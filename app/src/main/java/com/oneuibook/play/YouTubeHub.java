package com.oneuibook.play;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Play-UI YouTube Hub & Cinema Player:
 * - Browses YouTube videos inside Play's own A/G glass UI (Chips, 16:9 cards, channel subscriptions, search).
 * - Uses public YouTube Atom/RSS feeds, official oEmbed metadata, optional YouTube Data API v3 key,
 *   and configurable public Piped/Invidious JSON API endpoints for search, trending, and comments.
 * - Full-screen Play-UI YouTube Player Overlay with Play's own Glass/G-style top & bottom controls,
 *   speed control (0.25x..2.0x), NPU ProScaler Assist & Bit-Perfect Assist quick access,
 *   comments & video description sheet, and optional 1-tap handoff to org.schabi.newpipe if installed.
 */
final class YouTubeHub {
    private YouTubeHub() {}

    static final String PREF_API_KEY = "yt_api_key";
    static final String PREF_PIPED_URL = "yt_piped_url";
    static final String PREF_SUB_CHANNELS = "yt_sub_channels";
    static final String DEFAULT_PIPED = "https://pipedapi.kavin.rocks";
    static final String NEWPIPE_PKG = "org.schabi.newpipe";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(80);

    private static final Pattern ID_PAT = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?.*v=|embed/|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");
    private static final Pattern RAW_ID_PAT = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    static final class YtVideo {
        final String id;
        String title;
        String author;
        String channelId;
        String published;
        String views;
        String description;
        long durationSec;

        YtVideo(String id, String title, String author) {
            this.id = id == null ? "" : id.trim();
            this.title = title == null || title.isEmpty() ? "YouTube (" + this.id + ")" : title;
            this.author = author == null ? "YouTube" : author;
            this.channelId = "";
            this.published = "";
            this.views = "";
            this.description = "";
        }

        String thumbUrl() {
            return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        }

        String watchUrl() {
            return "https://www.youtube.com/watch?v=" + id;
        }
    }

    static final class YtComment {
        final String author;
        final String text;
        final String time;
        final String likes;

        YtComment(String author, String text, String time, String likes) {
            this.author = author == null ? "" : author;
            this.text = text == null ? "" : text;
            this.time = time == null ? "" : time;
            this.likes = likes == null ? "" : likes;
        }
    }

