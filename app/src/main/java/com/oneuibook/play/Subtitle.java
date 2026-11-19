package com.oneuibook.play;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-featured subtitle parser & renderer:
 * Supports SRT, WebVTT (.vtt), ASS/SSA (.ass/.ssa), SAMI (.smi),
 * automatic encoding detection (UTF-8 / UTF-16 / EUC-KR / CP949),
 * sync offset, dual subtitles, and customizable typography/backdrop.
 */
final class Subtitle {
    private Subtitle() {}

    static final class Cue {
        final long startMs;
        final long endMs;
        final String text;

        Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    static final class Track {
        String name;
        String path;
        final List<Cue> cues;

        Track(String name, String path, List<Cue> cues) {
            this.name = name;
            this.path = path;
            this.cues = cues;
        }

        /** Binary search for all active cues at playback time (with sync offset applied). */
        String textAt(long posMs, long offsetMs) {
            if (cues.isEmpty()) return null;
            long t = posMs - offsetMs;
            int lo = 0, hi = cues.size() - 1, idx = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cues.get(mid).startMs <= t) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (idx < 0) return null;
            // Collect overlapping cues around idx
            StringBuilder sb = null;
            int start = Math.max(0, idx - 4);
            for (int i = start; i <= idx; i++) {
                Cue c = cues.get(i);
                if (t >= c.startMs && t <= c.endMs && c.text.length() > 0) {
                    if (sb == null) sb = new StringBuilder(c.text);
                    else sb.append('\n').append(c.text);
                }
            }
            return sb == null ? null : sb.toString();
        }
    }

    // ---- Style preferences ----
    static float textSizeSp = 20f;
    static int textColor = 0xFFFFFFFF;
    static float bgAlpha = 0.55f;
    static float bottomMarginDp = 36f;
    static boolean bold = true;
    static boolean outline = true;

    static void loadStyle(SharedPreferences p) {
        textSizeSp = Ui.clamp(p.getFloat("sub_size", 20f), 12f, 40f);
        textColor = p.getInt("sub_color", 0xFFFFFFFF);
        bgAlpha = Ui.clamp(p.getFloat("sub_bg_alpha", 0.55f), 0f, 1f);
        bottomMarginDp = Ui.clamp(p.getFloat("sub_bottom", 36f), 8f, 160f);
        bold = p.getBoolean("sub_bold", true);
        outline = p.getBoolean("sub_outline", true);
    }

    static void saveStyle(SharedPreferences p) {
        p.edit().putFloat("sub_size", textSizeSp)
                .putInt("sub_color", textColor)
                .putFloat("sub_bg_alpha", bgAlpha)
                .putFloat("sub_bottom", bottomMarginDp)
                .putBoolean("sub_bold", bold)
                .putBoolean("sub_outline", outline)
                .apply();
    }

    // ---- Loading ----
    static Track loadFile(File f) {
        if (f == null || !f.exists() || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = readAll(in);
            return parse(f.getName(), f.getAbsolutePath(), decodeBytes(data));
        } catch (Throwable t) {
            return null;
        }
    }

