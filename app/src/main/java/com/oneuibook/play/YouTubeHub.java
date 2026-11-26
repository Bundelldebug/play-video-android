package com.oneuibook.play;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Play-UI YouTube Hub & Native Cinema Player (v9.5.7):
 * - Direct YouTube InnerTube API (/browse, /search, /player) with Google & microG session support
 *   (SAPISIDHASH auth headers + microG / GmsCore detection & account bridge).
 * - Native TextureView + MediaPlayer stream playback (IOS / ANDROID_VR / TVHTML5_SIMPLY_EMBEDDED_PLAYER)
 *   with scrub seekbar, PlaybackParams speed, resolution badge, and 1-tap mobile WebView fallback
 *   sharing Google/microG cookies (completely fixing broken youtube-nocookie.com IFrame Error 150/152/153).
 */
final class YouTubeHub {
    private YouTubeHub() {}

    static final String PREF_API_KEY = "yt_api_key";
    static final String PREF_PIPED_URL = "yt_piped_url";
    static final String PREF_SUB_CHANNELS = "yt_sub_channels";
    static final String PREF_GOOGLE_COOKIE = "yt_google_cookie";
    static final String PREF_GOOGLE_ACCOUNT = "yt_google_account";
    static final String DEFAULT_PIPED = "https://pipedapi.kavin.rocks";
    static final String NEWPIPE_PKG = "org.schabi.newpipe";

    private static final String[][] MICROG_PKGS = {
            {"app.revanced.android.gms", "ReVanced microG (GmsCore)"},
            {"org.microg.gms", "microG Services"},
            {"com.mgoogle.android.gms", "Vanced microG"},
            {"com.google.android.gms", "Google Play Services"}
    };