    /** Extracts an 11-char YouTube video ID from any youtube.com / youtu.be URL or raw 11-char ID, or null. */
    static String extractVideoId(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (RAW_ID_PAT.matcher(s).matches()) return s;
        Matcher m = ID_PAT.matcher(s);
        if (m.find()) return m.group(1);
        try {
            Uri u = Uri.parse(s);
            String host = u.getHost();
            if (host != null) {
                host = host.toLowerCase(Locale.US);
                if (host.contains("youtube.com") || host.contains("youtube-nocookie.com")) {
                    String v = u.getQueryParameter("v");
                    if (v != null && RAW_ID_PAT.matcher(v).matches()) return v;
                } else if (host.contains("youtu.be")) {
                    String seg = u.getLastPathSegment();
                    if (seg != null && RAW_ID_PAT.matcher(seg).matches()) return seg;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static boolean isYouTubeUrl(String text) {
        return extractVideoId(text) != null;
    }

    static boolean isNewPipeInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(NEWPIPE_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    static void openInNewPipe(Context c, String videoId) {
        String url = "https://www.youtube.com/watch?v=" + videoId;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (isNewPipeInstalled(c)) i.setPackage(NEWPIPE_PKG);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {}
        }
    }

    // ---- Curated default channels for instant RSS feed browsing without API keys ----
    private static final String[][] CURATED_CHANNELS = {
            // Tech & Hardware
            {"UCBJycsmduvYEL83R_U4JriQ", "Marques Brownlee (MKBHD)", "tech"},
            {"UCXuqSBlHAE6Xw-yeJA0Tunw", "Linus Tech Tips", "tech"},
            {"UCsXVk37bltHxD1rDPwtNM8Q", "Kurzgesagt", "tech"},
            // Music
            {"UC38IQsAvIsxxjztdMZQtwHA", "HYBE LABELS", "music"},
            {"UCEf_Bc-KVd7onSeifS3py9g", "SMTOWN", "music"},
            {"UCaO6TYtlC8U5ttz62hTrZgg", "JYP Entertainment", "music"},
            // Gaming
            {"UC-2Y8dQb0S6DtpxNgAKoJKA", "PlayStation", "gaming"},
            {"UCGIY_O-8vW4rfX98KlMkvRg", "Nintendo", "gaming"},
            // Film & Animation
            {"UCi8e0iOVk1fEOogdfu4YgfA", "Movieclips Trailers", "film"},
            {"UCjmJDM5pRKbUlVIzDYYWb6g", "Warner Bros. Pictures", "film"}
    };

    private static List<String[]> subscribedChannels(Context c) {
        List<String[]> list = new ArrayList<>();
        for (String[] cc : CURATED_CHANNELS) list.add(cc);
        String extra = MainActivity.prefs(c).getString(PREF_SUB_CHANNELS, "");
        if (!extra.isEmpty()) {
            for (String line : extra.split("\n")) {
                String[] parts = line.trim().split("\\|", 2);
                if (parts.length >= 1 && !parts[0].isEmpty()) {
                    list.add(0, new String[]{parts[0].trim(), parts.length > 1 ? parts[1].trim() : parts[0].trim(), "custom"});
                }
            }
        }
        return list;
    }

    static void addChannelSubscription(Context c, String channelId, String name) {
        if (channelId == null || channelId.trim().isEmpty()) return;
        String cur = MainActivity.prefs(c).getString(PREF_SUB_CHANNELS, "");
        String entry = channelId.trim() + "|" + (name == null || name.trim().isEmpty() ? channelId.trim() : name.trim());
        String next = cur.isEmpty() ? entry : entry + "\n" + cur;
        MainActivity.prefs(c).edit().putString(PREF_SUB_CHANNELS, next).apply();
    }

    interface ListCb { void onResult(List<YtVideo> videos, String sourceLabel); }
    interface CommentsCb { void onResult(List<YtComment> comments, String description); }

    static void loadCategory(Context c, int categoryIdx, ListCb cb) {
        String[] cats = {"all", "music", "gaming", "film", "tech"};
        String filter = categoryIdx >= 0 && categoryIdx < cats.length ? cats[categoryIdx] : "all";
        String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        String apiKey = MainActivity.prefs(c).getString(PREF_API_KEY, "").trim();

        EXEC.execute(() -> {
            List<YtVideo> out = new ArrayList<>();
            String src = "YouTube RSS";
            // 1) If YouTube Data API v3 key is configured and category is trending
            if (!apiKey.isEmpty() && categoryIdx == 0) {
                try {
                    String u = "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&regionCode=KR&maxResults=16&key="
                            + URLEncoder.encode(apiKey, "UTF-8");
                    String json = httpGet(u, 5500);
                    if (json != null) {
                        JSONObject root = new JSONObject(json);
                        JSONArray items = root.optJSONArray("items");
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject it = items.getJSONObject(i);
                                String id = it.optString("id");
                                JSONObject sn = it.optJSONObject("snippet");
                                if (!id.isEmpty() && sn != null) {
                                    YtVideo v = new YtVideo(id, sn.optString("title"), sn.optString("channelTitle"));
                                    v.published = sn.optString("publishedAt");
                                    v.description = sn.optString("description");
                                    out.add(v);
                                }
                            }
                            if (!out.isEmpty()) src = "YouTube Data API v3";
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2) Try Piped trending JSON API if category == 0 and out is empty
            if (out.isEmpty() && categoryIdx == 0 && !pipedBase.isEmpty()) {
                try {
                    String base = pipedBase.endsWith("/") ? pipedBase.substring(0, pipedBase.length() - 1) : pipedBase;
                    String json = httpGet(base + "/trending?region=KR", 4500);
                    if (json != null && json.trim().startsWith("[")) {
                        JSONArray arr = new JSONArray(json);
                        for (int i = 0; i < Math.min(20, arr.length()); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            String url = o.optString("url");
                            String id = extractVideoId("https://www.youtube.com" + url);
                            if (id != null) {
                                YtVideo v = new YtVideo(id, o.optString("title"), o.optString("uploaderName"));
                                v.durationSec = Math.max(0, o.optLong("duration", 0));
                                long views = o.optLong("views", -1);
                                if (views > 0) v.views = formatCount(views) + "회";
                                v.published = o.optString("uploadedDate");
                                out.add(v);
                            }
                        }
                        if (!out.isEmpty()) src = "Piped API";
                    }
                } catch (Throwable ignored) {}
            }

            // 3) Always supplement / fallback to official YouTube public Atom/RSS feeds
            if (out.isEmpty()) {
                List<String[]> chans = subscribedChannels(c);
                int fetched = 0;
                for (String[] ch : chans) {
                    if (!"all".equals(filter) && !"custom".equals(ch[2]) && !filter.equals(ch[2])) continue;
                    List<YtVideo> feed = fetchChannelRss(ch[0]);
                    for (int i = 0; i < Math.min(4, feed.size()); i++) out.add(feed.get(i));
                    if (++fetched >= 5) break;
                }
            }

            final List<YtVideo> res = out;
            final String label = src;
            MAIN.post(() -> cb.onResult(res, label));
        });
    }

    static void searchVideos(Context c, String query, ListCb cb) {
        if (query == null || query.trim().isEmpty()) {
            loadCategory(c, 0, cb);
            return;
        }
        final String q = query.trim();
        // Check if query is a direct YouTube URL or 11-char video ID
        final String directId = extractVideoId(q);
        final String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        final String apiKey = MainActivity.prefs(c).getString(PREF_API_KEY, "").trim();

        EXEC.execute(() -> {
            List<YtVideo> out = new ArrayList<>();
            String src = "YouTube";
            if (directId != null) {
                YtVideo v = fetchOEmbed(directId);
                out.add(v);
                final List<YtVideo> res = out;
                MAIN.post(() -> cb.onResult(res, "YouTube oEmbed"));
                return;
            }

            // 1) YouTube Data API v3 search if key configured
            if (!apiKey.isEmpty()) {
                try {
                    String u = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=16&q="
                            + URLEncoder.encode(q, "UTF-8") + "&key=" + URLEncoder.encode(apiKey, "UTF-8");
                    String json = httpGet(u, 5500);
                    if (json != null) {
                        JSONObject root = new JSONObject(json);
                        JSONArray items = root.optJSONArray("items");
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject it = items.getJSONObject(i);
                                JSONObject idObj = it.optJSONObject("id");
                                JSONObject sn = it.optJSONObject("snippet");
                                String vid = idObj != null ? idObj.optString("videoId") : "";
                                if (!vid.isEmpty() && sn != null) {
                                    YtVideo v = new YtVideo(vid, sn.optString("title"), sn.optString("channelTitle"));
                                    v.published = sn.optString("publishedAt");
                                    v.description = sn.optString("description");
                                    out.add(v);
                                }
                            }
                            if (!out.isEmpty()) src = "YouTube Data API v3";
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2) Piped JSON Search API
            if (out.isEmpty() && !pipedBase.isEmpty()) {
                String[] endpoints = {
                        pipedBase,
                        "https://pipedapi.kavin.rocks",
                        "https://pipedapi.adminforge.de"
                };
                for (String ep : endpoints) {
                    if (ep == null || ep.isEmpty()) continue;
                    try {
                        String base = ep.endsWith("/") ? ep.substring(0, ep.length() - 1) : ep;
                        String u = base + "/search?q=" + URLEncoder.encode(q, "UTF-8") + "&filter=videos";
                        String json = httpGet(u, 4500);
                        if (json != null) {
                            JSONObject root = new JSONObject(json);
                            JSONArray items = root.optJSONArray("items");
                            if (items != null) {
                                for (int i = 0; i < Math.min(20, items.length()); i++) {
                                    JSONObject o = items.getJSONObject(i);
                                    String id = extractVideoId("https://www.youtube.com" + o.optString("url"));
                                    if (id != null) {
                                        YtVideo v = new YtVideo(id, o.optString("title"), o.optString("uploaderName"));
                                        v.durationSec = Math.max(0, o.optLong("duration", 0));
                                        long views = o.optLong("views", -1);
                                        if (views > 0) v.views = formatCount(views) + "회";
                                        v.published = o.optString("uploadedDate");
                                        out.add(v);
                                    }
                                }
                            }
                        }
                        if (!out.isEmpty()) {
                            src = "Piped Search";
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // 3) Filter local RSS feeds by query if offline/unreachable
            if (out.isEmpty()) {
                String ql = q.toLowerCase(Locale.US);
                for (String[] ch : subscribedChannels(c)) {
                    for (YtVideo v : fetchChannelRss(ch[0])) {
                        if (v.title.toLowerCase(Locale.US).contains(ql) || v.author.toLowerCase(Locale.US).contains(ql)) {
                            out.add(v);
                        }
                    }
                }
                src = "YouTube RSS";
            }

            final List<YtVideo> res = out;
            final String label = src;
            MAIN.post(() -> cb.onResult(res, label));
        });
    }

    static void loadCommentsAndMeta(Context c, YtVideo v, CommentsCb cb) {
        final String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        final String apiKey = MainActivity.prefs(c).getString(PREF_API_KEY, "").trim();
        EXEC.execute(() -> {
            List<YtComment> comments = new ArrayList<>();
            String desc = v.description == null ? "" : v.description;

            // Populate title/author via official oEmbed if missing
            if (v.title.startsWith("YouTube (")) {
                YtVideo oe = fetchOEmbed(v.id);
                v.title = oe.title;
                v.author = oe.author;
            }

            // 1) YouTube Data API v3 commentThreads if key is set
            if (!apiKey.isEmpty()) {
                try {
                    String u = "https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&maxResults=25&videoId="
                            + URLEncoder.encode(v.id, "UTF-8") + "&key=" + URLEncoder.encode(apiKey, "UTF-8");
                    String json = httpGet(u, 5000);
                    if (json != null) {
                        JSONObject root = new JSONObject(json);
                        JSONArray items = root.optJSONArray("items");
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject top = items.getJSONObject(i).optJSONObject("snippet");
                                JSONObject tl = top != null ? top.optJSONObject("topLevelComment") : null;
                                JSONObject sn = tl != null ? tl.optJSONObject("snippet") : null;
                                if (sn != null) {
                                    comments.add(new YtComment(
                                            sn.optString("authorDisplayName"),
                                            sn.optString("textOriginal", sn.optString("textDisplay")),
                                            sn.optString("publishedAt"),
                                            String.valueOf(sn.optInt("likeCount", 0))));
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2) Piped JSON comments endpoint
            if (comments.isEmpty() && !pipedBase.isEmpty()) {
                try {
                    String base = pipedBase.endsWith("/") ? pipedBase.substring(0, pipedBase.length() - 1) : pipedBase;
                    String json = httpGet(base + "/comments/" + v.id, 4500);
                    if (json != null) {
                        JSONObject root = new JSONObject(json);
                        JSONArray arr = root.optJSONArray("comments");
                        if (arr != null) {
                            for (int i = 0; i < Math.min(25, arr.length()); i++) {
                                JSONObject o = arr.getJSONObject(i);
                                comments.add(new YtComment(
                                        o.optString("author"),
                                        o.optString("commentText"),
                                        o.optString("commentedTime"),
                                        String.valueOf(o.optInt("likeCount", 0))));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            final String fDesc = desc;
            MAIN.post(() -> cb.onResult(comments, fDesc));
        });
    }

    private static YtVideo fetchOEmbed(String videoId) {
        try {
            String u = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json";
            String json = httpGet(u, 4000);
            if (json != null) {
                JSONObject o = new JSONObject(json);
                return new YtVideo(videoId, o.optString("title"), o.optString("author_name"));
            }
        } catch (Throwable ignored) {}
        return new YtVideo(videoId, "YouTube (" + videoId + ")", "YouTube");
    }

    /** Parses official YouTube public Atom feed: https://www.youtube.com/feeds/videos.xml?channel_id=... */
    private static List<YtVideo> fetchChannelRss(String channelId) {
        List<YtVideo> list = new ArrayList<>();
        try {
            String xml = httpGet("https://www.youtube.com/feeds/videos.xml?channel_id=" + URLEncoder.encode(channelId, "UTF-8"), 4500);
            if (xml == null || xml.isEmpty()) return list;
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            f.setNamespaceAware(true);
            XmlPullParser p = f.newPullParser();
            p.setInput(new StringReader(xml));

            boolean inEntry = false;
            String vid = "", title = "", author = "", pub = "", desc = "", views = "";
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = p.getName();
                    if ("entry".equals(name)) {
                        inEntry = true;
                        vid = title = author = pub = desc = views = "";
                    } else if (inEntry) {
                        if ("videoId".equals(name)) vid = p.nextText();
                        else if ("title".equals(name) && title.isEmpty()) title = p.nextText();
                        else if ("name".equals(name) && author.isEmpty()) author = p.nextText();
                        else if ("published".equals(name)) pub = p.nextText();
                        else if ("description".equals(name)) desc = p.nextText();
                        else if ("statistics".equals(name)) {
                            String vw = p.getAttributeValue(null, "views");
                            if (vw != null) {
                                try { views = formatCount(Long.parseLong(vw)) + "회"; } catch (Throwable ignored) {}
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && "entry".equals(p.getName())) {
                    inEntry = false;
                    if (!vid.isEmpty()) {
                        YtVideo v = new YtVideo(vid, title, author);
                        v.channelId = channelId;
                        v.published = pub.length() >= 10 ? pub.substring(0, 10) : pub;
                        v.description = desc;
                        v.views = views;
                        list.add(v);
                    }
                }
                ev = p.next();
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private static String formatCount(long n) {
        if (n >= 100_000_000L) return String.format(Locale.US, "%.1f억", n / 100_000_000.0);
        if (n >= 10_000L) return String.format(Locale.US, "%.1f만", n / 10_000.0);
        if (n >= 1_000L) return String.format(Locale.US, "%.1f천", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String httpGet(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json, application/atom+xml, text/xml, */*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void loadThumb(YtVideo v, ThumbCb cb) {
        Bitmap cached = THUMBS.get(v.id);
        if (cached != null) {
            cb.onBitmap(cached);
            return;
        }
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(v.thumbUrl()).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        Bitmap b = BitmapFactory.decodeStream(is);
                        if (b != null) {
                            THUMBS.put(v.id, b);
                            MAIN.post(() -> cb.onBitmap(b));
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private interface ThumbCb { void onBitmap(Bitmap b); }

    // ==================================================================
    // Play-UI YouTube Browser Page
    // ==================================================================
    static final class YouTubePage extends Page {
        private int category = 0;
        private String currentQuery = "";
        private LinearLayout resultsHost;
        private TextView statusLabel;

        YouTubePage(MainActivity m) {
            super(m, m.getString(R.string.yt_title), true);
            back = true;
            headerIcons = new int[]{Icons.PLUS, Icons.SETTINGS};
        }

        @Override void build() {
            MainActivity m = main;

            // 1. Search & Direct URL Bar Card
            Widgets.Group searchGroup = new Widgets.Group(m, null);
            LinearLayout sRow = new LinearLayout(m);
            sRow.setOrientation(LinearLayout.HORIZONTAL);
            sRow.setGravity(Gravity.CENTER_VERTICAL);
            sRow.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            sRow.addView(new Icons.IconView(m, Icons.YOUTUBE, 22, 0xFFFF0033), new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)));

            EditText et = new EditText(m);
            et.setBackground(null);
            et.setSingleLine(true);
            et.setHint(m.getString(R.string.yt_search_hint));
            et.setText(currentQuery);
            et.setTextSize(15f);
            et.setTextColor(Ui.text());
            et.setHintTextColor(Ui.text3());
            et.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
            et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            et.setInputType(InputType.TYPE_CLASS_TEXT);
            sRow.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView goBtn = Ui.text(m, m.getString(R.string.search), 13.5f, Ui.semibold, 0xFFFFFFFF);
            goBtn.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
            goBtn.setBackground(Ui.round(Ui.ACCENT, 100));
            Runnable triggerSearch = () -> {
                String q = et.getText().toString().trim();
                String vid = extractVideoId(q);
                if (vid != null && (q.contains("youtube.com") || q.contains("youtu.be") || RAW_ID_PAT.matcher(q).matches())) {
                    openVideo(m, new YtVideo(vid, "YouTube (" + vid + ")", "YouTube"));
                    return;
                }
                currentQuery = q;
                fetchVideos();
            };
            goBtn.setOnClickListener(v -> triggerSearch.run());
            et.setOnEditorActionListener((v, id, ev) -> { triggerSearch.run(); return true; });
            Ui.press(goBtn, 0.94f);
            sRow.addView(goBtn);
            searchGroup.addCustom(sRow);
            content.addView(searchGroup, Widgets.MW);

            // 2. Category Chips
            String[] cats = {
                    m.getString(R.string.yt_cat_trending),
                    m.getString(R.string.yt_cat_music),
                    m.getString(R.string.yt_cat_gaming),
                    m.getString(R.string.yt_cat_film),
                    m.getString(R.string.yt_cat_tech)
            };
            Widgets.Chips chips = new Widgets.Chips(m, cats, category, idx -> {
                category = idx;
                currentQuery = "";
                et.setText("");
                fetchVideos();
            });
            content.addView(chips, Widgets.MW);

            // 3. Status & Source indicator
            LinearLayout metaRow = new LinearLayout(m);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            metaRow.setPadding(Ui.dp(22), Ui.dp(4), Ui.dp(22), Ui.dp(6));
            statusLabel = Ui.text(m, m.getString(R.string.yt_loading), 12.5f, Ui.medium, Ui.text2());
            metaRow.addView(statusLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView cfgBtn = Ui.text(m, m.getString(R.string.yt_source_settings), 12.5f, Ui.semibold, Ui.ACCENT);
            cfgBtn.setOnClickListener(v -> showSourceSettingsSheet(m));
            metaRow.addView(cfgBtn);
            content.addView(metaRow, Widgets.MW);

            // 4. Video Grid Host
            resultsHost = new LinearLayout(m);
            resultsHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(resultsHost, Widgets.MW);

            fetchVideos();
        }

        private void fetchVideos() {
            if (resultsHost == null) return;
            resultsHost.removeAllViews();
            statusLabel.setText(main.getString(R.string.yt_loading));
            ListCb cb = (videos, src) -> {
                if (resultsHost == null) return;
                resultsHost.removeAllViews();
                if (videos.isEmpty()) {
                    statusLabel.setText(main.getString(R.string.yt_empty));
                    return;
                }
                statusLabel.setText(src + " · " + videos.size() + "개 동영상"
                        + (isNewPipeInstalled(main) ? " · NewPipe 연동됨" : ""));
                renderVideoCards(videos);
            };
            if (!currentQuery.isEmpty()) searchVideos(main, currentQuery, cb);
            else loadCategory(main, category, cb);
        }

        private void renderVideoCards(List<YtVideo> videos) {
            MainActivity m = main;
            int cols = Pages.gridColumns(m);
            int gap = Ui.dp(12);
            int pad = Ui.dp(18);
            int avail = Widgets.contentW(m) - pad * 2 - gap * (cols - 1);
            int cardW = Math.max(Ui.dp(120), avail / cols);

            for (int i = 0; i < videos.size(); i += cols) {
                LinearLayout row = new LinearLayout(m);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(pad, 0, pad, Ui.dp(16));
                for (int c = 0; c < cols; c++) {
                    int idx = i + c;
                    if (idx < videos.size()) {
                        YtVideo v = videos.get(idx);
                        View card = buildYtCard(m, v, cardW);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
                        if (c < cols - 1) lp.rightMargin = gap;
                        row.addView(card, lp);
                    } else {
                        View sp = new View(m);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, 1);
                        if (c < cols - 1) lp.rightMargin = gap;
                        row.addView(sp, lp);
                    }
                }
                resultsHost.addView(row, Widgets.MW);
            }
        }

        private View buildYtCard(MainActivity m, YtVideo v, int widthPx) {
            int h = (int) (widthPx * 9f / 16f);
            float rad = Ui.gStyle ? 20f : 14f;
            LinearLayout box = new LinearLayout(m);
            box.setOrientation(LinearLayout.VERTICAL);

            FrameLayout thumbBox = new FrameLayout(m);
            YtThumbView tv = new YtThumbView(m, rad);
            tv.bind(v);
            thumbBox.addView(tv, new FrameLayout.LayoutParams(widthPx, h));

            // YouTube badge top-left
            TextView ytBadge = Widgets.pillBadge(m, "YouTube", 0xCCFF0033, 0xFFFFFFFF);
            FrameLayout.LayoutParams ylp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            ylp.setMargins(Ui.dp(8), Ui.dp(8), 0, 0);
            thumbBox.addView(ytBadge, ylp);

            if (v.durationSec > 0) {
                TextView dur = Widgets.pillBadge(m, Ui.time(v.durationSec * 1000L), 0xB8000000, 0xFFFFFFFF);
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                dlp.setMargins(0, 0, Ui.dp(8), Ui.dp(8));
                thumbBox.addView(dur, dlp);
            }

            box.addView(thumbBox, new LinearLayout.LayoutParams(widthPx, h));
            TextView title = Ui.text(m, v.title, 13.5f, Ui.semibold, Ui.text());
            title.setSingleLine(false);
            title.setMaxLines(2);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = Ui.dp(7);
            box.addView(title, tlp);

            StringBuilder meta = new StringBuilder(v.author);
            if (!v.views.isEmpty()) meta.append(" · ").append(v.views);
            else if (!v.published.isEmpty()) meta.append(" · ").append(v.published);
            TextView sub = Ui.text(m, meta.toString(), 11.5f, Ui.regular, Ui.text2());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(2);
            box.addView(sub, slp);

            box.setOnClickListener(vv -> openVideo(m, v));
            box.setOnLongClickListener(vv -> {
                showYtVideoMenu(m, v, vv);
                return true;
            });
            Ui.press(box, Ui.gStyle ? 0.98f : 0.96f);
            return box;
        }
    }

    static void showYtVideoMenu(MainActivity m, YtVideo v, View anchorView) {
        float[] o = Glass.abs(anchorView);
        RectF r = new RectF(o[0], o[1], o[0] + anchorView.getWidth(), o[1] + anchorView.getHeight());
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.yt_play_in_app), Icons.PLAY, false, () -> openVideo(m, v)));
        if (isNewPipeInstalled(m)) {
            es.add(new GlassMenu.Entry(m.getString(R.string.yt_open_newpipe), Icons.YOUTUBE, false, () -> openInNewPipe(m, v.id)));
        }
        es.add(new GlassMenu.Entry(m.getString(R.string.yt_open_external), Icons.GLOBE, false, () -> openInNewPipe(m, v.id)));
        GlassMenu.sheet(m, r, v.title, es, null);
    }

    static void showSourceSettingsSheet(MainActivity m) {
        LinearLayout form = new LinearLayout(m);
        form.setOrientation(LinearLayout.VERTICAL);
        String curPiped = MainActivity.prefs(m).getString(PREF_PIPED_URL, DEFAULT_PIPED);
        String curKey = MainActivity.prefs(m).getString(PREF_API_KEY, "");
        EditText pipedEt = Widgets.formField(m, m.getString(R.string.yt_piped_instance), DEFAULT_PIPED, curPiped,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, form);
        EditText keyEt = Widgets.formField(m, m.getString(R.string.yt_api_key_label), m.getString(R.string.yt_api_key_hint), curKey,
                InputType.TYPE_CLASS_TEXT, form);
        EditText chanEt = Widgets.formField(m, m.getString(R.string.yt_add_channel), "UC... (채널 ID)", "",
                InputType.TYPE_CLASS_TEXT, form);
        Widgets.showFormSheet(m, m.getString(R.string.yt_source_settings), form, m.getString(R.string.save), () -> {
            String pu = pipedEt.getText().toString().trim();
            if (pu.isEmpty()) pu = DEFAULT_PIPED;
            MainActivity.prefs(m).edit()
                    .putString(PREF_PIPED_URL, pu)
                    .putString(PREF_API_KEY, keyEt.getText().toString().trim())
                    .apply();
            String ch = chanEt.getText().toString().trim();
            if (!ch.isEmpty()) addChannelSubscription(m, ch, ch);
            m.reloadAll();
        });
    }

    // ---- 16:9 Rounded YouTube Thumbnail View ----
    private static final class YtThumbView extends View {
        private final float radiusDp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private Bitmap bmp;
        private String id;

        YtThumbView(Context c, float radiusDp) {
            super(c);
            this.radiusDp = radiusDp;
        }

        void bind(YtVideo v) {
            this.id = v.id;
            this.bmp = THUMBS.get(v.id);
            invalidate();
            if (bmp == null) {
                loadThumb(v, b -> {
                    if (v.id.equals(id)) {
                        bmp = b;
                        invalidate();
                    }
                });
            }
        }

        @Override protected void onDraw(Canvas c) {
            rect.set(0, 0, getWidth(), getHeight());
            float r = Ui.dpf(radiusDp);
            if (bmp != null && !bmp.isRecycled()) {
                c.save();
                clip.reset();
                clip.addRoundRect(rect, r, r, Path.Direction.CW);
                c.clipPath(clip);
                float s = Math.max(rect.width() / bmp.getWidth(), rect.height() / bmp.getHeight());
                float dx = (rect.width() - bmp.getWidth() * s) / 2f;
                float dy = (rect.height() - bmp.getHeight() * s) / 2f;
                c.translate(dx, dy);
                c.scale(s, s);
                c.drawBitmap(bmp, 0, 0, paint);
                c.restore();
            } else {
                paint.setColor(Ui.placeholder());
                c.drawRoundRect(rect, r, r, paint);
                Icons.draw(c, Icons.YOUTUBE, rect.centerX(), rect.centerY(), Ui.dpf(26), 0xFFFF0033);
            }
        }
    }

    // ==================================================================
    // Full-Screen Play-UI YouTube Player Overlay
    // ==================================================================
    static void openVideo(MainActivity m, YtVideo v) {
        if (v == null || v.id.isEmpty()) return;
        if (VideoPlayer.playing) VideoPlayer.pause();
        dismissOverlay(m.root);
        YouTubeOverlay ov = new YouTubeOverlay(m, v);
        m.root.addView(ov, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    static boolean dismissOverlay(ViewGroup root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View c = root.getChildAt(i);
            if (c instanceof YouTubeOverlay) {
                ((YouTubeOverlay) c).close();
                return true;
            }
        }
        return false;
    }

    static final class YouTubeOverlay extends FrameLayout {
        private final MainActivity m;
        private final YtVideo video;
        private final WebView web;
        private final FrameLayout chrome;
        private final TextView titleView, authorView, speedBadge;
        private final Icons.IconView playBtn;
        private boolean playing = true;
        private float speed = 1.0f;
        private boolean chromeVisible = true;
        private final Ui.Spring openP = new Ui.Spring(360f, 0.84f, 0.002f);
        private boolean closing;

        YouTubeOverlay(MainActivity activity, YtVideo v) {
            super(activity);
            this.m = activity;
            this.video = v;
            setBackgroundColor(0xFF000000);
            setClickable(true);

            // 1. Official YouTube IFrame Embed WebView
            web = new WebView(m);
            web.setBackgroundColor(Color.BLACK);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            web.setWebChromeClient(new WebChromeClient());
            web.setWebViewClient(new WebViewClient());
            web.addJavascriptInterface(new JsBridge(), "PlayBridge");
            addView(web, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 2. Play Glass / G-style Chrome overlay
            chrome = new FrameLayout(m);
            addView(chrome, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            boolean glassDeck = !Ui.gStyle;
            LinearLayout topBar = new LinearLayout(m);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            if (glassDeck) {
                topBar.setBackground(glassCapsule(100));
                topBar.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(10), Ui.dp(4));
            } else {
                topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xCC000000, 0x00000000}));
                topBar.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(16));
            }

            Icons.IconView backBtn = iconBtn(Icons.BACK, vv -> close());
            topBar.addView(backBtn);

            LinearLayout titleCol = new LinearLayout(m);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            titleCol.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
            titleView = Ui.text(m, v.title, 15f, Ui.semibold, 0xFFFFFFFF);
            authorView = Ui.text(m, v.author, 12f, Ui.regular, 0xCCFFFFFF);
            titleCol.addView(titleView);
            titleCol.addView(authorView);
            topBar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            topBar.addView(iconBtn(Icons.PIP, vv -> m.enterPip()));
            topBar.addView(iconBtn(Icons.INFO, vv -> showCommentsAndInfoSheet()));
            topBar.addView(iconBtn(Icons.MORE, this::showMoreSheet));

            int topSafe = Math.max(Ui.statusInset, Ui.dp(24)) + Ui.dp(10);
            int sideSafe = Math.max(Ui.cutoutLeft, Ui.cutoutRight) + Ui.dp(14);
            LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            tlp.setMargins(sideSafe, topSafe, sideSafe, 0);
            chrome.addView(topBar, tlp);

            // Bottom floating Play control bar
            LinearLayout botBar = new LinearLayout(m);
            botBar.setOrientation(LinearLayout.HORIZONTAL);
            botBar.setGravity(Gravity.CENTER_VERTICAL);
            botBar.setBackground(glassCapsule(26));
            botBar.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));

            playBtn = iconBtn(Icons.PAUSE, vv -> togglePlay());
            botBar.addView(playBtn);
            botBar.addView(iconBtn(Icons.REWIND, vv -> seekDelta(-10)));
            botBar.addView(iconBtn(Icons.FORWARD, vv -> seekDelta(10)));

            speedBadge = Ui.text(m, "1.0x", 13f, Ui.bold, 0xFFFFFFFF);
            speedBadge.setGravity(Gravity.CENTER);
            speedBadge.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
            speedBadge.setOnClickListener(this::showSpeedSheet);
            botBar.addView(speedBadge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView commentsBtn = Ui.text(m, m.getString(R.string.yt_comments_info), 12.5f, Ui.semibold, 0xFFFFFFFF);
            commentsBtn.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
            commentsBtn.setBackground(Ui.round(0x33FFFFFF, 100));
            commentsBtn.setOnClickListener(vv -> showCommentsAndInfoSheet());
            botBar.addView(commentsBtn);

            if (isNewPipeInstalled(m)) {
                TextView npBtn = Ui.text(m, "NewPipe", 12.5f, Ui.bold, 0xFFFFFFFF);
                npBtn.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
                npBtn.setBackground(Ui.round(0xCCFF0033, 100));
                npBtn.setOnClickListener(vv -> openInNewPipe(m, v.id));
                LinearLayout.LayoutParams nplp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nplp.leftMargin = Ui.dp(8);
                botBar.addView(npBtn, nplp);
            }

            LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            blp.setMargins(sideSafe, 0, sideSafe, Math.max(Ui.navInset, Ui.dp(12)) + Ui.dp(12));
            chrome.addView(botBar, blp);

            loadEmbedPlayer(v.id);

            // Fetch metadata & comments asynchronously
            loadCommentsAndMeta(m, v, (comments, desc) -> {
                titleView.setText(v.title);
                authorView.setText(v.author);
            });

            openP.onUpdate = () -> {
                float k = Ui.clamp(openP.value, 0f, 1f);
                setAlpha(k);
                setTranslationY((1f - k) * Ui.dpf(32));
            };
            openP.onEnd = () -> {
                if (closing) {
                    try { web.destroy(); } catch (Throwable ignored) {}
                    m.root.removeView(this);
                }
            };
            openP.snap(0f);
            openP.to(1f);
        }

        private void loadEmbedPlayer(String videoId) {
            String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
                    + "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;}"
                    + "#player{width:100%;height:100%;border:0;}</style></head><body>"
                    + "<div id='player'></div>"
                    + "<script src='https://www.youtube.com/iframe_api'></script>"
                    + "<script>"
                    + "var player;"
                    + "function onYouTubeIframeAPIReady(){"
                    + "  player=new YT.Player('player',{videoId:'" + videoId + "',"
                    + "    playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,cc_load_policy:1},"
                    + "    events:{onStateChange:function(e){if(window.PlayBridge)PlayBridge.onState(e.data);}}"
                    + "  });"
                    + "}"
                    + "function playToggle(){if(!player)return;var s=player.getPlayerState();if(s===1)player.pauseVideo();else player.playVideo();}"
                    + "function seekDelta(d){if(!player)return;player.seekTo(Math.max(0,player.getCurrentTime()+d),true);}"
                    + "function setRate(r){if(!player)return;player.setPlaybackRate(r);}"
                    + "</script></body></html>";
            web.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null);
        }

        private final class JsBridge {
            @JavascriptInterface
            public void onState(int state) {
                MAIN.post(() -> {
                    playing = (state == 1);
                    playBtn.set(playing ? Icons.PAUSE : Icons.PLAY, 0xFFFFFFFF);
                });
            }
        }

        private void togglePlay() {
            web.evaluateJavascript("playToggle();", null);
            playing = !playing;
            playBtn.set(playing ? Icons.PAUSE : Icons.PLAY, 0xFFFFFFFF);
        }

        private void seekDelta(int sec) {
            web.evaluateJavascript("seekDelta(" + sec + ");", null);
        }

        private void showSpeedSheet(View anchor) {
            float[] rates = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
            String[] labels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
            int sel = 2;
            for (int i = 0; i < rates.length; i++) if (Math.abs(rates[i] - speed) < 0.02f) sel = i;
            Widgets.pickSheet(m, anchor, m.getString(R.string.speed), labels, sel, idx -> {
                speed = rates[idx];
                speedBadge.setText(labels[idx]);
                web.evaluateJavascript("setRate(" + speed + ");", null);
            });
        }

        private void showMoreSheet(View anchor) {
            float[] o = Glass.abs(anchor);
            RectF r = new RectF(o[0], o[1], o[0] + anchor.getWidth(), o[1] + anchor.getHeight());
            List<GlassMenu.Entry> es = new ArrayList<>();
            es.add(new GlassMenu.Entry(m.getString(R.string.psa_title) + " (" + NpuVideo.summary(m) + ")",
                    Icons.PROSCALER, NpuVideo.enabled(m), () -> NpuVideo.showQuickSheet(m, r, null)));
            es.add(new GlassMenu.Entry(m.getString(R.string.bpa_title) + " (" + NpuAudio.summary(m) + ")",
                    Icons.HEADPHONES, NpuAudio.enabled(m), () -> {
                close();
                m.push(new Settings.BitPerfectAssistPage(m));
            }));
            es.add(new GlassMenu.Entry(m.getString(R.string.yt_comments_info), Icons.INFO, false, this::showCommentsAndInfoSheet));
            if (isNewPipeInstalled(m)) {
                es.add(new GlassMenu.Entry(m.getString(R.string.yt_open_newpipe), Icons.YOUTUBE, false, () -> openInNewPipe(m, video.id)));
            }
            es.add(new GlassMenu.Entry(m.getString(R.string.yt_open_external), Icons.GLOBE, false, () -> openInNewPipe(m, video.id)));
            GlassMenu.sheet(m, r, video.title, es, anchor);
        }

        private void showCommentsAndInfoSheet() {
            LinearLayout box = new LinearLayout(m);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(14));

            TextView t = Ui.text(m, video.title, 16f, Ui.bold, 0xFFFFFFFF);
            t.setSingleLine(false);
            box.addView(t, Widgets.MW);

            TextView au = Ui.text(m, video.author + (video.published.isEmpty() ? "" : " · " + video.published), 13f, Ui.medium, Ui.ACCENT);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = Ui.dp(4);
            alp.bottomMargin = Ui.dp(10);
            box.addView(au, alp);

            LinearLayout commentsList = new LinearLayout(m);
            commentsList.setOrientation(LinearLayout.VERTICAL);
            TextView loading = Ui.text(m, m.getString(R.string.yt_loading_comments), 13.5f, Ui.regular, 0xB3FFFFFF);
            commentsList.addView(loading);
            box.addView(commentsList, Widgets.MW);

            loadCommentsAndMeta(m, video, (comments, desc) -> {
                commentsList.removeAllViews();
                if (desc != null && !desc.isEmpty()) {
                    TextView dTv = Ui.text(m, desc, 13f, Ui.regular, 0xD9FFFFFF);
                    dTv.setSingleLine(false);
                    dTv.setMaxLines(6);
                    dTv.setEllipsize(TextUtils.TruncateAt.END);
                    dTv.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
                    dTv.setBackground(Ui.round(0x22FFFFFF, 14));
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    dlp.bottomMargin = Ui.dp(12);
                    commentsList.addView(dTv, dlp);
                }
                if (comments.isEmpty()) {
                    commentsList.addView(Ui.text(m, m.getString(R.string.yt_no_comments), 13.5f, Ui.regular, 0x99FFFFFF));
                    return;
                }
                TextView hdr = Ui.text(m, m.getString(R.string.yt_comments_count, comments.size()), 14f, Ui.bold, 0xFFFFFFFF);
                hdr.setPadding(0, Ui.dp(4), 0, Ui.dp(8));
                commentsList.addView(hdr);
                for (YtComment cm : comments) {
                    LinearLayout cRow = new LinearLayout(m);
                    cRow.setOrientation(LinearLayout.VERTICAL);
                    cRow.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
                    String head = cm.author + (cm.time.isEmpty() ? "" : " · " + cm.time)
                            + (cm.likes.isEmpty() || "0".equals(cm.likes) ? "" : " · ♥ " + cm.likes);
                    cRow.addView(Ui.text(m, head, 12f, Ui.semibold, 0xB3FFFFFF));
                    TextView body = Ui.text(m, cm.text, 13.5f, Ui.regular, 0xFFFFFFFF);
                    body.setSingleLine(false);
                    cRow.addView(body);
                    commentsList.addView(cRow, Widgets.MW);
                }
            });

            RectF anchor = new RectF(Ui.screenW / 2f - Ui.dpf(40), Ui.screenH - Ui.dpf(80), Ui.screenW / 2f + Ui.dpf(40), Ui.screenH - Ui.dpf(40));
            GlassMenu.customSheet(m, anchor, m.getString(R.string.yt_comments_info), box, null);
        }

        private GradientDrawable glassCapsule(int radiusDp) {
            GradientDrawable gd = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x99222630, 0x8814161D});
            gd.setCornerRadius(Ui.dpf(radiusDp));
            gd.setStroke(Math.max(1, Ui.dp(1)), 0x4DFFFFFF);
            return gd;
        }

        private Icons.IconView iconBtn(int icon, OnClickListener click) {
            Icons.IconView iv = new Icons.IconView(m, icon, 21, 0xFFFFFFFF);
            iv.setBackground(Widgets.ripple());
            iv.setOnClickListener(click);
            Ui.press(iv, 0.88f);
            iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
            return iv;
        }

        void close() {
            if (closing) return;
            closing = true;
            try { web.loadUrl("about:blank"); } catch (Throwable ignored) {}
            openP.to(0f);
        }
    }
}