    static Track loadUri(Context c, Uri uri, String displayName) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            byte[] data = readAll(in);
            String name = displayName != null && !displayName.isEmpty() ? displayName : uri.getLastPathSegment();
            return parse(name == null ? "subtitle.srt" : name, uri.toString(), decodeBytes(data));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Finds a matching sidecar subtitle (.srt/.vtt/.ass/.ssa/.smi) next to a local video file. */
    static File findSidecar(String videoPath) {
        if (videoPath == null || videoPath.isEmpty()) return null;
        File vf = new File(videoPath);
        File dir = vf.getParentFile();
        if (dir == null || !dir.isDirectory()) return null;
        String name = vf.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String[] exts = {".srt", ".vtt", ".ass", ".ssa", ".smi", ".ko.srt", ".en.srt", ".ko.ass"};
        for (String ext : exts) {
            File cand = new File(dir, base + ext);
            if (cand.exists() && cand.isFile()) return cand;
        }
        return null;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Auto-detect UTF-8 BOM, UTF-16 LE/BE, valid UTF-8, or Korean CP949/EUC-KR fallback. */
    static String decodeBytes(byte[] b) {
        if (b == null || b.length == 0) return "";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        if (isValidUtf8(b)) return new String(b, StandardCharsets.UTF_8);
        try {
            return new String(b, Charset.forName("MS949"));
        } catch (Throwable ignored) {
            try {
                return new String(b, Charset.forName("EUC-KR"));
            } catch (Throwable ignored2) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }
    }

    static boolean isValidUtf8(byte[] b) {
        int i = 0, n = b.length;
        while (i < n) {
            int c = b[i] & 0xFF;
            if (c <= 0x7F) { i++; continue; }
            int need;
            if ((c & 0xE0) == 0xC0) need = 1;
            else if ((c & 0xF0) == 0xE0) need = 2;
            else if ((c & 0xF8) == 0xF0) need = 3;
            else return false;
            if (i + need >= n) return false;
            for (int k = 1; k <= need; k++) {
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            i += need + 1;
        }
        return true;
    }

    // ---- Parsers ----
    static Track parse(String name, String path, String raw) {
        if (raw == null) return new Track(name, path, new ArrayList<>());
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        String trim = raw.trim();
        List<Cue> cues;
        if (lower.endsWith(".ass") || lower.endsWith(".ssa") || trim.contains("[Events]")) {
            cues = parseAss(raw);
        } else if (lower.endsWith(".smi") || trim.toLowerCase(Locale.US).contains("<sami")) {
            cues = parseSmi(raw);
        } else {
            cues = parseSrtOrVtt(raw);
        }
        Collections.sort(cues, Comparator.comparingLong(c -> c.startMs));
        return new Track(name, path, cues);
    }

    private static final Pattern TIME_LINE = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");

    static List<Cue> parseSrtOrVtt(String raw) {
        List<Cue> out = new ArrayList<>();
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int i = 0, n = lines.length;
        while (i < n) {
            Matcher m = TIME_LINE.matcher(lines[i]);
            if (!m.find()) { i++; continue; }
            long s = toMs(m.group(1), m.group(2), m.group(3), m.group(4));
            long e = toMs(m.group(5), m.group(6), m.group(7), m.group(8));
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < n && !lines[i].trim().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
                i++;
            }
            String clean = cleanHtml(sb.toString());
            if (!clean.isEmpty() && e > s) out.add(new Cue(s, e, clean));
        }
        return out;
    }

    static List<Cue> parseAss(String raw) {
        List<Cue> out = new ArrayList<>();
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int startIdx = 1, endIdx = 2, textIdx = 9;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("Format:")) {
                String[] cols = t.substring(7).split(",");
                for (int k = 0; k < cols.length; k++) {
                    String col = cols[k].trim().toLowerCase(Locale.US);
                    if ("start".equals(col)) startIdx = k;
                    else if ("end".equals(col)) endIdx = k;
                    else if ("text".equals(col)) textIdx = k;
                }
            } else if (t.startsWith("Dialogue:")) {
                String body = t.substring(9).trim();
                String[] parts = splitLimit(body, ',', textIdx + 1);
                if (parts.length <= textIdx) continue;
                long s = parseAssTime(parts[startIdx].trim());
                long e = parseAssTime(parts[endIdx].trim());
                String txt = parts[textIdx]
                        .replaceAll("\\{[^}]*\\}", "")
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                        .trim();
                if (!txt.isEmpty() && e > s) out.add(new Cue(s, e, txt));
            }
        }
        return out;
    }

    private static final Pattern SMI_SYNC = Pattern.compile("<SYNC\\s+Start\\s*=\\s*(\\d+)[^>]*>(.*?)(?=<SYNC\\s+Start|</BODY|</SAMI|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static List<Cue> parseSmi(String raw) {
        List<Cue> out = new ArrayList<>();
        Matcher m = SMI_SYNC.matcher(raw);
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (m.find()) {
            long start = Long.parseLong(m.group(1));
            String body = m.group(2)
                    .replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("<[^>]+>", "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .trim();
            times.add(new long[]{start});
            texts.add(body);
        }
        for (int i = 0; i < times.size(); i++) {
            String txt = texts.get(i);
            if (txt.isEmpty()) continue;
            long s = times.get(i)[0];
            long e = (i + 1 < times.size()) ? times.get(i + 1)[0] : s + 4000L;
            if (e > s) out.add(new Cue(s, e, txt));
        }
        return out;
    }

    private static long parseAssTime(String s) {
        String[] p = s.split("[:.]");
        if (p.length < 4) return 0;
        long h = parseLong(p[0]), m = parseLong(p[1]), sec = parseLong(p[2]);
        long cs = parseLong(p[3]);
        long ms = p[3].length() == 2 ? cs * 10 : (p[3].length() == 1 ? cs * 100 : cs);
        return ((h * 3600) + (m * 60) + sec) * 1000L + ms;
    }

    private static long toMs(String h, String m, String s, String ms) {
        long hh = h == null ? 0 : parseLong(h);
        long mm = parseLong(m);
        long ss = parseLong(s);
        long f = parseLong(ms);
        if (ms.length() == 1) f *= 100;
        else if (ms.length() == 2) f *= 10;
        return ((hh * 3600) + (mm * 60) + ss) * 1000L + f;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0; }
    }

    private static String[] splitLimit(String s, char sep, int maxParts) {
        List<String> list = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length() && list.size() < maxParts - 1; i++) {
            if (s.charAt(i) == sep) {
                list.add(s.substring(start, i));
                start = i + 1;
            }
        }
        list.add(s.substring(start));
        return list.toArray(new String[0]);
    }

    static String cleanHtml(String s) {
        return s.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\{\\\\[^}]*\\}", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .trim();
    }

    static String formatSrtTime(long ms) {
        long m = Math.max(0, ms);
        long h = m / 3600000; m %= 3600000;
        long min = m / 60000; m %= 60000;
        long sec = m / 1000; long rem = m % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, min, sec, rem);
    }

    /** Overlay view drawing primary (bottom) and optional secondary (top/above) subtitles. */
    static final class SubtitleView extends View {
        private String primaryText, secondaryText;
        float extraBottomPx;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint strokePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bgRect = new RectF();

        SubtitleView(Context c) {
            super(c);
            setClickable(false);
            setFocusable(false);
        }

        void setTexts(String primary, String secondary) {
            if (eq(primaryText, primary) && eq(secondaryText, secondary)) return;
            primaryText = primary;
            secondaryText = secondary;
            invalidate();
        }

        private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float bottom = h - Ui.dpf(bottomMarginDp) - extraBottomPx;
            if (primaryText != null && !primaryText.isEmpty()) {
                bottom = drawBlock(canvas, primaryText, w, bottom, textSizeSp, textColor);
                bottom -= Ui.dpf(8);
            }
            if (secondaryText != null && !secondaryText.isEmpty()) {
                drawBlock(canvas, secondaryText, w, bottom, Math.max(12f, textSizeSp * 0.85f), 0xFFE5ECFF);
            }
        }

        private float drawBlock(Canvas canvas, String text, int w, float bottomY, float sizeSp, int color) {
            Typeface tf = bold ? Ui.semibold : Ui.regular;
            textPaint.setTypeface(tf);
            textPaint.setTextSize(Ui.sp(sizeSp));
            textPaint.setColor(color);
            textPaint.setShadowLayer(outline ? Ui.dpf(3f) : 0f, 0f, Ui.dpf(1.2f), 0xDD000000);

            int maxW = Math.max(Ui.dp(100), w - Ui.dp(48));
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(Ui.dpf(2), 1.05f)
                    .build();

            float maxLineW = 0f;
            for (int i = 0; i < layout.getLineCount(); i++) {
                maxLineW = Math.max(maxLineW, layout.getLineWidth(i));
            }
            float padX = Ui.dpf(12), padY = Ui.dpf(6);
            float blockH = layout.getHeight();
            float topY = bottomY - blockH - padY;

            if (bgAlpha > 0.02f) {
                bgPaint.setColor(Ui.alpha(0xFF000000, bgAlpha));
                float cx = w / 2f;
                bgRect.set(cx - maxLineW / 2f - padX, topY - padY, cx + maxLineW / 2f + padX, bottomY + padY * 0.5f);
                canvas.drawRoundRect(bgRect, Ui.dpf(10), Ui.dpf(10), bgPaint);
            }

            canvas.save();
            canvas.translate((w - maxW) / 2f, topY);
            if (outline) {
                strokePaint.set(textPaint);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(Ui.dpf(2.6f));
                strokePaint.setColor(0xD9000000);
                strokePaint.clearShadowLayer();
                StaticLayout strokeLayout = StaticLayout.Builder.obtain(text, 0, text.length(), strokePaint, maxW)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(Ui.dpf(2), 1.05f)
                        .build();
                strokeLayout.draw(canvas);
            }
            layout.draw(canvas);
            canvas.restore();
            return topY - padY;
        }
    }
}
