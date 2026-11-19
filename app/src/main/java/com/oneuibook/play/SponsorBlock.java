package com.oneuibook.play;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public SponsorBlock API client (sponsor.ajay.app) for skipping community-submitted
 * sponsor, intro, outro, self-promo, and interaction segments on YouTube videos.
 */
final class SponsorBlock {
    private SponsorBlock() {}

    static final class Segment {
        final long startMs, endMs;
        final String category;

        Segment(long startMs, long endMs, String category) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.category = category;
        }

        int color() {
            switch (category) {
                case "sponsor": return 0xCC00D400;
                case "intro": return 0xCC00FFFF;
                case "outro": return 0xCC0202ED;
                case "selfpromo": return 0xCCFFFF00;
                case "interaction": return 0xCCCC00FF;
                default: return 0xCCFF9800;
            }
        }

        String label() {
            switch (category) {
                case "sponsor": return "스폰서";
                case "intro": return "인트로";
                case "outro": return "아웃트로";
                case "selfpromo": return "자체 홍보";
                case "interaction": return "구독/좋아요 유도";
                default: return "구간";
            }
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, List<Segment>> CACHE = new HashMap<>();
    private static final Pattern YT_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?.*v=|shorts/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    static volatile String currentVideoId;
    static volatile List<Segment> currentSegments = Collections.emptyList();

    /** Extracts an 11-character YouTube video ID from a URL or filename if present. */
    static String extractVideoId(String uriOrTitle) {
        if (uriOrTitle == null || uriOrTitle.isEmpty()) return null;
        Matcher m = YT_ID.matcher(uriOrTitle);
        if (m.find()) return m.group(1);
        return null;
    }

    static boolean isEnabled(Context c) {
        return MainActivity.prefs(c).getBoolean("sb_enabled", true);
    }

    static void clear() {
        currentVideoId = null;
        currentSegments = Collections.emptyList();
    }

    static void loadFor(Context c, VideoLibrary.Video v, Runnable onLoaded) {
        clear();
        if (v == null || !isEnabled(c) || "robolectric".equals(android.os.Build.FINGERPRINT)) return;
        String vid = extractVideoId(v.uri);
        if (vid == null) vid = extractVideoId(v.title);
        if (vid == null) return;
        currentVideoId = vid;
        List<Segment> cached = CACHE.get(vid);
        if (cached != null) {
            currentSegments = cached;
            if (onLoaded != null) onLoaded.run();
            return;
        }
        SharedPreferences p = MainActivity.prefs(c);
        List<String> cats = new ArrayList<>();
        if (p.getBoolean("sb_sponsor", true)) cats.add("sponsor");
        if (p.getBoolean("sb_intro", true)) cats.add("intro");
        if (p.getBoolean("sb_outro", true)) cats.add("outro");
        if (p.getBoolean("sb_selfpromo", true)) cats.add("selfpromo");
        if (p.getBoolean("sb_interaction", false)) cats.add("interaction");
        if (cats.isEmpty()) return;

        final String targetId = vid;
        new Thread(() -> {
            List<Segment> out = new ArrayList<>();
            try {
                StringBuilder q = new StringBuilder("[");
                for (int i = 0; i < cats.size(); i++) {
                    if (i > 0) q.append(',');
                    q.append('"').append(cats.get(i)).append('"');
                }
                q.append(']');
                String url = "https://sponsor.ajay.app/api/skipSegments?videoID="
                        + URLEncoder.encode(targetId, "UTF-8")
                        + "&categories=" + URLEncoder.encode(q.toString(), "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "Play-Android/" + MainActivity.VERSION);
                if (conn.getResponseCode() == 200) {
                    JSONArray arr = new JSONArray(readAll(conn.getInputStream()));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        JSONArray seg = o.optJSONArray("segment");
                        if (seg == null || seg.length() < 2) continue;
                        long s = Math.round(seg.optDouble(0, 0) * 1000.0);
                        long e = Math.round(seg.optDouble(1, 0) * 1000.0);
                        if (e > s + 500) out.add(new Segment(s, e, o.optString("category", "sponsor")));
                    }
                }
            } catch (Exception ignored) {}
            MAIN.post(() -> {
                CACHE.put(targetId, out);
                if (targetId.equals(currentVideoId)) {
                    currentSegments = out;
                    if (onLoaded != null) onLoaded.run();
                }
            });
        }, "play-sponsorblock").start();
    }

    static Segment segmentAt(long posMs) {
        for (Segment s : currentSegments) {
            if (posMs >= s.startMs && posMs < s.endMs - 400) return s;
        }
        return null;
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