    private static final String YT_ORIGIN = "https://www.youtube.com";
    private static final String YT_API_BASE = "https://www.youtube.com/youtubei/v1/";
    private static final String WEB_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)";
    private static final String VR_UA = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4);
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

        String thumbUrl() { return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"; }
        String watchUrl() { return "https://www.youtube.com/watch?v=" + id; }
    }

    static final class YtComment {
        final String author, text, time, likes;
        YtComment(String author, String text, String time, String likes) {
            this.author = author == null ? "" : author;
            this.text = text == null ? "" : text;
            this.time = time == null ? "" : time;
            this.likes = likes == null ? "" : likes;
        }
    }

    static final class ResolvedStream {
        String url;
        String audioUrl; // non-null when video & audio are separate adaptiveFormats streams
        String qualityLabel;
        String clientUsed;
        String title;
        String author;
        String description;
        long durationSec;
        boolean hls;
        boolean dualTrack;
        String error;
    }

    static final class MicroGInfo {
        final String pkg;
        final String label;
        final boolean isMicroG;
        MicroGInfo(String pkg, String label, boolean isMicroG) {
            this.pkg = pkg;
            this.label = label;
            this.isMicroG = isMicroG;
        }
    }

    /** Detects installed microG (ReVanced GmsCore / org.microg.gms / Vanced) or Google Play Services. */
    static MicroGInfo detectMicroGProvider(Context c) {
        if (c == null) return null;
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < MICROG_PKGS.length; i++) {
            try {
                pm.getPackageInfo(MICROG_PKGS[i][0], 0);
                return new MicroGInfo(MICROG_PKGS[i][0], MICROG_PKGS[i][1], i < 3);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Safely reads Google / microG accounts registered on the device if accessible. */
    static List<String> detectDeviceGoogleAccounts(Context c) {
        List<String> out = new ArrayList<>();
        try {
            AccountManager am = AccountManager.get(c);
            if (am != null) {
                for (Account acc : am.getAccountsByType("com.google")) {
                    if (acc.name != null && !acc.name.isEmpty()) out.add(acc.name);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static void openMicroGSettings(Context c) {
        MicroGInfo info = detectMicroGProvider(c);
        if (info == null) {
            Toast.makeText(c, "설치된 microG 앱이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(info.pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(launch);
                return;
            }
            Intent settings = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + info.pkg));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(settings);
        } catch (Throwable t) {
            Toast.makeText(c, "microG 설정을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    static String getGoogleCookie(Context c) {
        String saved = MainActivity.prefs(c).getString(PREF_GOOGLE_COOKIE, "").trim();
        if (!saved.isEmpty()) return saved;
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                String live = cm.getCookie("https://www.youtube.com");
                if (live != null && (live.contains("SAPISID=") || live.contains("__Secure-3PAPISID="))) {
                    return live;
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    static boolean isGoogleSignedIn(Context c) {
        String ck = getGoogleCookie(c);
        return ck.contains("SAPISID=") || ck.contains("__Secure-3PAPISID=") || ck.contains("SID=");
    }

    static String getSignedInLabel(Context c) {
        String saved = MainActivity.prefs(c).getString(PREF_GOOGLE_ACCOUNT, "").trim();
        if (!saved.isEmpty()) return saved;
        List<String> dev = detectDeviceGoogleAccounts(c);
        if (!dev.isEmpty()) return dev.get(0);
        return isGoogleSignedIn(c) ? c.getString(R.string.yt_google_signed_in) : "";
    }

    static void signOutGoogle(Context c) {
        MainActivity.prefs(c).edit()
                .remove(PREF_GOOGLE_COOKIE)
                .remove(PREF_GOOGLE_ACCOUNT)
                .apply();
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.removeAllCookies(null);
                cm.flush();
            }
        } catch (Throwable ignored) {}
    }

    /** Generates standard YouTube SAPISIDHASH Authorization header from Google session cookies. */
    static String buildSapisidHash(String cookieHeader, String origin) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;
        String sapisid = extractCookieValue(cookieHeader, "__Secure-3PAPISID");
        if (sapisid == null) sapisid = extractCookieValue(cookieHeader, "SAPISID");
        if (sapisid == null || sapisid.isEmpty()) return null;
        try {
            long ts = System.currentTimeMillis() / 1000L;
            String raw = ts + " " + sapisid + " " + (origin == null ? YT_ORIGIN : origin);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : dig) hex.append(String.format(Locale.US, "%02x", b & 0xff));
            return "SAPISIDHASH " + ts + "_" + hex;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String t = part.trim();
            int eq = t.indexOf('=');
            if (eq > 0 && t.substring(0, eq).trim().equals(name)) {
                return t.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** Opens Google / microG compatible WebView sign-in dialog capturing YouTube session cookies. */
    static void showGoogleLoginDialog(MainActivity m, Runnable onDone) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);

        MicroGInfo mg = detectMicroGProvider(m);
        List<String> devAccs = detectDeviceGoogleAccounts(m);
        StringBuilder hint = new StringBuilder();
        if (mg != null && mg.isMicroG) {
            hint.append(m.getString(R.string.yt_microg_detected, mg.label));
            if (!devAccs.isEmpty()) hint.append(" · ").append(devAccs.get(0));
            hint.append("\n아래에서 Google 계정에 로그인하면 YouTube 맞춤 피드 및 스트림 세션이 연동됩니다.");
        } else {
            hint.append("Google 계정에 로그인하면 YouTube 맞춤 피드와 연령·세션 제한 영상을 재생할 수 있습니다.");
        }

        TextView banner = Ui.text(m, hint.toString(), 12.5f, Ui.medium, Ui.text2());
        banner.setSingleLine(false);
        banner.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(8));
        box.addView(banner, Widgets.MW);

        WebView wv = new WebView(m);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUserAgentString(WEB_UA);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        final AlertDialog[] dlgRef = new AlertDialog[1];
        Runnable captureSession = () -> {
            try {
                cm.flush();
                String ckYt = cm.getCookie("https://www.youtube.com");
                String ckMob = cm.getCookie("https://m.youtube.com");
                String merged = (ckYt != null ? ckYt : "") + (ckMob != null && !ckMob.equals(ckYt) ? "; " + ckMob : "");
                if (merged.contains("SAPISID=") || merged.contains("__Secure-3PAPISID=") || merged.contains("SID=")) {
                    String label = !devAccs.isEmpty() ? devAccs.get(0) : m.getString(R.string.yt_google_signed_in);
                    MainActivity.prefs(m).edit()
                            .putString(PREF_GOOGLE_COOKIE, merged.trim())
                            .putString(PREF_GOOGLE_ACCOUNT, label)
                            .apply();
                    Toast.makeText(m, m.getString(R.string.yt_google_signed_in), Toast.LENGTH_SHORT).show();
                    if (dlgRef[0] != null && dlgRef[0].isShowing()) dlgRef[0].dismiss();
                    if (onDone != null) onDone.run();
                }
            } catch (Throwable ignored) {}
        };

        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && (url.contains("youtube.com") && !url.contains("accounts.google.com"))) {
                    captureSession.run();
                }
            }
        });
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.setOnTouchListener((v, ev) -> {
            if (!v.hasFocus()) v.requestFocus();
            return false;
        });
        wv.setWebChromeClient(new WebChromeClient());
        wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F");

        int h = Math.max(Ui.dp(380), (int) (Ui.screenH * 0.62f));
        box.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        AlertDialog.Builder b = new AlertDialog.Builder(m,
                Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(m.getString(R.string.yt_account_title))
                .setView(box)
                .setPositiveButton("로그인 완료 적용", (d, w) -> captureSession.run())
                .setNegativeButton(m.getString(R.string.cancel), null);
        if (mg != null && mg.isMicroG) {
            b.setNeutralButton(m.getString(R.string.yt_microg_open), (d, w) -> openMicroGSettings(m));
        }
        dlgRef[0] = b.show();
        android.view.Window dw = dlgRef[0].getWindow();
        if (dw != null) {
            dw.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dw.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        wv.requestFocus(View.FOCUS_DOWN);
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

    static boolean isYouTubeUrl(String text) { return extractVideoId(text) != null; }

    static boolean isNewPipeInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(NEWPIPE_PKG, 0);
            return true;
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

    // ---- Curated default channels for reliable offline/RSS fallback ----
    private static final String[][] CURATED_CHANNELS = {
            {"UCBJycsmduvYEL83R_U4JriQ", "Marques Brownlee (MKBHD)", "tech"},
            {"UCXuqSBlHAE6Xw-yeJA0Tunw", "Linus Tech Tips", "tech"},
            {"UCsXVk37bltHxD1rDPwtNM8Q", "Kurzgesagt", "tech"},
            {"UC38IQsAvIsxxjztdMZQtwHA", "HYBE LABELS", "music"},
            {"UCEf_Bc-KVd7onSeifS3py9g", "SMTOWN", "music"},
            {"UCaO6TYtlC8U5ttz62hTrZgg", "JYP Entertainment", "music"},
            {"UC-2Y8dQb0S6DtpxNgAKoJKA", "PlayStation", "gaming"},
            {"UCGIY_O-8vW4rfX98KlMkvRg", "Nintendo", "gaming"},
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
    interface StreamCb { void onResolved(ResolvedStream stream); }

    // ---- Direct YouTube InnerTube HTTP client ----
    private static JSONObject innertubePost(Context c, String endpoint, JSONObject body,
                                            String clientNameId, String clientVersion, String ua, boolean attachAuth) {
        return innertubePost(c, endpoint, body, clientNameId, clientVersion, ua, attachAuth, true);
    }

    private static JSONObject innertubePost(Context c, String endpoint, JSONObject body,
                                            String clientNameId, String clientVersion, String ua,
                                            boolean attachAuth, boolean sendWebOrigin) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(YT_API_BASE + endpoint + "?prettyPrint=false").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(7500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            if (sendWebOrigin) {
                conn.setRequestProperty("Origin", YT_ORIGIN);
                conn.setRequestProperty("Referer", YT_ORIGIN + "/");
            }
            conn.setRequestProperty("X-YouTube-Client-Name", clientNameId);
            conn.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            if (ua != null) conn.setRequestProperty("User-Agent", ua);

            if (attachAuth) {
                String cookie = getGoogleCookie(c);
                if (!cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                    String auth = buildSapisidHash(cookie, YT_ORIGIN);
                    if (auth != null) conn.setRequestProperty("Authorization", auth);
                    conn.setRequestProperty("X-Origin", YT_ORIGIN);
                }
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject webContext() throws Exception {
        JSONObject client = new JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", "2.20250301.00.00")
                .put("hl", "ko")
                .put("gl", "KR");
        return new JSONObject().put("client", client);
    }

    /** Recursively extracts videoRenderer / compactVideoRenderer items from any InnerTube browse/search JSON. */
    private static void collectRenderers(Object node, List<YtVideo> out, Set<String> seen, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject vr = o.optJSONObject("videoRenderer");
            if (vr == null) vr = o.optJSONObject("compactVideoRenderer");
            if (vr == null) vr = o.optJSONObject("gridVideoRenderer");
            if (vr != null) {
                String vid = vr.optString("videoId");
                if (!vid.isEmpty() && !seen.contains(vid)) {
                    seen.add(vid);
                    String title = readYtText(vr.optJSONObject("title"));
                    String author = readYtText(vr.optJSONObject("ownerText"));
                    if (author.isEmpty()) author = readYtText(vr.optJSONObject("longBylineText"));
                    if (author.isEmpty()) author = readYtText(vr.optJSONObject("shortBylineText"));
                    YtVideo v = new YtVideo(vid, title, author);
                    v.views = readYtText(vr.optJSONObject("shortViewCountText"));
                    if (v.views.isEmpty()) v.views = readYtText(vr.optJSONObject("viewCountText"));
                    v.published = readYtText(vr.optJSONObject("publishedTimeText"));
                    v.durationSec = parseDurationText(readYtText(vr.optJSONObject("lengthText")));
                    out.add(v);
                }
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext() && out.size() < limit) {
                collectRenderers(o.opt(keys.next()), out, seen, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < limit; i++) {
                collectRenderers(a.opt(i), out, seen, limit);
            }
        }
    }

    private static String readYtText(JSONObject o) {
        if (o == null) return "";
        String simple = o.optString("simpleText");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = o.optJSONArray("runs");
        if (runs != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < runs.length(); i++) {
                JSONObject r = runs.optJSONObject(i);
                if (r != null) sb.append(r.optString("text"));
            }
            return sb.toString();
        }
        return "";
    }

    private static long parseDurationText(String s) {
        if (s == null || s.isEmpty() || !s.contains(":")) return 0;
        try {
            String[] p = s.trim().split(":");
            long sec = 0;
            for (String part : p) sec = sec * 60L + Long.parseLong(part.replaceAll("\\D", ""));
            return sec;
        } catch (Throwable ignored) { return 0; }
    }

    /** Resolves a direct playable progressive MP4 or HLS stream URL via YouTube InnerTube /player clients. */
    static void resolveStream(Context c, String videoId, StreamCb cb) {
        EXEC.execute(() -> {
            ResolvedStream best = resolveStreamSync(c, videoId);
            MAIN.post(() -> cb.onResolved(best));
        });
    }

    private static ResolvedStream resolveStreamSync(Context c, String videoId) {
        ResolvedStream result = new ResolvedStream();
        String[][] clients = {
                {"IOS", "5", "20.10.4", IOS_UA},
                {"ANDROID_VR", "28", "1.62.27", VR_UA},
                {"TVHTML5_SIMPLY_EMBEDDED_PLAYER", "85", "2.0", WEB_UA}
        };
        boolean authed = isGoogleSignedIn(c);
        for (String[] cl : clients) {
            try {
                JSONObject client = new JSONObject()
                        .put("clientName", cl[0])
                        .put("clientVersion", cl[2])
                        .put("hl", "ko")
                        .put("gl", "KR");
                if ("IOS".equals(cl[0])) {
                    client.put("deviceMake", "Apple")
                            .put("deviceModel", "iPhone16,2")
                            .put("osName", "iPhone")
                            .put("osVersion", "18.3.1.22D72");
                } else if ("ANDROID_VR".equals(cl[0])) {
                    client.put("androidSdkVersion", 32)
                            .put("osName", "Android")
                            .put("osVersion", "12");
                }
                JSONObject ctx = new JSONObject().put("client", client);
                if ("TVHTML5_SIMPLY_EMBEDDED_PLAYER".equals(cl[0])) {
                    ctx.put("thirdParty", new JSONObject().put("embedUrl", "https://www.youtube.com/"));
                }
                JSONObject body = new JSONObject()
                        .put("videoId", videoId)
                        .put("contentCheckOk", true)
                        .put("racyCheckOk", true)
                        .put("context", ctx);

                JSONObject resp = innertubePost(c, "player", body, cl[1], cl[2], cl[3],
                        authed && !"IOS".equals(cl[0]), "TVHTML5_SIMPLY_EMBEDDED_PLAYER".equals(cl[0]));
                if (resp == null) continue;

                JSONObject vd = resp.optJSONObject("videoDetails");
                if (vd != null) {
                    if (result.title == null || result.title.isEmpty()) result.title = vd.optString("title");
                    if (result.author == null || result.author.isEmpty()) result.author = vd.optString("author");
                    if (result.description == null || result.description.isEmpty()) result.description = vd.optString("shortDescription");
                    try { result.durationSec = Long.parseLong(vd.optString("lengthSeconds", "0")); } catch (Throwable ignored) {}
                }

                JSONObject sd = resp.optJSONObject("streamingData");
                if (sd == null) continue;

                // 1) HLS manifest URL (IOS client returns adaptive HD video+audio HLS natively supported by Android MediaPlayer!)
                String hls = sd.optString("hlsManifestUrl");
                if (!hls.isEmpty()) {
                    result.url = hls;
                    result.qualityLabel = "HLS HD · " + cl[0];
                    result.clientUsed = cl[0];
                    result.hls = true;
                    result.dualTrack = false;
                    return result;
                }

                // 2) Progressive muxed MP4 formats (single URL audio+video)
                JSONArray formats = sd.optJSONArray("formats");
                String chosenUrl = null;
                String chosenQ = null;
                int bestH = -1;
                if (formats != null) {
                    for (int i = 0; i < formats.length(); i++) {
                        JSONObject f = formats.optJSONObject(i);
                        if (f == null) continue;
                        String u = f.optString("url");
                        if (u.isEmpty()) continue;
                        String mime = f.optString("mimeType").toLowerCase(Locale.US);
                        if (!mime.contains("video/mp4") && !mime.contains("video/webm")) continue;
                        int h = f.optInt("height", 360);
                        int score = h + (mime.contains("video/mp4") ? 2000 : 0);
                        if (score > bestH) {
                            bestH = score;
                            chosenUrl = u;
                            chosenQ = f.optString("qualityLabel", h + "p");
                        }
                    }
                }
                if (chosenUrl != null) {
                    result.url = chosenUrl;
                    result.qualityLabel = chosenQ + " · " + cl[0];
                    result.clientUsed = cl[0];
                    result.hls = false;
                    result.dualTrack = false;
                    return result;
                }

                // 3) Adaptive dual-track streams (separate H.264 MP4 video + AAC M4A audio — verified working on IOS!)
                JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
                if (adaptive != null) {
                    String bestVidUrl = null, bestVidQ = null;
                    int bestVidScore = -1;
                    String bestAudUrl = null;
                    int bestAudBr = -1;
                    for (int i = 0; i < adaptive.length(); i++) {
                        JSONObject f = adaptive.optJSONObject(i);
                        if (f == null) continue;
                        String u = f.optString("url");
                        if (u.isEmpty()) continue;
                        String mime = f.optString("mimeType").toLowerCase(Locale.US);
                        if (mime.startsWith("video/mp4") && mime.contains("avc1")) {
                            int h = f.optInt("height", 360);
                            int capped = h <= 1080 ? h : (1080 - (h - 1080) / 4);
                            if (capped > bestVidScore) {
                                bestVidScore = capped;
                                bestVidUrl = u;
                                bestVidQ = f.optString("qualityLabel", h + "p");
                            }
                        } else if (mime.startsWith("audio/mp4")) {
                            int br = f.optInt("bitrate", 0);
                            if (br > bestAudBr) {
                                bestAudBr = br;
                                bestAudUrl = u;
                            }
                        }
                    }
                    if (bestVidUrl != null && bestAudUrl != null) {
                        result.url = bestVidUrl;
                        result.audioUrl = bestAudUrl;
                        result.qualityLabel = bestVidQ + " HD · " + cl[0];
                        result.clientUsed = cl[0];
                        result.hls = false;
                        result.dualTrack = true;
                        return result;
                    }
                }
            } catch (Throwable ignored) {}
        }
        result.error = "Direct stream unavailable";
        return result;
    }

    static void loadCategory(Context c, int categoryIdx, ListCb cb) {
        String[] cats = {"all", "music", "gaming", "film", "tech"};
        String[] queries = {"", "인기 뮤직비디오 2026", "게임 최신 트레일러", "영화 공식 예고편", "최신 스마트폰 테크 리뷰"};
        String filter = categoryIdx >= 0 && categoryIdx < cats.length ? cats[categoryIdx] : "all";
        String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        String apiKey = MainActivity.prefs(c).getString(PREF_API_KEY, "").trim();

        EXEC.execute(() -> {
            List<YtVideo> out = new ArrayList<>();
            String src = "YouTube InnerTube";
            Set<String> seen = new HashSet<>();

            // 1) Direct YouTube InnerTube browse / search (authenticated if Google/microG signed in!)
            try {
                if (categoryIdx == 0) {
                    String browseId = isGoogleSignedIn(c) ? "FEwhat_to_watch" : "FEtrending";
                    JSONObject body = new JSONObject()
                            .put("browseId", browseId)
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "browse", body, "1", "2.20250301.00.00", WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 20);
                    if (!out.isEmpty()) {
                        src = isGoogleSignedIn(c) ? "YouTube 맞춤 피드 (Google)" : "YouTube 인기 급상승";
                    }
                } else {
                    JSONObject body = new JSONObject()
                            .put("query", queries[categoryIdx])
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "search", body, "1", "2.20250301.00.00", WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 20);
                }
            } catch (Throwable ignored) {}

            // 2) YouTube Data API v3 fallback if configured
            if (out.isEmpty() && !apiKey.isEmpty() && categoryIdx == 0) {
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

            // 3) Piped fallback
            if (out.isEmpty() && categoryIdx == 0 && !pipedBase.isEmpty()) {
                try {
                    String base = pipedBase.endsWith("/") ? pipedBase.substring(0, pipedBase.length() - 1) : pipedBase;
                    String json = httpGet(base + "/trending?region=KR", 4000);
                    if (json != null && json.trim().startsWith("[")) {
                        JSONArray arr = new JSONArray(json);
                        for (int i = 0; i < Math.min(20, arr.length()); i++) {
                            JSONObject o = arr.getJSONObject(i);
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
                        if (!out.isEmpty()) src = "Piped API";
                    }
                } catch (Throwable ignored) {}
            }

            // 4) Official YouTube public Atom/RSS feeds fallback
            if (out.isEmpty()) {
                List<String[]> chans = subscribedChannels(c);
                int fetched = 0;
                for (String[] ch : chans) {
                    if (!"all".equals(filter) && !"custom".equals(ch[2]) && !filter.equals(ch[2])) continue;
                    List<YtVideo> feed = fetchChannelRss(ch[0]);
                    for (int i = 0; i < Math.min(4, feed.size()); i++) out.add(feed.get(i));
                    if (++fetched >= 5) break;
                }
                src = "YouTube RSS";
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
        final String directId = extractVideoId(q);
        final String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        final String apiKey = MainActivity.prefs(c).getString(PREF_API_KEY, "").trim();

        EXEC.execute(() -> {
            List<YtVideo> out = new ArrayList<>();
            String src = "YouTube 검색";
            if (directId != null) {
                out.add(fetchOEmbed(directId));
                MAIN.post(() -> cb.onResult(out, "YouTube 직접 링크"));
                return;
            }

            // 1) Direct YouTube InnerTube /search (works out of the box without any API key or Piped server)
            try {
                JSONObject body = new JSONObject()
                        .put("query", q)
                        .put("context", webContext());
                JSONObject resp = innertubePost(c, "search", body, "1", "2.20250301.00.00", WEB_UA, true);
                if (resp != null) collectRenderers(resp, out, new HashSet<>(), 24);
                if (!out.isEmpty()) src = "YouTube InnerTube";
            } catch (Throwable ignored) {}

            // 2) YouTube Data API v3 fallback
            if (out.isEmpty() && !apiKey.isEmpty()) {
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

            // 3) Piped fallback
            if (out.isEmpty() && !pipedBase.isEmpty()) {
                try {
                    String base = pipedBase.endsWith("/") ? pipedBase.substring(0, pipedBase.length() - 1) : pipedBase;
                    String json = httpGet(base + "/search?q=" + URLEncoder.encode(q, "UTF-8") + "&filter=videos", 4000);
                    if (json != null) {
                        JSONArray items = new JSONObject(json).optJSONArray("items");
                        if (items != null) {
                            for (int i = 0; i < Math.min(20, items.length()); i++) {
                                JSONObject o = items.getJSONObject(i);
                                String id = extractVideoId("https://www.youtube.com" + o.optString("url"));
                                if (id != null) {
                                    YtVideo v = new YtVideo(id, o.optString("title"), o.optString("uploaderName"));
                                    v.durationSec = Math.max(0, o.optLong("duration", 0));
                                    out.add(v);
                                }
                            }
                        }
                    }
                    if (!out.isEmpty()) src = "Piped Search";
                } catch (Throwable ignored) {}
            }

            // 4) Local RSS fallback
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

            if (v.title.startsWith("YouTube (")) {
                YtVideo oe = fetchOEmbed(v.id);
                v.title = oe.title;
                v.author = oe.author;
            }

            if (!apiKey.isEmpty()) {
                try {
                    String u = "https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&maxResults=25&videoId="
                            + URLEncoder.encode(v.id, "UTF-8") + "&key=" + URLEncoder.encode(apiKey, "UTF-8");
                    String json = httpGet(u, 5000);
                    if (json != null) {
                        JSONArray items = new JSONObject(json).optJSONArray("items");
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

            if (comments.isEmpty() && !pipedBase.isEmpty()) {
                try {
                    String base = pipedBase.endsWith("/") ? pipedBase.substring(0, pipedBase.length() - 1) : pipedBase;
                    String json = httpGet(base + "/comments/" + v.id, 4000);
                    if (json != null) {
                        JSONArray arr = new JSONObject(json).optJSONArray("comments");
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
    // Play-UI YouTube Browser Page (with Google & microG Account Card)
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

            // 2. Google & microG Account Bar
            Widgets.Group accGroup = new Widgets.Group(m, null);
            LinearLayout aRow = new LinearLayout(m);
            aRow.setOrientation(LinearLayout.HORIZONTAL);
            aRow.setGravity(Gravity.CENTER_VERTICAL);
            aRow.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(14), Ui.dp(11));

            boolean signedIn = isGoogleSignedIn(m);
            MicroGInfo mg = detectMicroGProvider(m);
            LinearLayout aText = new LinearLayout(m);
            aText.setOrientation(LinearLayout.VERTICAL);
            String titleStr = signedIn ? getSignedInLabel(m) : m.getString(R.string.yt_account_title);
            String subStr = signedIn
                    ? (mg != null && mg.isMicroG ? m.getString(R.string.yt_microg_detected, mg.label) : "YouTube 세션 활성 · 맞춤 피드 동기화")
                    : (mg != null && mg.isMicroG ? m.getString(R.string.yt_microg_detected, mg.label) : m.getString(R.string.yt_google_guest));
            aText.addView(Ui.text(m, titleStr, 14f, Ui.semibold, Ui.text()));
            TextView subTv = Ui.text(m, subStr, 12f, Ui.regular, signedIn ? 0xFF34C759 : Ui.text2());
            aText.addView(subTv);
            aRow.addView(aText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (mg != null && mg.isMicroG) {
                TextView mgBtn = Ui.text(m, "microG", 12f, Ui.semibold, Ui.ACCENT);
                mgBtn.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
                mgBtn.setBackground(Ui.round(Ui.dark ? 0x22FFFFFF : 0x14000000, 100));
                mgBtn.setOnClickListener(v -> openMicroGSettings(m));
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.rightMargin = Ui.dp(8);
                aRow.addView(mgBtn, mlp);
            }

            TextView loginBtn = Ui.text(m,
                    m.getString(signedIn ? R.string.yt_google_signout : R.string.yt_google_signin),
                    12.5f, Ui.semibold, signedIn ? Ui.text() : 0xFFFFFFFF);
            loginBtn.setPadding(Ui.dp(13), Ui.dp(7), Ui.dp(13), Ui.dp(7));
            loginBtn.setBackground(Ui.round(signedIn ? (Ui.dark ? 0x28FFFFFF : 0x18000000) : Ui.ACCENT, 100));
            loginBtn.setOnClickListener(v -> {
                if (signedIn) {
                    signOutGoogle(m);
                    rebuild();
                } else {
                    showGoogleLoginDialog(m, this::rebuild);
                }
            });
            Ui.press(loginBtn, 0.94f);
            aRow.addView(loginBtn);
            accGroup.addCustom(aRow);
            content.addView(accGroup, Widgets.MW);

            // 3. Category Chips
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

            // 4. Status & Source indicator
            LinearLayout metaRow = new LinearLayout(m);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            metaRow.setPadding(Ui.dp(22), Ui.dp(4), Ui.dp(22), Ui.dp(6));
            statusLabel = Ui.text(m, m.getString(R.string.yt_loading), 12.5f, Ui.medium, Ui.text2());
            metaRow.addView(statusLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView cfgBtn = Ui.text(m, m.getString(R.string.yt_source_settings), 12.5f, Ui.semibold, Ui.ACCENT);
            cfgBtn.setOnClickListener(v -> showSourceSettingsSheet(m));
            metaRow.addView(cfgBtn);
            content.addView(metaRow, Widgets.MW);

            // 5. Video Grid Host
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

        // Google / microG quick action row inside Source Settings too
        boolean signed = isGoogleSignedIn(m);
        MicroGInfo mg = detectMicroGProvider(m);
        TextView accStatus = Ui.text(m,
                (signed ? "연결됨: " + getSignedInLabel(m) : "Google 계정 미연결")
                        + (mg != null && mg.isMicroG ? " · " + mg.label : ""),
                13f, Ui.medium, signed ? 0xFF34C759 : Ui.text2());
        accStatus.setPadding(Ui.dp(4), 0, Ui.dp(4), Ui.dp(8));
        form.addView(accStatus, Widgets.MW);

        LinearLayout btnRow = new LinearLayout(m);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView gBtn = Ui.text(m, m.getString(signed ? R.string.yt_google_signout : R.string.yt_google_signin), 13f, Ui.semibold, 0xFFFFFFFF);
        gBtn.setPadding(Ui.dp(14), Ui.dp(9), Ui.dp(14), Ui.dp(9));
        gBtn.setBackground(Ui.round(signed ? 0xFFCC3344 : Ui.ACCENT, 12));
        gBtn.setOnClickListener(v -> {
            if (signed) {
                signOutGoogle(m);
                m.reloadAll();
            } else {
                showGoogleLoginDialog(m, m::reloadAll);
            }
        });
        btnRow.addView(gBtn);
        if (mg != null && mg.isMicroG) {
            TextView mBtn = Ui.text(m, m.getString(R.string.yt_microg_open), 13f, Ui.semibold, Ui.ACCENT);
            mBtn.setPadding(Ui.dp(14), Ui.dp(9), Ui.dp(14), Ui.dp(9));
            mBtn.setBackground(Ui.round(Ui.dark ? 0x22FFFFFF : 0x14000000, 12));
            mBtn.setOnClickListener(v -> openMicroGSettings(m));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.leftMargin = Ui.dp(8);
            btnRow.addView(mBtn, mlp);
        }
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.bottomMargin = Ui.dp(14);
        form.addView(btnRow, brlp);

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
    // Full-Screen Play-UI Native YouTube Player Overlay
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

    static final class YouTubeOverlay extends FrameLayout implements TextureView.SurfaceTextureListener {
        private final MainActivity m;
        private final YtVideo video;
        private final TextureView texture;
        private final WebView webFallback;
        private final ProgressBar spinner;
        private final TextView statusPill;
        private final FrameLayout chrome;
        private final TextView titleView, authorView, speedBadge, timeLabel, modeBtn;
        private final Widgets.Slider seekBar;
        private final Icons.IconView playBtn;

        private MediaPlayer mp;
        private MediaPlayer audioMp; // synchronized companion audio player for adaptiveFormats dual-track streams
        private boolean videoReady = false, audioReady = false;
        private Surface surface;
        private ResolvedStream currentStream;
        private boolean webMode = false;
        private boolean prepared = false;
        private boolean playing = false;
        private float speed = 1.0f;
        private int videoW = 16, videoH = 9;
        private boolean chromeVisible = true;
        private final Ui.Spring openP = new Ui.Spring(400f, 1.0f, 0.002f);
        private boolean closing;

        private final Runnable tickRun = new Runnable() {
            @Override public void run() {
                if (closing || webMode) return;
                if (mp != null && prepared) {
                    try {
                        int pos = mp.getCurrentPosition();
                        int dur = Math.max(1, mp.getDuration());
                        seekBar.setValue(Ui.clamp((float) pos / dur, 0f, 1f));
                        timeLabel.setText(Ui.time(pos) + " / " + Ui.time(dur));
                        // Keep dual-track adaptive audio tightly synchronized with video clock
                        if (audioMp != null && audioReady && playing) {
                            int aPos = audioMp.getCurrentPosition();
                            if (Math.abs(pos - aPos) > 260) {
                                audioMp.seekTo(pos);
                            }
                            if (!audioMp.isPlaying() && mp.isPlaying()) audioMp.start();
                        }
                    } catch (Throwable ignored) {}
                }
                MAIN.postDelayed(this, 350);
            }
        };

        YouTubeOverlay(MainActivity activity, YtVideo v) {
            super(activity);
            this.m = activity;
            this.video = v;
            setBackgroundColor(0xFF000000);
            setClickable(true);

            // 1. Native TextureView video surface
            texture = new TextureView(m);
            texture.setSurfaceTextureListener(this);
            texture.setOnClickListener(vv -> toggleChrome());
            addView(texture, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 2. Mobile Web fallback view (hidden unless DRM/fallback triggered or toggled)
            webFallback = new WebView(m);
            webFallback.setBackgroundColor(Color.BLACK);
            webFallback.setVisibility(GONE);
            WebSettings ws = webFallback.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setUserAgentString(WEB_UA);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webFallback, true);
            webFallback.setWebChromeClient(new WebChromeClient());
            webFallback.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    return false;
                }
            });
            addView(webFallback, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 3. Center loading indicator + status pill
            LinearLayout centerBox = new LinearLayout(m);
            centerBox.setOrientation(LinearLayout.VERTICAL);
            centerBox.setGravity(Gravity.CENTER);
            spinner = new ProgressBar(m);
            centerBox.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(42), Ui.dp(42)));
            statusPill = Ui.text(m, m.getString(R.string.yt_resolving_stream), 12.5f, Ui.medium, 0xDDFFFFFF);
            statusPill.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
            statusPill.setBackground(Ui.round(0x88000000, 100));
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stlp.topMargin = Ui.dp(10);
            centerBox.addView(statusPill, stlp);
            addView(centerBox, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            // 4. Play Glass / G-style Chrome overlay
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

            topBar.addView(iconBtn(Icons.BACK, vv -> close()));

            LinearLayout titleCol = new LinearLayout(m);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            titleCol.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
            titleView = Ui.text(m, v.title, 15f, Ui.semibold, 0xFFFFFFFF);
            authorView = Ui.text(m, v.author, 12f, Ui.regular, 0xCCFFFFFF);
            titleCol.addView(titleView);
            titleCol.addView(authorView);
            topBar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            modeBtn = Ui.text(m, m.getString(R.string.yt_mode_web), 11.5f, Ui.semibold, 0xFFFFFFFF);
            modeBtn.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
            modeBtn.setBackground(Ui.round(0x33FFFFFF, 100));
            modeBtn.setOnClickListener(vv -> switchPlaybackMode(!webMode));
            topBar.addView(modeBtn);
            topBar.addView(iconBtn(Icons.PIP, vv -> m.enterPip()));
            topBar.addView(iconBtn(Icons.INFO, vv -> showCommentsAndInfoSheet()));
            topBar.addView(iconBtn(Icons.MORE, this::showMoreSheet));

            int topSafe = Math.max(Ui.statusInset, Ui.dp(24)) + Ui.dp(10);
            int sideSafe = Math.max(Ui.cutoutLeft, Ui.cutoutRight) + Ui.dp(14);
            LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            tlp.setMargins(sideSafe, topSafe, sideSafe, 0);
            chrome.addView(topBar, tlp);

            // Bottom floating Play control card (seekbar + transport controls)
            LinearLayout botCard = new LinearLayout(m);
            botCard.setOrientation(LinearLayout.VERTICAL);
            botCard.setBackground(glassCapsule(26));
            botCard.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));

            seekBar = new Widgets.Slider(m, 0f);
            seekBar.darkSurface = true;
            seekBar.change = (val, fromUser) -> {
                if (fromUser && mp != null && prepared) {
                    try {
                        int dur = mp.getDuration();
                        if (dur > 0) {
                            int target = (int) (dur * val);
                            mp.seekTo(target);
                            if (audioMp != null && audioReady) audioMp.seekTo(target);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            botCard.addView(seekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(28)));

            LinearLayout botBar = new LinearLayout(m);
            botBar.setOrientation(LinearLayout.HORIZONTAL);
            botBar.setGravity(Gravity.CENTER_VERTICAL);

            playBtn = iconBtn(Icons.PAUSE, vv -> togglePlay());
            botBar.addView(playBtn);
            botBar.addView(iconBtn(Icons.REWIND, vv -> seekDelta(-10)));
            botBar.addView(iconBtn(Icons.FORWARD, vv -> seekDelta(10)));

            timeLabel = Ui.text(m, "0:00 / 0:00", 12f, Ui.medium, 0xDDFFFFFF);
            timeLabel.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            botBar.addView(timeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            speedBadge = Ui.text(m, "1.0x", 12.5f, Ui.bold, 0xFFFFFFFF);
            speedBadge.setGravity(Gravity.CENTER);
            speedBadge.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
            speedBadge.setBackground(Ui.round(0x28FFFFFF, 100));
            speedBadge.setOnClickListener(this::showSpeedSheet);
            botBar.addView(speedBadge);

            if (isNewPipeInstalled(m)) {
                TextView npBtn = Ui.text(m, "NewPipe", 12f, Ui.bold, 0xFFFFFFFF);
                npBtn.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
                npBtn.setBackground(Ui.round(0xCCFF0033, 100));
                npBtn.setOnClickListener(vv -> openInNewPipe(m, v.id));
                LinearLayout.LayoutParams nplp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nplp.leftMargin = Ui.dp(8);
                botBar.addView(npBtn, nplp);
            }

            botCard.addView(botBar, Widgets.MW);

            LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            blp.setMargins(sideSafe, 0, sideSafe, Math.max(Ui.navInset, Ui.dp(12)) + Ui.dp(12));
            chrome.addView(botCard, blp);

            // Start direct InnerTube stream resolution immediately
            resolveStream(m, v.id, stream -> {
                if (closing) return;
                currentStream = stream;
                if (stream.title != null && !stream.title.isEmpty()) {
                    video.title = stream.title;
                    titleView.setText(video.title);
                }
                if (stream.author != null && !stream.author.isEmpty()) {
                    video.author = stream.author;
                }
                if (stream.description != null && !stream.description.isEmpty()) {
                    video.description = stream.description;
                }
                if (stream.url != null && !stream.url.isEmpty()) {
                    authorView.setText(video.author + " · " + stream.qualityLabel);
                    startNativeStream(stream);
                } else {
                    // Graceful automatic fallback to authenticated mobile YouTube web player
                    Toast.makeText(m, m.getString(R.string.yt_stream_fallback_web), Toast.LENGTH_SHORT).show();
                    switchPlaybackMode(true);
                }
            });

            loadCommentsAndMeta(m, v, (comments, desc) -> {
                if (closing) return;
                titleView.setText(v.title);
                if (currentStream == null || currentStream.qualityLabel == null) authorView.setText(v.author);
            });

            openP.onUpdate = () -> {
                float k = Ui.clamp(openP.value, 0f, 1f);
                setAlpha(k);
                setTranslationY((1f - k) * Ui.dpf(28));
            };
            openP.onEnd = () -> {
                if (closing) {
                    releasePlayer();
                    try { webFallback.destroy(); } catch (Throwable ignored) {}
                    m.root.removeView(this);
                }
            };
            openP.snap(0f);
            openP.to(1f);
        }

        private void toggleChrome() {
            chromeVisible = !chromeVisible;
            chrome.animate().alpha(chromeVisible ? 1f : 0f).setDuration(180).start();
        }

        private void startNativeStream(ResolvedStream stream) {
            if (closing || webMode) return;
            releasePlayer();
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "IOS".equals(stream.clientUsed) ? IOS_UA : VR_UA);

                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                if (stream.dualTrack) mp.setVolume(0f, 0f);
                mp.setDataSource(m, Uri.parse(stream.url), headers);
                if (surface != null) mp.setSurface(surface);
                mp.setOnVideoSizeChangedListener((player, w, h) -> {
                    if (w > 0 && h > 0) {
                        videoW = w;
                        videoH = h;
                        updateTextureTransform();
                    }
                });
                mp.setOnPreparedListener(player -> {
                    if (closing) return;
                    videoReady = true;
                    maybeStartPlayback(stream.dualTrack);
                });
                mp.setOnCompletionListener(player -> {
                    playing = false;
                    if (audioMp != null) { try { if (audioMp.isPlaying()) audioMp.pause(); } catch (Throwable ignored) {} }
                    playBtn.set(Icons.PLAY, 0xFFFFFFFF);
                });
                mp.setOnErrorListener((player, what, extra) -> {
                    if (!closing && !webMode) switchPlaybackMode(true);
                    return true;
                });
                mp.prepareAsync();

                if (stream.dualTrack && stream.audioUrl != null) {
                    audioMp = new MediaPlayer();
                    audioMp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build());
                    audioMp.setDataSource(m, Uri.parse(stream.audioUrl), headers);
                    audioMp.setOnPreparedListener(ap -> {
                        if (closing) return;
                        audioReady = true;
                        maybeStartPlayback(true);
                    });
                    audioMp.setOnErrorListener((ap, what, extra) -> {
                        if (!closing && !webMode) switchPlaybackMode(true);
                        return true;
                    });
                    audioMp.prepareAsync();
                } else {
                    audioReady = true;
                }
            } catch (Throwable t) {
                switchPlaybackMode(true);
            }
        }

        private void maybeStartPlayback(boolean dual) {
            if (closing || !videoReady || (dual && !audioReady) || mp == null) return;
            prepared = true;
            playing = true;
            spinner.setVisibility(GONE);
            statusPill.setVisibility(GONE);
            playBtn.set(Icons.PAUSE, 0xFFFFFFFF);
            try {
                NpuAudio.apply(m);
                if (Math.abs(speed - 1f) > 0.01f) {
                    PlaybackParams pp = mp.getPlaybackParams();
                    pp.setSpeed(speed);
                    mp.setPlaybackParams(pp);
                    if (audioMp != null) {
                        PlaybackParams app = audioMp.getPlaybackParams();
                        app.setSpeed(speed);
                        audioMp.setPlaybackParams(app);
                    }
                }
            } catch (Throwable ignored) {}
            mp.start();
            if (dual && audioMp != null) audioMp.start();
            MAIN.removeCallbacks(tickRun);
            MAIN.post(tickRun);
        }

        private void switchPlaybackMode(boolean toWeb) {
            webMode = toWeb;
            if (webMode) {
                releasePlayer();
                MAIN.removeCallbacks(tickRun);
                spinner.setVisibility(GONE);
                statusPill.setVisibility(GONE);
                texture.setVisibility(GONE);
                seekBar.setVisibility(GONE);
                webFallback.setVisibility(VISIBLE);
                modeBtn.setText(m.getString(R.string.yt_mode_native));
                String ck = getGoogleCookie(m);
                if (!ck.isEmpty()) {
                    CookieManager cm = CookieManager.getInstance();
                    for (String part : ck.split(";")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) cm.setCookie("https://m.youtube.com", trimmed);
                    }
                    cm.flush();
                }
                webFallback.loadUrl("https://m.youtube.com/watch?v=" + video.id);
            } else {
                webFallback.loadUrl("about:blank");
                webFallback.setVisibility(GONE);
                texture.setVisibility(VISIBLE);
                seekBar.setVisibility(VISIBLE);
                modeBtn.setText(m.getString(R.string.yt_mode_web));
                if (currentStream != null && currentStream.url != null) {
                    spinner.setVisibility(VISIBLE);
                    statusPill.setVisibility(VISIBLE);
                    startNativeStream(currentStream);
                }
            }
        }

        private void updateTextureTransform() {
            int vw = texture.getWidth(), vh = texture.getHeight();
            if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return;
            float viewRatio = (float) vw / vh;
            float vidRatio = (float) videoW / videoH;
            float sx = 1f, sy = 1f;
            if (vidRatio > viewRatio) sy = viewRatio / vidRatio;
            else sx = vidRatio / viewRatio;
            Matrix mx = new Matrix();
            mx.setScale(sx, sy, vw / 2f, vh / 2f);
            texture.setTransform(mx);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            surface = new Surface(st);
            if (mp != null) {
                try { mp.setSurface(surface); } catch (Throwable ignored) {}
            }
            updateTextureTransform();
        }

        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            updateTextureTransform();
        }

        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            if (mp != null) {
                try { mp.setSurface(null); } catch (Throwable ignored) {}
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
            return true;
        }

        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

        private void togglePlay() {
            if (webMode) {
                webFallback.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;if(v.paused)v.play();else v.pause();})();", null);
                playing = !playing;
                playBtn.set(playing ? Icons.PAUSE : Icons.PLAY, 0xFFFFFFFF);
                return;
            }
            if (mp == null || !prepared) return;
            try {
                if (mp.isPlaying()) {
                    mp.pause();
                    if (audioMp != null && audioReady) audioMp.pause();
                    playing = false;
                } else {
                    mp.start();
                    if (audioMp != null && audioReady) {
                        audioMp.seekTo(mp.getCurrentPosition());
                        audioMp.start();
                    }
                    playing = true;
                }
                playBtn.set(playing ? Icons.PAUSE : Icons.PLAY, 0xFFFFFFFF);
            } catch (Throwable ignored) {}
        }

        private void seekDelta(int sec) {
            if (webMode) {
                webFallback.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.currentTime=Math.max(0,v.currentTime+" + sec + ");})();", null);
                return;
            }
            if (mp == null || !prepared) return;
            try {
                int target = Math.max(0, Math.min(mp.getDuration(), mp.getCurrentPosition() + sec * 1000));
                mp.seekTo(target);
                if (audioMp != null && audioReady) audioMp.seekTo(target);
            } catch (Throwable ignored) {}
        }

        private void showSpeedSheet(View anchor) {
            float[] rates = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
            String[] labels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
            int sel = 2;
            for (int i = 0; i < rates.length; i++) if (Math.abs(rates[i] - speed) < 0.02f) sel = i;
            Widgets.pickSheet(m, anchor, m.getString(R.string.speed), labels, sel, idx -> {
                speed = rates[idx];
                speedBadge.setText(labels[idx]);
                if (webMode) {
                    webFallback.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.playbackRate=" + speed + ";})();", null);
                } else if (mp != null && prepared) {
                    try {
                        PlaybackParams pp = mp.getPlaybackParams();
                        pp.setSpeed(speed);
                        mp.setPlaybackParams(pp);
                        if (audioMp != null && audioReady) {
                            PlaybackParams app = audioMp.getPlaybackParams();
                            app.setSpeed(speed);
                            audioMp.setPlaybackParams(app);
                        }
                    } catch (Throwable ignored) {}
                }
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
            es.add(new GlassMenu.Entry(webMode ? m.getString(R.string.yt_mode_native) : m.getString(R.string.yt_mode_web),
                    Icons.GLOBE, webMode, () -> switchPlaybackMode(!webMode)));
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
            commentsList.addView(Ui.text(m, m.getString(R.string.yt_loading_comments), 13.5f, Ui.regular, 0xB3FFFFFF));
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

        private void releasePlayer() {
            prepared = false;
            videoReady = false;
            audioReady = false;
            playing = false;
            if (mp != null) {
                try { mp.stop(); } catch (Throwable ignored) {}
                try { mp.release(); } catch (Throwable ignored) {}
                mp = null;
            }
            if (audioMp != null) {
                try { audioMp.stop(); } catch (Throwable ignored) {}
                try { audioMp.release(); } catch (Throwable ignored) {}
                audioMp = null;
            }
        }

        void close() {
            if (closing) return;
            closing = true;
            MAIN.removeCallbacks(tickRun);
            releasePlayer();
            try { webFallback.loadUrl("about:blank"); } catch (Throwable ignored) {}
            openP.to(0f);
        }
    }
}
