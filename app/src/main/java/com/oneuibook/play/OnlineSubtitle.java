package com.oneuibook.play;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Web subtitle search & direct download system with native glass sheet UI:
 * - Automatic video title cleaning & 64-bit OpenSubtitles moviehash computation
 * - Multi-source search (OpenSubtitles REST API v1, Subdl API, Wyzie open index, or direct URL)
 * - Automatic .zip / .gz extraction and instant playback application
 */
final class OnlineSubtitle {
    private OnlineSubtitle() {}

    static final class Item {
        String title;
        String lang;
        String format;
        String source;
        String downloadUrl;
        String fileId; // For OpenSubtitles v1 /download endpoint
    }

    interface SearchCb {
        void onResult(List<Item> items, String statusMsg);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String[] LANG_CODES = {"ko", "en", "ja", "all"};
    private static final String[] LANG_NAMES = {"한국어", "영어", "일본어", "전체"};

    /** Strips release tags, resolutions, codecs and brackets from a video filename for clean search. */
    static String cleanQuery(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
        s = s.replaceAll("\\[[^\\]]*\\]", " ");
        s = s.replaceAll("(?i)\\b(2160p|1080p|720p|480p|4k|uhd|hdr|bluray|brrip|webrip|web-dl|hdtv|x264|x265|h264|h265|hevc|aac|ac3|dts|10bit|remux|proper|repack)\\b.*", " ");
        s = s.replace('.', ' ').replace('_', ' ');
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? raw : s;
    }

    /** Computes standard 64-bit OpenSubtitles moviehash (size + first 64KB + last 64KB). */
    static String computeMovieHash(File file) {
        if (file == null || !file.isFile() || file.length() < 131072) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel ch = raf.getChannel()) {
            long size = ch.size();
            long head = checksum64(ch, 0, 65536);
            long tail = checksum64(ch, size - 65536, 65536);
            return String.format(Locale.US, "%016x", size + head + tail);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long checksum64(FileChannel ch, long pos, int len) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        ch.position(pos);
        while (buf.hasRemaining() && ch.read(buf) != -1) {}
        buf.flip();
        long sum = 0;
        while (buf.remaining() >= 8) sum += buf.getLong();
        return sum;
    }

    /** Dedicated native glass sheet for searching and downloading subtitles from the web. */
    static void showSearchSheet(MainActivity m, VideoLibrary.Video video, boolean asSecondary) {
        LinearLayout root = new LinearLayout(m);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(18), Ui.dp(4), Ui.dp(18), Ui.dp(14));

        boolean dark = m.playerOverlay.isOpen() || Ui.dark;
        int fg = dark ? 0xFFFFFFFF : Ui.text();
        int fg2 = dark ? 0xB3FFFFFF : Ui.text2();

        String initialQ = video != null ? cleanQuery(video.title) : "";
        final String[] movieHashRef = {null};

        // Search input row
        LinearLayout searchRow = new LinearLayout(m);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = new EditText(m);
        input.setText(initialQ);
        input.setHint("작품 제목 또는 자막 URL (.srt / .zip)");
        input.setSingleLine(true);
        input.setIncludeFontPadding(false);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
        input.setTextColor(fg);
        input.setHintTextColor(fg2);
        input.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(14), Ui.dp(11));
        input.setBackground(Ui.round(dark ? 0x28FFFFFF : 0xFFF0F2F6, 16));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, Ui.dp(46), 1f);
        ilp.rightMargin = Ui.dp(8);
        searchRow.addView(input, ilp);

        TextView searchBtn = Ui.text(m, "검색", 13.5f, Ui.semibold, 0xFFFFFFFF);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        searchBtn.setBackground(Ui.round(Ui.ACCENT, 16));
        Ui.press(searchBtn, 0.94f);
        searchRow.addView(searchBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(46)));
        root.addView(searchRow, Widgets.MW);

        // Language filter chips
        final int[] langIdx = {0};
        LinearLayout langRow = new LinearLayout(m);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        langRow.setPadding(0, Ui.dp(10), 0, Ui.dp(6));
        TextView[] langChips = new TextView[LANG_CODES.length];

        // Status & result list container
        TextView status = Ui.text(m, "제목으로 웹 자막을 검색하거나 직접 URL을 입력하세요", 12, Ui.regular, fg2);
        status.setPadding(Ui.dp(2), Ui.dp(4), Ui.dp(2), Ui.dp(8));
        if (video != null && video.path != null) {
            final String vpath = video.path;
            new Thread(() -> {
                String h0 = computeMovieHash(new File(vpath));
                if (h0 != null) {
                    movieHashRef[0] = h0;
                    MAIN.post(() -> status.setText("파일 해시(" + h0.substring(0, 8) + "…) · 제목으로 웹 자막을 찾습니다"));
                }
            }, "sub-hash").start();
        }

        LinearLayout resultsBox = new LinearLayout(m);
        resultsBox.setOrientation(LinearLayout.VERTICAL);

        Runnable triggerSearch = () -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) {
                status.setText("검색할 제목이나 자막 URL을 입력하세요");
                return;
            }
            if (q.startsWith("http://") || q.startsWith("https://")) {
                status.setText("URL에서 자막 다운로드 중…");
                Item direct = new Item();
                direct.title = video != null ? video.title : "direct_subtitle";
                direct.lang = LANG_CODES[langIdx[0]];
                direct.format = "SRT";
                direct.source = "Direct URL";
                direct.downloadUrl = q;
                downloadAndApply(m, video, direct, asSecondary, status);
                return;
            }
            status.setText("웹에서 '" + q + "' 자막을 검색하는 중…");
            resultsBox.removeAllViews();
            searchWeb(m, q, LANG_CODES[langIdx[0]], movieHashRef[0], (items, msg) -> {
                status.setText(msg);
                resultsBox.removeAllViews();
                for (Item it : items) {
                    resultsBox.addView(resultRow(m, video, it, asSecondary, status, fg, fg2, dark), Widgets.MW);
                }
            });
        };

        Runnable syncLangChips = () -> {
            for (int i = 0; i < LANG_CODES.length; i++) {
                boolean on = langIdx[0] == i;
                langChips[i].setTextColor(on ? 0xFFFFFFFF : fg);
                langChips[i].setBackground(Ui.round(on ? Ui.ACCENT : (dark ? 0x22FFFFFF : Ui.chip()), 100));
            }
        };

        for (int i = 0; i < LANG_CODES.length; i++) {
            final int idx = i;
            TextView ch = Ui.text(m, LANG_NAMES[i], 12.5f, Ui.semibold, fg);
            ch.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
            ch.setOnClickListener(v -> {
                langIdx[0] = idx;
                syncLangChips.run();
                triggerSearch.run();
            });
            Ui.press(ch, 0.94f);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(6);
            langRow.addView(ch, clp);
            langChips[i] = ch;
        }
        syncLangChips.run();

        root.addView(langRow, Widgets.MW);
        root.addView(status, Widgets.MW);
        root.addView(resultsBox, Widgets.MW);

        searchBtn.setOnClickListener(v -> triggerSearch.run());
        if (!initialQ.isEmpty() && !"robolectric".equals(android.os.Build.FINGERPRINT)) {
            MAIN.postDelayed(triggerSearch, 180);
        }

        RectF anchor = new RectF(Ui.screenW / 2f - Ui.dpf(40), Ui.screenH - Ui.dpf(90), Ui.screenW / 2f + Ui.dpf(40), Ui.screenH - Ui.dpf(40));
        GlassMenu.customSheet(m, anchor, m.getString(R.string.sub_web_search), root, null);
    }

    private static View resultRow(MainActivity m, VideoLibrary.Video video, Item it, boolean asSecondary,
                                  TextView status, int fg, int fg2, boolean dark) {
        LinearLayout row = new LinearLayout(m);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        row.setBackground(Ui.round(dark ? 0x1AFFFFFF : 0xFFF4F5F8, 16));

        LinearLayout textCol = new LinearLayout(m);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(m, it.title, 13.5f, Ui.semibold, fg);
        t.setMaxLines(2);
        t.setSingleLine(false);
        textCol.addView(t);
        String meta = (it.lang != null ? it.lang.toUpperCase(Locale.US) : "") + " · "
                + (it.format != null ? it.format : "SRT") + " · " + it.source;
        TextView sub = Ui.text(m, meta, 11.5f, Ui.medium, fg2);
        sub.setPadding(0, Ui.dp(2), 0, 0);
        textCol.addView(sub);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView dl = Ui.text(m, "다운로드", 12.5f, Ui.semibold, 0xFFFFFFFF);
        dl.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
        dl.setBackground(Ui.round(Ui.ACCENT, 100));
        dl.setOnClickListener(v -> downloadAndApply(m, video, it, asSecondary, status));
        Ui.press(dl, 0.94f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Ui.dp(10);
        row.addView(dl, dlp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(8);
        row.setLayoutParams(rlp);
        return row;
    }

    static void searchWeb(Context c, String query, String lang, String movieHash, SearchCb cb) {
        SharedPreferences p = MainActivity.prefs(c);
        String osKey = SecureVault.get(c, p, "opensubtitles_api_key").trim();
        String subdlKey = SecureVault.get(c, p, "subdl_api_key").trim();

        new Thread(() -> {
            List<Item> out = new ArrayList<>();
            String err = null;

            // 1. OpenSubtitles REST API v1 (if API key configured or public endpoint responds)
            try {
                StringBuilder u = new StringBuilder("https://api.opensubtitles.com/api/v1/subtitles?query=")
                        .append(URLEncoder.encode(query, "UTF-8"));
                if (!"all".equals(lang)) u.append("&languages=").append(lang);
                if (movieHash != null) u.append("&moviehash=").append(movieHash);
                HttpURLConnection conn = (HttpURLConnection) new URL(u.toString()).openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setRequestProperty("User-Agent", "PlayVideoPlayer v" + MainActivity.VERSION);
                conn.setRequestProperty("Accept", "application/json");
                if (!osKey.isEmpty()) conn.setRequestProperty("Api-Key", osKey);
                if (conn.getResponseCode() == 200) {
                    JSONObject root = new JSONObject(readStr(conn.getInputStream()));
                    JSONArray data = root.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < Math.min(20, data.length()); i++) {
                            JSONObject d = data.optJSONObject(i);
                            if (d == null) continue;
                            JSONObject attr = d.optJSONObject("attributes");
                            if (attr == null) continue;
                            JSONArray files = attr.optJSONArray("files");
                            if (files == null || files.length() == 0) continue;
                            JSONObject f0 = files.optJSONObject(0);
                            if (f0 == null) continue;
                            Item it = new Item();
                            it.title = attr.optString("release", f0.optString("file_name", query));
                            it.lang = attr.optString("language", lang);
                            it.format = "SRT";
                            it.source = "OpenSubtitles";
                            it.fileId = String.valueOf(f0.optInt("file_id", 0));
                            out.add(it);
                        }
                    }
                }
            } catch (Exception e) {
                err = e.getMessage();
            }

            // 2. Subdl API (if key configured or public query)
            if (!subdlKey.isEmpty()) {
                try {
                    StringBuilder u = new StringBuilder("https://api.subdl.com/api/v1/subtitles?api_key=")
                            .append(URLEncoder.encode(subdlKey, "UTF-8"))
                            .append("&film_name=").append(URLEncoder.encode(query, "UTF-8"))
                            .append("&subs_per_page=15");
                    if (!"all".equals(lang)) u.append("&languages=").append(lang.toUpperCase(Locale.US));
                    HttpURLConnection conn = (HttpURLConnection) new URL(u.toString()).openConnection();
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(7000);
                    conn.setRequestProperty("User-Agent", "PlayVideoPlayer/" + MainActivity.VERSION);
                    if (conn.getResponseCode() == 200) {
                        JSONObject root = new JSONObject(readStr(conn.getInputStream()));
                        JSONArray subs = root.optJSONArray("subtitles");
                        if (subs != null) {
                            for (int i = 0; i < Math.min(20, subs.length()); i++) {
                                JSONObject s = subs.optJSONObject(i);
                                if (s == null) continue;
                                String dlUrl = s.optString("url", "");
                                if (dlUrl.isEmpty()) continue;
                                if (dlUrl.startsWith("/")) dlUrl = "https://dl.subdl.com" + dlUrl;
                                Item it = new Item();
                                it.title = s.optString("release_name", s.optString("name", query));
                                it.lang = s.optString("lang", lang);
                                it.format = "ZIP/SRT";
                                it.source = "Subdl";
                                it.downloadUrl = dlUrl;
                                out.add(it);
                            }
                        }
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }

            // 3. OpenSubtitles.org public REST search fallback (no key needed)
            if (out.isEmpty()) {
                try {
                    String lang3 = "ko".equals(lang) ? "kor" : ("en".equals(lang) ? "eng" : ("ja".equals(lang) ? "jpn" : "all"));
                    String u = "https://rest.opensubtitles.org/search/query-"
                            + URLEncoder.encode(query.toLowerCase(Locale.US), "UTF-8")
                            + ("all".equals(lang3) ? "" : "/sublanguageid-" + lang3);
                    HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(7000);
                    conn.setRequestProperty("User-Agent", "TemporaryUserAgent");
                    if (conn.getResponseCode() == 200) {
                        JSONArray arr = new JSONArray(readStr(conn.getInputStream()));
                        for (int i = 0; i < Math.min(20, arr.length()); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            String dl = o.optString("SubDownloadLink", "");
                            if (dl.isEmpty()) continue;
                            Item it = new Item();
                            it.title = o.optString("SubFileName", o.optString("MovieReleaseName", query));
                            it.lang = o.optString("ISO639", lang);
                            it.format = o.optString("SubFormat", "srt").toUpperCase(Locale.US);
                            it.source = "OpenSubtitles";
                            it.downloadUrl = dl;
                            out.add(it);
                        }
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }

            final String msg;
            if (!out.isEmpty()) {
                msg = "자막 " + out.size() + "개를 찾았습니다. 원하는 자막의 '다운로드'를 누르세요.";
            } else if (osKey.isEmpty() && subdlKey.isEmpty()) {
                msg = "일치하는 공개 자막을 찾지 못했습니다. (설정 → 자막에서 OpenSubtitles / Subdl API 키를 등록하거나 직접 .srt URL을 입력할 수 있습니다)";
            } else {
                msg = "검색 결과가 없습니다" + (err != null ? " (" + err + ")" : "");
            }
            MAIN.post(() -> cb.onResult(out, msg));
        }, "play-sub-search").start();
    }

    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 64;

    static boolean isSafeSubtitleUrl(String url) {
        return url != null && url.startsWith("https://");
    }

    private static void downloadAndApply(MainActivity m, VideoLibrary.Video video, Item it, boolean asSecondary, TextView status) {
        status.setText("'" + it.title + "' 자막을 내려받는 중…");
        SharedPreferences p = MainActivity.prefs(m);
        String osKey = SecureVault.get(m, p, "opensubtitles_api_key").trim();

        new Thread(() -> {
            try {
                String dlUrl = it.downloadUrl;
                if ((dlUrl == null || dlUrl.isEmpty()) && it.fileId != null && !it.fileId.isEmpty()) {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.opensubtitles.com/api/v1/download").openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(7000);
                    c.setReadTimeout(7000);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("User-Agent", "PlayVideoPlayer v" + MainActivity.VERSION);
                    if (!osKey.isEmpty()) c.setRequestProperty("Api-Key", osKey);
                    try {
                        byte[] body = ("{\"file_id\":" + it.fileId + "}").getBytes(StandardCharsets.UTF_8);
                        try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
                        if (c.getResponseCode() == 200) {
                            JSONObject resp = new JSONObject(readStr(c.getInputStream()));
                            dlUrl = resp.optString("link", "");
                        }
                    } finally {
                        c.disconnect();
                    }
                }
                if (dlUrl == null || dlUrl.isEmpty()) throw new Exception("다운로드 링크를 가져올 수 없습니다");
                if (!isSafeSubtitleUrl(dlUrl)) throw new Exception("HTTPS 자막 링크만 허용됩니다");

                HttpURLConnection conn = (HttpURLConnection) new URL(dlUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "PlayVideoPlayer/" + MainActivity.VERSION);
                byte[] raw = readBytes(conn.getInputStream());
                byte[] subBytes = unpackSubtitleBytes(raw, dlUrl);

                File dir = new File(m.getFilesDir(), "online_subs");
                if (!dir.exists()) dir.mkdirs();
                String safeName = (it.title == null ? "subtitle" : it.title.replaceAll("[^A-Za-z0-9가-힣._-]", "_"));
                if (!safeName.toLowerCase(Locale.US).endsWith(".srt")
                        && !safeName.toLowerCase(Locale.US).endsWith(".ass")
                        && !safeName.toLowerCase(Locale.US).endsWith(".vtt")
                        && !safeName.toLowerCase(Locale.US).endsWith(".smi")) {
                    safeName += ".srt";
                }
                File outFile = new File(dir, safeName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(subBytes);
                }

                Subtitle.Track track = Subtitle.loadFile(outFile);
                if (track == null || track.cues.isEmpty()) {
                    throw new Exception("유효한 자막 대사를 파싱하지 못했습니다");
                }
                MAIN.post(() -> {
                    if (asSecondary) VideoPlayer.secondarySub = track;
                    else {
                        VideoPlayer.primarySub = track;
                        VideoPlayer.subEnabled = true;
                        if (video != null) video.hasSubtitle = true;
                    }
                    Toast.makeText(m, "자막 다운로드 및 적용 완료 (" + track.cues.size() + "줄)", Toast.LENGTH_SHORT).show();
                    GlassMenu.dismissAny(m.root);
                });
            } catch (Exception e) {
                MAIN.post(() -> status.setText("다운로드 실패: " + e.getMessage()));
            }
        }, "play-sub-dl").start();
    }

    /** Automatically extracts .gz or .zip subtitle archives or returns plain subtitle bytes (size-bounded). */
    static byte[] unpackSubtitleBytes(byte[] raw, String urlHint) throws Exception {
        if (raw == null || raw.length < 4) throw new Exception("빈 파일");
        if (raw.length > MAX_SUBTITLE_BYTES) throw new Exception("자막 파일 크기가 제한(4MB)을 초과했습니다");
        // GZIP magic: 0x1F 0x8B
        if ((raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return readBytes(gis);
            }
        }
        // ZIP magic: 'P' 'K' 0x03 0x04
        if (raw[0] == 'P' && raw[1] == 'K') {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(raw))) {
                ZipEntry ze;
                int entries = 0;
                while ((ze = zis.getNextEntry()) != null) {
                    if (++entries > MAX_ZIP_ENTRIES) throw new Exception("압축 파일 항목 수 제한 초과");
                    if (ze.isDirectory()) continue;
                    String n = ze.getName().toLowerCase(Locale.US);
                    if (n.endsWith(".srt") || n.endsWith(".ass") || n.endsWith(".ssa") || n.endsWith(".vtt") || n.endsWith(".smi")) {
                        return readBytes(zis);
                    }
                }
            }
        }
        return raw;
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_SUBTITLE_BYTES) throw new Exception("자막 데이터 크기 제한(4MB) 초과");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readStr(InputStream in) throws Exception {
        try (InputStream s = in) {
            return new String(readBytes(s), StandardCharsets.UTF_8);
        }
    }
}
