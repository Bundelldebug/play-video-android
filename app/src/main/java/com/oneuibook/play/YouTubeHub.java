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
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.VelocityTracker;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    static final String PREF_GOOGLE_OAUTH = "yt_google_oauth";
    static final String DEFAULT_PIPED = "https://pipedapi.kavin.rocks";
    static final String NEWPIPE_PKG = "org.schabi.newpipe";
    private static Runnable pendingAccountCallback;

    private static final String[][] MICROG_PKGS = {
            {"app.revanced.android.gms", "ReVanced microG (GmsCore)"},
            {"org.microg.gms", "microG Services"},
            {"com.mgoogle.android.gms", "Vanced microG"},
            {"com.google.android.gms", "Google Play Services"}
    };

    private static final String YT_ORIGIN = "https://www.youtube.com";
    private static final String YT_API_BASE = "https://www.youtube.com/youtubei/v1/";
    private static final String WEB_CLIENT_VERSION = "2.20260301.00.00";
    private static final String WEB_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)";
    private static final String VR_UA = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(96);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<>(120);

    private static final Pattern ID_PAT = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?.*v=|embed/|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");
    private static final Pattern RAW_ID_PAT = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    static final class YtVideo {
        final String id;
        String title;
        String author;
        String channelId;
        String channelAvatar;
        String published;
        String views;
        String description;
        long durationSec;
        boolean isShort;

        YtVideo(String id, String title, String author) {
            this.id = id == null ? "" : id.trim();
            this.title = title == null || title.isEmpty() ? "YouTube (" + this.id + ")" : title;
            this.author = author == null ? "YouTube" : author;
            this.channelId = "";
            this.channelAvatar = "";
            this.published = "";
            this.views = "";
            this.description = "";
        }

        String thumbUrl() { return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"; }
        String watchUrl() { return "https://www.youtube.com/watch?v=" + id; }
    }

    static final class YtComment {
        final String author, text, time, likes;
        String authorAvatar = "";
        boolean isOwner;
        boolean isPinned;
        boolean isHearted;
        int replyCount;
        String replyToken = "";
        final List<YtComment> replies = new ArrayList<>();
        boolean repliesLoaded;
        boolean repliesExpanded;

        YtComment(String author, String text, String time, String likes) {
            this.author = author == null ? "" : author;
            this.text = text == null ? "" : text;
            this.time = time == null ? "" : time;
            this.likes = likes == null ? "" : likes;
        }
    }

    static final class SubscribedChannel {
        final String channelId;
        final String name;
        String avatarUrl = "";
        String handle = "";
        String subscribers = "";

        SubscribedChannel(String channelId, String name) {
            this.channelId = channelId == null ? "" : channelId.trim();
            this.name = (name == null || name.trim().isEmpty()) ? this.channelId : name.trim();
        }
    }

    static final class QualityOption {
        final String label;
        final int height;
        final String videoUrl;
        final long videoContentLength;
        final String audioUrl;
        final long audioContentLength;
        final boolean hls;
        final boolean dualTrack;
        final boolean audioOnly;
        final String clientUsed;

        QualityOption(String label, int height, String videoUrl, long videoLen,
                      String audioUrl, long audioLen, boolean hls, boolean dualTrack,
                      boolean audioOnly, String clientUsed) {
            this.label = label;
            this.height = height;
            this.videoUrl = videoUrl;
            this.videoContentLength = videoLen;
            this.audioUrl = audioUrl;
            this.audioContentLength = audioLen;
            this.hls = hls;
            this.dualTrack = dualTrack;
            this.audioOnly = audioOnly;
            this.clientUsed = clientUsed;
        }
    }

    static final class ResolvedStream {
        String url;
        long videoContentLength = -1;
        String audioUrl; // non-null when video & audio are separate adaptiveFormats streams
        long audioContentLength = -1;
        String qualityLabel;
        String clientUsed;
        String title;
        String author;
        String description;
        long durationSec;
        boolean hls;
        boolean dualTrack;
        String error;
        final List<QualityOption> qualities = new ArrayList<>();
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

    private static String pendingAuthAccount;
    private static String pendingAuthType;
    private static int pendingAuthScopeIdx;
    private static final String[] AUTH_TOKEN_TYPES = {
            "weblogin:service=youtube&continue=https://m.youtube.com/",
            "oauth2:https://www.googleapis.com/auth/youtube",
            "oauth2:https://www.googleapis.com/auth/youtube.readonly"
    };

    /**
     * Initiates real microG / Google account login using Android's AccountManager picker,
     * supporting live cookie reuse, weblogin MergeSession silent cookie minting, KEY_INTENT consent,
     * and OAuth2 token fallback before ever falling back to manual WebView sign-in.
     */
    static void startMicroGAccountLogin(MainActivity m, Runnable onDone) {
        pendingAccountCallback = onDone;
        try {
            Intent picker = AccountManager.newChooseAccountIntent(
                    null, null,
                    new String[]{"com.google", "app.revanced.android.gms", "org.microg.gms"},
                    null, null, null, null);
            m.startActivityForResult(picker, MainActivity.REQ_PICK_ACCOUNT);
        } catch (Throwable t) {
            List<String> accs = detectDeviceGoogleAccounts(m);
            showGoogleLoginDialog(m, onDone, accs.isEmpty() ? null : accs.get(0));
        }
    }

    /** Handles AccountManager picker result from microG / Google Play Services. */
    static void handleAccountPickerResult(MainActivity m, Intent data) {
        if (data == null) {
            pendingAccountCallback = null;
            return;
        }
        String accName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        String accType = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
        if (accName == null || accName.trim().isEmpty()) {
            pendingAccountCallback = null;
            return;
        }
        accName = accName.trim();
        if (accType == null || accType.isEmpty()) accType = "com.google";

        // 1) If WebView already holds a valid YouTube Google session cookie, bind it immediately without any login window!
        try {
            CookieManager cm = CookieManager.getInstance();
            String existing = cm != null ? cm.getCookie("https://www.youtube.com") : null;
            if (existing != null && (existing.contains("SAPISID=") || existing.contains("__Secure-3PAPISID="))) {
                SharedPreferences sp = MainActivity.prefs(m);
                SecureVault.put(m, sp, PREF_GOOGLE_COOKIE, existing.trim());
                sp.edit().putString(PREF_GOOGLE_ACCOUNT, accName).apply();
                Runnable cb = pendingAccountCallback;
                pendingAccountCallback = null;
                Toast.makeText(m, accName + " · microG 계정 연결 완료", Toast.LENGTH_SHORT).show();
                if (cb != null) cb.run();
                return;
            }
        } catch (Throwable ignored) {}

        pendingAuthAccount = accName;
        pendingAuthType = accType;
        pendingAuthScopeIdx = 0;
        MainActivity.prefs(m).edit().putString(PREF_GOOGLE_ACCOUNT, accName).apply();
        requestNextAuthToken(m);
    }

    /** Resumes microG / Google token retrieval after Android's AccountManager consent Intent returns. */
    static void handleAuthConsentResult(MainActivity m, boolean ok) {
        if (pendingAuthAccount == null) return;
        if (ok) {
            requestNextAuthToken(m);
        } else {
            pendingAuthScopeIdx++;
            requestNextAuthToken(m);
        }
    }

    private static void requestNextAuthToken(MainActivity m) {
        final String accName = pendingAuthAccount;
        final String accType = pendingAuthType;
        final int idx = pendingAuthScopeIdx;
        if (accName == null || idx >= AUTH_TOKEN_TYPES.length) {
            Runnable cb = pendingAccountCallback;
            pendingAccountCallback = null;
            pendingAuthAccount = null;
            MAIN.post(() -> {
                Toast.makeText(m, "microG 외부 앱 토큰 제한 · " + accName + " 세션으로 1회 연결합니다", Toast.LENGTH_LONG).show();
                showGoogleLoginDialog(m, cb, accName);
            });
            return;
        }
        final String tokenType = AUTH_TOKEN_TYPES[idx];
        try {
            AccountManager am = AccountManager.get(m);
            Account acc = new Account(accName, accType);
            am.getAuthToken(acc, tokenType, null, m, future -> {
                try {
                    android.os.Bundle res = future.getResult();
                    if (res != null) {
                        Intent consent = res.getParcelable(AccountManager.KEY_INTENT);
                        if (consent != null) {
                            MAIN.post(() -> {
                                try {
                                    m.startActivityForResult(consent, MainActivity.REQ_AUTH_CONSENT);
                                } catch (Throwable e) {
                                    pendingAuthScopeIdx++;
                                    requestNextAuthToken(m);
                                }
                            });
                            return;
                        }
                        String tok = res.getString(AccountManager.KEY_AUTHTOKEN);
                        if (tok != null && !tok.isEmpty()) {
                            if (tokenType.startsWith("weblogin:") && tok.startsWith("http")) {
                                MAIN.post(() -> exchangeMergeSessionSilent(m, accName, tok));
                                return;
                            }
                            SharedPreferences sp = MainActivity.prefs(m);
                            SecureVault.put(m, sp, PREF_GOOGLE_OAUTH, tok);
                            sp.edit().putString(PREF_GOOGLE_ACCOUNT, accName).apply();
                            Runnable cb = pendingAccountCallback;
                            pendingAccountCallback = null;
                            pendingAuthAccount = null;
                            MAIN.post(() -> {
                                Toast.makeText(m, accName + " · microG 로그인 완료", Toast.LENGTH_SHORT).show();
                                if (cb != null) cb.run();
                            });
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
                pendingAuthScopeIdx++;
                requestNextAuthToken(m);
            }, MAIN);
        } catch (Throwable t) {
            pendingAuthScopeIdx++;
            requestNextAuthToken(m);
        }
    }

    static void hardenWebSettings(WebSettings ws) {
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setSaveFormData(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    }

    static boolean isTrustedGoogleOrYouTubeUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return "youtube.com".equals(h) || h.endsWith(".youtube.com")
                    || "youtu.be".equals(h)
                    || "google.com".equals(h) || h.endsWith(".google.com")
                    || "gstatic.com".equals(h) || h.endsWith(".gstatic.com")
                    || "googleapis.com".equals(h) || h.endsWith(".googleapis.com")
                    || "googlevideo.com".equals(h) || h.endsWith(".googlevideo.com")
                    || "ytimg.com".equals(h) || h.endsWith(".ytimg.com")
                    || "youtube-nocookie.com".equals(h) || h.endsWith(".youtube-nocookie.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Silently exchanges a Google/microG MergeSession URL for real YouTube cookies without showing any login UI. */
    private static void exchangeMergeSessionSilent(MainActivity m, String accName, String mergeUrl) {
        if (!isTrustedGoogleOrYouTubeUrl(mergeUrl)) {
            pendingAuthScopeIdx++;
            requestNextAuthToken(m);
            return;
        }
        try {
            WebView wv = new WebView(m);
            WebSettings ws = wv.getSettings();
            hardenWebSettings(ws);
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setUserAgentString(WEB_UA);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(wv, true);
            wv.setVisibility(View.INVISIBLE);
            m.root.addView(wv, new FrameLayout.LayoutParams(1, 1));
            final boolean[] finished = {false};
            Runnable cleanup = () -> {
                if (finished[0]) return;
                finished[0] = true;
                try { wv.stopLoading(); m.root.removeView(wv); wv.destroy(); } catch (Throwable ignored) {}
            };
            wv.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    String u = req != null && req.getUrl() != null ? req.getUrl().toString() : null;
                    return !isTrustedGoogleOrYouTubeUrl(u);
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (finished[0]) return;
                    cm.flush();
                    String ckYt = cm.getCookie("https://www.youtube.com");
                    String ckMob = cm.getCookie("https://m.youtube.com");
                    String merged = (ckYt != null ? ckYt : "") + (ckMob != null && !ckMob.equals(ckYt) ? "; " + ckMob : "");
                    if (merged.contains("SAPISID=") || merged.contains("__Secure-3PAPISID=") || merged.contains("SID=")) {
                        cleanup.run();
                        SharedPreferences sp = MainActivity.prefs(m);
                        SecureVault.put(m, sp, PREF_GOOGLE_COOKIE, merged.trim());
                        sp.edit().putString(PREF_GOOGLE_ACCOUNT, accName).apply();
                        Runnable cb = pendingAccountCallback;
                        pendingAccountCallback = null;
                        pendingAuthAccount = null;
                        Toast.makeText(m, accName + " · microG 자동 로그인 완료", Toast.LENGTH_SHORT).show();
                        if (cb != null) cb.run();
                    }
                }
            });
            wv.loadUrl(mergeUrl);
            MAIN.postDelayed(() -> {
                if (!finished[0]) {
                    cleanup.run();
                    pendingAuthScopeIdx++;
                    requestNextAuthToken(m);
                }
            }, 6500);
        } catch (Throwable t) {
            pendingAuthScopeIdx++;
            requestNextAuthToken(m);
        }
    }

    static String getGoogleOAuthToken(Context c) {
        return SecureVault.get(c, MainActivity.prefs(c), PREF_GOOGLE_OAUTH).trim();
    }

    static String getGoogleCookie(Context c) {
        SharedPreferences sp = MainActivity.prefs(c);
        String saved = SecureVault.get(c, sp, PREF_GOOGLE_COOKIE).trim();
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                String liveW = cm.getCookie("https://www.youtube.com");
                String liveM = cm.getCookie("https://m.youtube.com");
                String merged = mergeCookieStrings(saved, mergeCookieStrings(liveM, liveW));
                if (!merged.isEmpty()) {
                    if (!merged.equals(saved) && (merged.contains("SAPISID=") || merged.contains("__Secure-3PAPISID="))) {
                        SecureVault.put(c, sp, PREF_GOOGLE_COOKIE, merged);
                    }
                    return merged;
                }
            }
        } catch (Throwable ignored) {}
        return saved;
    }

    private static String mergeCookieStrings(String base, String override) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (String src : new String[]{base, override}) {
            if (src == null || src.trim().isEmpty()) continue;
            for (String part : src.split(";")) {
                String t = part.trim();
                int eq = t.indexOf('=');
                if (eq > 0) map.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static boolean isGoogleSignedIn(Context c) {
        if (!getGoogleOAuthToken(c).isEmpty()) return true;
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
                .remove(PREF_GOOGLE_OAUTH)
                .apply();
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.removeAllCookies(null);
                cm.flush();
            }
        } catch (Throwable ignored) {}
    }

    /** Generates modern YouTube SAPISIDHASH (+ 1P/3P) Authorization header from Google session cookies. */
    static String buildSapisidHash(String cookieHeader, String origin) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;
        String org = (origin == null || origin.isEmpty()) ? YT_ORIGIN : origin;
        String s3 = extractCookieValue(cookieHeader, "__Secure-3PAPISID");
        String s1 = extractCookieValue(cookieHeader, "__Secure-1PAPISID");
        String s0 = extractCookieValue(cookieHeader, "SAPISID");
        String primary = (s3 != null && !s3.isEmpty()) ? s3 : s0;
        if (primary == null || primary.isEmpty()) primary = s1;
        if (primary == null || primary.isEmpty()) return null;
        try {
            long ts = System.currentTimeMillis() / 1000L;
            String h0 = sha1Hex(ts + " " + primary + " " + org);
            StringBuilder sb = new StringBuilder("SAPISIDHASH ").append(ts).append('_').append(h0);
            if (s1 != null && !s1.isEmpty()) {
                sb.append(" SAPISID1PHASH ").append(ts).append('_').append(sha1Hex(ts + " " + s1 + " " + org));
            }
            if (s3 != null && !s3.isEmpty()) {
                sb.append(" SAPISID3PHASH ").append(ts).append('_').append(sha1Hex(ts + " " + s3 + " " + org));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha1Hex(String raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : dig) hex.append(String.format(Locale.US, "%02x", b & 0xff));
        return hex.toString();
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
        showGoogleLoginDialog(m, onDone, null);
    }

    static void showGoogleLoginDialog(MainActivity m, Runnable onDone, String prefillEmail) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);

        MicroGInfo mg = detectMicroGProvider(m);
        List<String> devAccs = detectDeviceGoogleAccounts(m);
        StringBuilder hint = new StringBuilder();
        if (prefillEmail != null && !prefillEmail.isEmpty()) {
            hint.append("선택한 계정: ").append(prefillEmail).append("\n로그인을 완료하면 YouTube 맞춤 피드와 고화질 스트림이 자동 연동됩니다.");
        } else if (mg != null && mg.isMicroG) {
            hint.append(m.getString(R.string.yt_microg_detected, mg.label));
            if (!devAccs.isEmpty()) hint.append(" · ").append(devAccs.get(0));
            hint.append("\n아래에서 Google 계정에 로그인하거나 'microG 계정 선택'으로 바로 연동할 수 있습니다.");
        } else {
            hint.append("Google 계정에 로그인하면 YouTube 맞춤 피드와 연령·세션 제한 영상을 재생할 수 있습니다.");
        }

        TextView banner = Ui.text(m, hint.toString(), 12.5f, Ui.medium, Ui.text2());
        banner.setSingleLine(false);
        banner.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(8));
        box.addView(banner, Widgets.MW);

        WebView wv = new WebView(m);
        WebSettings ws = wv.getSettings();
        hardenWebSettings(ws);
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
                    String label = (prefillEmail != null && !prefillEmail.isEmpty()) ? prefillEmail
                            : (!devAccs.isEmpty() ? devAccs.get(0) : m.getString(R.string.yt_google_signed_in));
                    SharedPreferences sp = MainActivity.prefs(m);
                    SecureVault.put(m, sp, PREF_GOOGLE_COOKIE, merged.trim());
                    sp.edit().putString(PREF_GOOGLE_ACCOUNT, label).apply();
                    Toast.makeText(m, m.getString(R.string.yt_google_signed_in), Toast.LENGTH_SHORT).show();
                    if (dlgRef[0] != null && dlgRef[0].isShowing()) dlgRef[0].dismiss();
                    if (onDone != null) onDone.run();
                }
            } catch (Throwable ignored) {}
        };

        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req != null && req.getUrl() != null ? req.getUrl().toString() : null;
                return !isTrustedGoogleOrYouTubeUrl(u);
            }
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
        String loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F";
        if (prefillEmail != null && !prefillEmail.isEmpty()) {
            try { loginUrl += "&Email=" + URLEncoder.encode(prefillEmail, "UTF-8"); } catch (Throwable ignored) {}
        }
        wv.loadUrl(loginUrl);

        int h = Math.max(Ui.dp(380), (int) (Ui.screenH * 0.62f));
        box.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        AlertDialog.Builder b = new AlertDialog.Builder(m,
                Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(m.getString(R.string.yt_account_title))
                .setView(box)
                .setPositiveButton("로그인 완료 적용", (d, w) -> captureSession.run())
                .setNegativeButton(m.getString(R.string.cancel), null);
        if (prefillEmail == null && mg != null && mg.isMicroG) {
            b.setNeutralButton(m.getString(R.string.yt_microg_signin), (d, w) -> startMicroGAccountLogin(m, onDone));
        }
        dlgRef[0] = b.show();
        dlgRef[0].setOnDismissListener(d -> {
            try { wv.stopLoading(); wv.destroy(); } catch (Throwable ignored) {}
        });
        android.view.Window dw = dlgRef[0].getWindow();
        if (dw != null) {
            dw.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dw.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        wv.requestFocus(View.FOCUS_DOWN);
    }

    private static String uaForClient(String clientUsed) {
        if ("IOS".equals(clientUsed)) return IOS_UA;
        if ("TVHTML5_SIMPLY_EMBEDDED_PLAYER".equals(clientUsed) || "WEB".equals(clientUsed)) return WEB_UA;
        return VR_UA;
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
                String oauth = getGoogleOAuthToken(c);
                String cookie = getGoogleCookie(c);
                if (!cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                    String auth = buildSapisidHash(cookie, YT_ORIGIN);
                    if (auth != null) conn.setRequestProperty("Authorization", auth);
                    conn.setRequestProperty("X-Origin", YT_ORIGIN);
                    conn.setRequestProperty("X-Goog-AuthUser", "0");
                    conn.setRequestProperty("X-Youtube-Bootstrap-Logged-In", "true");
                } else if (!oauth.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + oauth);
                    conn.setRequestProperty("X-Goog-AuthUser", "0");
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
                .put("clientVersion", WEB_CLIENT_VERSION)
                .put("hl", "ko")
                .put("gl", "KR");
        return new JSONObject().put("client", client);
    }

    /** Recursively extracts videoRenderer / compactVideoRenderer / gridVideoRenderer / playlistVideoRenderer / lockupViewModel / Shorts items. */
    static void collectRenderers(Object node, List<YtVideo> out, Set<String> seen, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            // 1) Classic video / compact / grid / playlist video renderers
            JSONObject vr = o.optJSONObject("videoRenderer");
            if (vr == null) vr = o.optJSONObject("compactVideoRenderer");
            if (vr == null) vr = o.optJSONObject("gridVideoRenderer");
            if (vr == null) vr = o.optJSONObject("playlistVideoRenderer");
            if (vr != null) {
                String vid = vr.optString("videoId");
                if (!vid.isEmpty() && !seen.contains(vid)) {
                    seen.add(vid);
                    String title = readYtText(vr.optJSONObject("title"));
                    String author = readYtText(vr.optJSONObject("ownerText"));
                    if (author.isEmpty()) author = readYtText(vr.optJSONObject("longBylineText"));
                    if (author.isEmpty()) author = readYtText(vr.optJSONObject("shortBylineText"));
                    YtVideo v = new YtVideo(vid, title, author);
                    v.channelId = extractChannelIdFromRenderer(vr);
                    v.channelAvatar = extractChannelAvatarFromRenderer(vr);
                    v.views = readYtText(vr.optJSONObject("shortViewCountText"));
                    if (v.views.isEmpty()) v.views = readYtText(vr.optJSONObject("viewCountText"));
                    v.published = readYtText(vr.optJSONObject("publishedTimeText"));
                    v.durationSec = parseDurationText(readYtText(vr.optJSONObject("lengthText")));
                    if (v.durationSec == 0) v.durationSec = vr.optLong("lengthSeconds", 0L);
                    out.add(v);
                }
                return;
            }
            // 2) Modern 2025/2026 YouTube Web lockupViewModel (used across FEwhat_to_watch, FEsubscriptions, FEhistory, FElibrary)
            JSONObject lvm = o.optJSONObject("lockupViewModel");
            if (lvm != null) {
                String contentType = lvm.optString("contentType");
                String vid = lvm.optString("contentId");
                if (!"LOCKUP_CONTENT_TYPE_PLAYLIST".equals(contentType)
                        && !"LOCKUP_CONTENT_TYPE_CHANNEL".equals(contentType)
                        && RAW_ID_PAT.matcher(vid).matches() && !seen.contains(vid)) {
                    YtVideo v = parseLockupVideo(lvm, vid);
                    if (v != null) {
                        seen.add(vid);
                        out.add(v);
                    }
                    return;
                }
            }
            // 3) YouTube Shorts (shortsLockupViewModel / reelItemRenderer)
            JSONObject slvm = o.optJSONObject("shortsLockupViewModel");
            if (slvm != null) {
                String vid = findFirstStringByKey(slvm, "videoId");
                if (RAW_ID_PAT.matcher(vid).matches() && !seen.contains(vid)) {
                    String title = findFirstStringByKey(slvm.optJSONObject("overlayMetadata"), "content");
                    if (title.isEmpty()) title = slvm.optString("accessibilityText");
                    YtVideo v = new YtVideo(vid, title, "Shorts");
                    v.isShort = true;
                    seen.add(vid);
                    out.add(v);
                }
                return;
            }
            JSONObject rir = o.optJSONObject("reelItemRenderer");
            if (rir != null) {
                String vid = rir.optString("videoId");
                if (!vid.isEmpty() && !seen.contains(vid)) {
                    YtVideo v = new YtVideo(vid, readYtText(rir.optJSONObject("headline")), "Shorts");
                    v.views = readYtText(rir.optJSONObject("viewCountText"));
                    v.isShort = true;
                    seen.add(vid);
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

    private static YtVideo parseLockupVideo(JSONObject lvm, String vid) {
        JSONObject meta = lvm.optJSONObject("metadata");
        JSONObject lmvm = meta == null ? null : meta.optJSONObject("lockupMetadataViewModel");
        String title = "";
        String author = "";
        String views = "";
        String pub = "";
        String chId = "";
        String avatar = "";
        if (lmvm != null) {
            JSONObject tObj = lmvm.optJSONObject("title");
            if (tObj != null) title = tObj.optString("content");
            JSONObject cm = lmvm.optJSONObject("metadata");
            JSONObject cmm = cm == null ? null : cm.optJSONObject("contentMetadataViewModel");
            JSONArray rows = cmm == null ? null : cmm.optJSONArray("metadataRows");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                    if (parts == null) continue;
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject pt = parts.optJSONObject(j);
                        JSONObject txt = pt == null ? null : pt.optJSONObject("text");
                        String s = txt == null ? "" : txt.optString("content");
                        if (s.isEmpty()) continue;
                        if (i == 0 && author.isEmpty()) author = s;
                        else if (views.isEmpty() && (s.contains("조회수") || s.contains("view") || s.contains("시청"))) views = s;
                        else if (pub.isEmpty() && (s.contains("전") || s.contains("ago") || s.contains("스트리밍"))) pub = s;
                        else if (views.isEmpty()) views = s;
                    }
                }
            }
            JSONObject img = lmvm.optJSONObject("image");
            if (img != null) {
                avatar = findFirstImageUrl(img);
                chId = findFirstBrowseId(img, "UC");
            }
        }
        if (title.isEmpty()) return null;
        YtVideo v = new YtVideo(vid, title, author.isEmpty() ? "YouTube" : author);
        v.views = views;
        v.published = pub;
        v.channelId = chId;
        v.channelAvatar = avatar;
        String durTxt = findDurationBadgeText(lvm.optJSONObject("contentImage"));
        if (!durTxt.isEmpty()) v.durationSec = parseDurationText(durTxt);
        return v;
    }

    private static String findDurationBadgeText(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String t = o.optString("text");
            if (!t.isEmpty() && t.contains(":") && t.length() <= 10) return t;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String r = findDurationBadgeText(o.opt(keys.next()));
                if (!r.isEmpty()) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findDurationBadgeText(a.opt(i));
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private static String extractChannelAvatarFromRenderer(JSONObject vr) {
        if (vr == null) return "";
        JSONObject chThumb = vr.optJSONObject("channelThumbnailSupportedRenderers");
        if (chThumb != null) {
            String u = findFirstImageUrl(chThumb);
            if (!u.isEmpty()) return u;
        }
        JSONObject ct = vr.optJSONObject("channelThumbnail");
        if (ct != null) {
            String u = findFirstImageUrl(ct);
            if (!u.isEmpty()) return u;
        }
        JSONObject av = vr.optJSONObject("avatar");
        if (av != null) {
            String u = findFirstImageUrl(av);
            if (!u.isEmpty()) return u;
        }
        return "";
    }

    private static String findFirstImageUrl(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray srcs = o.optJSONArray("sources");
            if (srcs != null && srcs.length() > 0) {
                JSONObject best = srcs.optJSONObject(srcs.length() - 1);
                String u = best == null ? "" : best.optString("url");
                if (u.startsWith("http")) return u;
            }
            JSONArray thumbs = o.optJSONArray("thumbnails");
            if (thumbs != null && thumbs.length() > 0) {
                JSONObject best = thumbs.optJSONObject(thumbs.length() - 1);
                String u = best == null ? "" : best.optString("url");
                if (u.startsWith("//")) u = "https:" + u;
                if (u.startsWith("http")) return u;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String r = findFirstImageUrl(o.opt(keys.next()));
                if (!r.isEmpty()) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findFirstImageUrl(a.opt(i));
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private static String findFirstBrowseId(Object node, String prefix) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String bid = o.optString("browseId");
            if (!bid.isEmpty() && (prefix == null || bid.startsWith(prefix))) return bid;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String r = findFirstBrowseId(o.opt(keys.next()), prefix);
                if (!r.isEmpty()) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findFirstBrowseId(a.opt(i), prefix);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private static String extractChannelIdFromRenderer(JSONObject vr) {
        if (vr == null) return "";
        String[] fields = {"ownerText", "longBylineText", "shortBylineText"};
        for (String f : fields) {
            JSONObject obj = vr.optJSONObject(f);
            JSONArray runs = obj == null ? null : obj.optJSONArray("runs");
            if (runs != null && runs.length() > 0) {
                JSONObject r0 = runs.optJSONObject(0);
                JSONObject nav = r0 == null ? null : r0.optJSONObject("navigationEndpoint");
                JSONObject br = nav == null ? null : nav.optJSONObject("browseEndpoint");
                String bid = br == null ? "" : br.optString("browseId");
                if (bid.startsWith("UC")) return bid;
            }
        }
        return "";
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

                // Collect ALL available quality options (HLS Auto + all progressive + all adaptive H.264 resolutions + Audio)
                String hls = sd.optString("hlsManifestUrl");
                if (!hls.isEmpty()) {
                    result.qualities.add(new QualityOption("자동 (HLS HD)", 9999, hls, -1L, null, -1L, true, false, false, cl[0]));
                }

                // Best AAC audio stream for pairing with adaptive video resolutions & audio-only download
                String bestAudUrl = null;
                long bestAudLen = -1;
                int bestAudBr = -1;
                JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
                if (adaptive != null) {
                    for (int i = 0; i < adaptive.length(); i++) {
                        JSONObject f = adaptive.optJSONObject(i);
                        if (f == null) continue;
                        String u = f.optString("url");
                        if (u.isEmpty()) continue;
                        String mime = f.optString("mimeType").toLowerCase(Locale.US);
                        if (mime.startsWith("audio/mp4")) {
                            int br = f.optInt("bitrate", 0);
                            if (br > bestAudBr) {
                                bestAudBr = br;
                                bestAudUrl = u;
                                bestAudLen = parseLongSafe(f.optString("contentLength", "-1"), -1L);
                            }
                        }
                    }
                }

                Map<String, QualityOption> byLabel = new java.util.LinkedHashMap<>();
                // Progressive formats (single muxed MP4 — fast & directly downloadable!)
                JSONArray formats = sd.optJSONArray("formats");
                if (formats != null) {
                    for (int i = 0; i < formats.length(); i++) {
                        JSONObject f = formats.optJSONObject(i);
                        if (f == null) continue;
                        String u = f.optString("url");
                        if (u.isEmpty()) continue;
                        String mime = f.optString("mimeType").toLowerCase(Locale.US);
                        if (!mime.contains("video/mp4")) continue;
                        int h = f.optInt("height", 360);
                        String qLbl = f.optString("qualityLabel", h + "p");
                        long clen = parseLongSafe(f.optString("contentLength", "-1"), -1L);
                        byLabel.put(qLbl, new QualityOption(qLbl, h, u, clen, null, -1L, false, false, false, cl[0]));
                    }
                }
                // Adaptive H.264 video resolutions (2160p, 1440p, 1080p, 720p, 480p, 360p, 240p, 144p) paired with AAC audio
                if (adaptive != null && bestAudUrl != null) {
                    for (int i = 0; i < adaptive.length(); i++) {
                        JSONObject f = adaptive.optJSONObject(i);
                        if (f == null) continue;
                        String u = f.optString("url");
                        if (u.isEmpty()) continue;
                        String mime = f.optString("mimeType").toLowerCase(Locale.US);
                        if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue;
                        int h = f.optInt("height", 360);
                        String qLbl = f.optString("qualityLabel", h + "p");
                        long clen = parseLongSafe(f.optString("contentLength", "-1"), -1L);
                        if (!byLabel.containsKey(qLbl)) {
                            byLabel.put(qLbl, new QualityOption(qLbl, h, u, clen, bestAudUrl, bestAudLen, false, true, false, cl[0]));
                        }
                    }
                }
                List<QualityOption> sorted = new ArrayList<>(byLabel.values());
                java.util.Collections.sort(sorted, (a, b) -> Integer.compare(b.height, a.height));
                result.qualities.addAll(sorted);
                if (bestAudUrl != null) {
                    result.qualities.add(new QualityOption("오디오만 (M4A)", 0, bestAudUrl, bestAudLen, bestAudUrl, bestAudLen, false, false, true, cl[0]));
                }

                // Pick best default playback option: HLS HD if present, else highest <=1080p video option
                if (!result.qualities.isEmpty()) {
                    QualityOption def = result.qualities.get(0);
                    if (!def.hls) {
                        for (QualityOption qo : result.qualities) {
                            if (!qo.audioOnly && qo.height <= 1080) { def = qo; break; }
                        }
                    }
                    result.url = def.videoUrl;
                    result.videoContentLength = def.videoContentLength;
                    result.audioUrl = def.audioUrl;
                    result.audioContentLength = def.audioContentLength;
                    result.qualityLabel = def.label;
                    result.clientUsed = def.clientUsed;
                    result.hls = def.hls;
                    result.dualTrack = def.dualTrack;
                    return result;
                }
            } catch (Throwable ignored) {}
        }
        result.error = "Direct stream unavailable";
        return result;
    }

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s); } catch (Throwable t) { return def; }
    }

    /** Prefetching HTTP Range MediaDataSource (512 KB chunks + async look-ahead + 4 retries) for zero-stutter YouTube HD! */
    static final class RangeMediaDataSource extends android.media.MediaDataSource {
        private static final int CHUNK = 524288;
        private static final int MAX_CACHED = 24;
        private static final ExecutorService PREFETCH = Executors.newFixedThreadPool(2);
        private final String url, ua;
        private volatile long totalSize;
        private volatile boolean closed;
        private final java.util.LinkedHashMap<Long, byte[]> cache = new java.util.LinkedHashMap<Long, byte[]>(MAX_CACHED + 2, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) { return size() > MAX_CACHED; }
        };
        private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<byte[]>> inFlight =
                new java.util.concurrent.ConcurrentHashMap<>();

        RangeMediaDataSource(String url, long contentLength, String ua) {
            this.url = url;
            this.totalSize = contentLength;
            this.ua = ua != null ? ua : IOS_UA;
            prefetch(0);
            if (contentLength <= 0 || contentLength > CHUNK) prefetch(1);
        }

        private void prefetch(final long idx) {
            if (closed || idx < 0) return;
            long total = totalSize;
            if (total > 0 && idx * CHUNK >= total) return;
            synchronized (cache) { if (cache.containsKey(idx)) return; }
            if (inFlight.containsKey(idx)) return;
            java.util.concurrent.CompletableFuture<byte[]> fut = new java.util.concurrent.CompletableFuture<>();
            if (inFlight.putIfAbsent(idx, fut) != null) return;
            PREFETCH.execute(() -> {
                try {
                    if (closed) { fut.complete(null); return; }
                    long start = idx * CHUNK;
                    long curTotal = totalSize;
                    long end = curTotal > 0 ? Math.min(curTotal - 1, start + CHUNK - 1) : (start + CHUNK - 1);
                    byte[] d = fetchRange(start, end, curTotal <= 0);
                    if (d != null && d.length > 0 && !closed) {
                        synchronized (cache) { if (!closed) cache.put(idx, d); }
                    }
                    fut.complete(d);
                } catch (Throwable t) {
                    fut.completeExceptionally(t);
                } finally {
                    inFlight.remove(idx, fut);
                }
            });
        }

        private byte[] loadChunk(long idx) throws java.io.IOException {
            if (closed) return null;
            synchronized (cache) {
                byte[] hit = cache.get(idx);
                if (hit != null) return hit;
            }
            java.util.concurrent.CompletableFuture<byte[]> ex = inFlight.get(idx);
            if (ex != null) {
                try {
                    byte[] waited = ex.get(16, java.util.concurrent.TimeUnit.SECONDS);
                    if (waited != null && waited.length > 0) return waited;
                } catch (Throwable ignored) {}
            }
            long total = totalSize;
            long start = idx * CHUNK;
            long end = total > 0 ? Math.min(total - 1, start + CHUNK - 1) : (start + CHUNK - 1);
            byte[] data = fetchRange(start, end, total <= 0);
            if (data != null && data.length > 0 && !closed) {
                synchronized (cache) { if (!closed) cache.put(idx, data); }
            }
            return data;
        }

        private long ensureSize() throws java.io.IOException {
            if (totalSize > 0) return totalSize;
            byte[] probe = loadChunk(0);
            if (probe != null && probe.length > 0 && totalSize <= 0) totalSize = probe.length;
            return totalSize > 0 ? totalSize : -1;
        }

        @Override public long getSize() throws java.io.IOException { return ensureSize(); }

        @Override public int readAt(long position, byte[] buffer, int offset, int size) throws java.io.IOException {
            if (closed || size <= 0) return 0;
            long total = ensureSize();
            if (total > 0 && position >= total) return -1;
            int want = total > 0 ? (int) Math.min(size, total - position) : size;
            long idx = position / CHUNK;
            int inChunk = (int) (position - idx * CHUNK);
            prefetch(idx + 1);
            prefetch(idx + 2);
            byte[] data = loadChunk(idx);
            if (data == null || data.length == 0 || inChunk >= data.length) return -1;
            int n = Math.min(want, data.length - inChunk);
            System.arraycopy(data, inChunk, buffer, offset, n);
            return n;
        }

        private byte[] fetchRange(long start, long end, boolean parseTotal) throws java.io.IOException {
            java.io.IOException lastErr = null;
            for (int attempt = 0; attempt < 4 && !closed; attempt++) {
                if (attempt > 0) {
                    try { Thread.sleep(attempt == 1 ? 120L : (attempt == 2 ? 320L : 650L)); } catch (InterruptedException ignored) { break; }
                }
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", ua);
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                    int code = conn.getResponseCode();
                    if (code != 206 && code != 200) throw new java.io.IOException("HTTP " + code);
                    if (parseTotal || totalSize <= 0) {
                        String cr = conn.getHeaderField("Content-Range");
                        if (cr != null && cr.lastIndexOf('/') > 0) {
                            long p = parseLongSafe(cr.substring(cr.lastIndexOf('/') + 1).trim(), -1L);
                            if (p > 0) totalSize = p;
                        } else if (code == 200 && conn.getContentLengthLong() > 0) {
                            totalSize = conn.getContentLengthLong();
                        }
                    }
                    try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream(CHUNK)) {
                        byte[] buf = new byte[32768];
                        int r;
                        while (!closed && (r = is.read(buf)) != -1) bos.write(buf, 0, r);
                        byte[] out = bos.toByteArray();
                        if (out.length > 0) return out;
                        throw new java.io.IOException("empty body");
                    }
                } catch (java.io.IOException e) { lastErr = e; } finally { if (conn != null) conn.disconnect(); }
            }
            if (lastErr != null) throw lastErr;
            return null;
        }

        @Override public void close() {
            closed = true;
            inFlight.clear();
            synchronized (cache) { cache.clear(); }
        }
    }

    // ---- Direct YouTube Video Downloader (chunked HTTP 206 Range + MediaMuxer for HD + MediaStore Movies/PlayVideo) ----
    static void showDownloadSheet(MainActivity m, YtVideo v, ResolvedStream preResolved, View anchor) {
        if (v == null || v.id.isEmpty()) return;
        Runnable openPicker = () -> {};
        if (preResolved != null && !preResolved.qualities.isEmpty()) {
            presentDownloadQualitySheet(m, v, preResolved, anchor);
            return;
        }
        Toast.makeText(m, m.getString(R.string.yt_resolving_stream), Toast.LENGTH_SHORT).show();
        resolveStream(m, v.id, stream -> {
            if (stream == null || stream.qualities.isEmpty()) {
                Toast.makeText(m, m.getString(R.string.yt_download_failed), Toast.LENGTH_SHORT).show();
                return;
            }
            presentDownloadQualitySheet(m, v, stream, anchor);
        });
    }

    private static void presentDownloadQualitySheet(MainActivity m, YtVideo v, ResolvedStream stream, View anchor) {
        List<QualityOption> downloadable = new ArrayList<>();
        for (QualityOption q : stream.qualities) {
            if (!q.hls) downloadable.add(q);
        }
        if (downloadable.isEmpty()) {
            Toast.makeText(m, m.getString(R.string.yt_download_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[downloadable.size()];
        for (int i = 0; i < downloadable.size(); i++) {
            QualityOption q = downloadable.get(i);
            String sz = q.videoContentLength > 0 ? (" · " + Ui.size(q.videoContentLength + Math.max(0, q.audioContentLength))) : "";
            labels[i] = q.label + (q.audioOnly ? "" : (q.dualTrack ? " (HD MP4)" : " (MP4)")) + sz;
        }
        Widgets.pickSheet(m, anchor, m.getString(R.string.yt_download), labels, 0, idx -> {
            downloadOptionToDevice(m, v, downloadable.get(idx));
        });
    }

    private static void downloadOptionToDevice(MainActivity m, YtVideo v, QualityOption q) {
        Toast.makeText(m, m.getString(R.string.yt_downloading) + " (" + q.label + ")", Toast.LENGTH_SHORT).show();
        final Context appCtx = m.getApplicationContext();
        EXEC.execute(() -> {
            java.io.File tmpV = null, tmpA = null, tmpOut = null;
            try {
                String ua = uaForClient(q.clientUsed);
                String safeName = (v.title == null ? v.id : v.title).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (safeName.length() > 64) safeName = safeName.substring(0, 64).trim();
                if (safeName.isEmpty()) safeName = "YouTube_" + v.id;
                String ext = q.audioOnly ? ".m4a" : ".mp4";
                String fileName = safeName + "_" + q.label.replaceAll("[^A-Za-z0-9]", "") + ext;

                java.io.File cacheDir = appCtx.getCacheDir();
                tmpV = new java.io.File(cacheDir, "ytdl_v_" + v.id + "_" + System.currentTimeMillis() + ext);
                downloadRangeToFile(q.videoUrl, q.videoContentLength, ua, tmpV);

                java.io.File finalFile = tmpV;
                if (q.dualTrack && q.audioUrl != null) {
                    tmpA = new java.io.File(cacheDir, "ytdl_a_" + v.id + "_" + System.currentTimeMillis() + ".m4a");
                    downloadRangeToFile(q.audioUrl, q.audioContentLength, ua, tmpA);
                    tmpOut = new java.io.File(cacheDir, "ytdl_mux_" + v.id + "_" + System.currentTimeMillis() + ".mp4");
                    muxVideoAndAudio(tmpV, tmpA, tmpOut);
                    finalFile = tmpOut;
                }

                // Save into public MediaStore Movies/PlayVideo (or Music/PlayVideo for audio-only) with IS_PENDING guard
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, q.audioOnly ? "audio/mp4" : "video/mp4");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        (q.audioOnly ? android.os.Environment.DIRECTORY_MUSIC : android.os.Environment.DIRECTORY_MOVIES) + "/PlayVideo");
                cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                Uri target = appCtx.getContentResolver().insert(
                        q.audioOnly ? android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                : android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (target == null) throw new java.io.IOException("MediaStore insert failed");
                try {
                    try (java.io.InputStream is = new java.io.FileInputStream(finalFile);
                         OutputStream os = appCtx.getContentResolver().openOutputStream(target)) {
                        if (os == null) throw new java.io.IOException("openOutputStream failed");
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                    }
                    android.content.ContentValues fin = new android.content.ContentValues();
                    fin.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                    appCtx.getContentResolver().update(target, fin, null, null);
                } catch (Throwable copyErr) {
                    try { appCtx.getContentResolver().delete(target, null, null); } catch (Throwable ignored) {}
                    throw copyErr;
                }
                MAIN.post(() -> {
                    Toast.makeText(m, m.getString(R.string.yt_download_done), Toast.LENGTH_LONG).show();
                    VideoLibrary.scan(m, m::reloadAll);
                });
            } catch (Throwable t) {
                MAIN.post(() -> Toast.makeText(m, m.getString(R.string.yt_download_failed) + " (" + t.getMessage() + ")", Toast.LENGTH_SHORT).show());
            } finally {
                if (tmpV != null) tmpV.delete();
                if (tmpA != null) tmpA.delete();
                if (tmpOut != null) tmpOut.delete();
            }
        });
    }

    private static void downloadRangeToFile(String url, long knownSize, String ua, java.io.File dest) throws Exception {
        final long chunk = 524288L;
        long total = knownSize;
        long pos = 0;
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            while (true) {
                long end = total > 0 ? Math.min(total - 1, pos + chunk - 1) : (pos + chunk - 1);
                byte[] data = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    HttpURLConnection conn = null;
                    try {
                        conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(20000);
                        conn.setRequestProperty("User-Agent", ua);
                        conn.setRequestProperty("Accept-Encoding", "identity");
                        conn.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                        int code = conn.getResponseCode();
                        if (code != 206 && code != 200) throw new java.io.IOException("HTTP " + code);
                        if (total <= 0) {
                            String cr = conn.getHeaderField("Content-Range");
                            if (cr != null && cr.lastIndexOf('/') > 0) {
                                long p = parseLongSafe(cr.substring(cr.lastIndexOf('/') + 1).trim(), -1L);
                                if (p > 0) total = p;
                            } else if (code == 200 && conn.getContentLengthLong() > 0) {
                                total = conn.getContentLengthLong();
                            }
                        }
                        try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream((int) chunk)) {
                            byte[] buf = new byte[32768];
                            int r;
                            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                            data = bos.toByteArray();
                        }
                        break;
                    } catch (Exception e) {
                        if (attempt == 2) throw e;
                        Thread.sleep(250L);
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
                if (data == null || data.length == 0) break;
                fos.write(data);
                pos += data.length;
                if (total > 0 && pos >= total) break;
                if (data.length < chunk) break;
            }
        }
    }

    /** Muxes separate H.264 MP4 video + AAC M4A audio into a single standard MP4 via Android MediaMuxer. */
    private static void muxVideoAndAudio(java.io.File videoFile, java.io.File audioFile, java.io.File outFile) throws Exception {
        android.media.MediaExtractor ve = new android.media.MediaExtractor();
        android.media.MediaExtractor ae = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        try {
            ve.setDataSource(videoFile.getAbsolutePath());
            ae.setDataSource(audioFile.getAbsolutePath());
            int vSrc = -1, aSrc = -1;
            for (int i = 0; i < ve.getTrackCount(); i++) {
                String mime = ve.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { vSrc = i; break; }
            }
            for (int i = 0; i < ae.getTrackCount(); i++) {
                String mime = ae.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { aSrc = i; break; }
            }
            if (vSrc < 0 || aSrc < 0) throw new java.io.IOException("Missing video/audio track");
            ve.selectTrack(vSrc);
            ae.selectTrack(aSrc);
            muxer = new android.media.MediaMuxer(outFile.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vDst = muxer.addTrack(ve.getTrackFormat(vSrc));
            int aDst = muxer.addTrack(ae.getTrackFormat(aSrc));
            muxer.start();
            int bufSize = 2 * 1024 * 1024;
            try {
                android.media.MediaFormat vf = ve.getTrackFormat(vSrc);
                if (vf.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufSize = Math.max(bufSize, vf.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            } catch (Throwable ignored) {}
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(bufSize);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            while (true) {
                info.offset = 0;
                info.size = ve.readSampleData(buf, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = ve.getSampleTime();
                info.flags = ve.getSampleFlags();
                muxer.writeSampleData(vDst, buf, info);
                ve.advance();
            }
            while (true) {
                info.offset = 0;
                info.size = ae.readSampleData(buf, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = ae.getSampleTime();
                info.flags = ae.getSampleFlags();
                muxer.writeSampleData(aDst, buf, info);
                ae.advance();
            }
            muxer.stop();
        } finally {
            try { ve.release(); } catch (Throwable ignored) {}
            try { ae.release(); } catch (Throwable ignored) {}
            if (muxer != null) { try { muxer.release(); } catch (Throwable ignored) {} }
        }
    }

    static void loadCategory(Context c, int categoryIdx, ListCb cb) {
        String[] cats = {"all", "music", "gaming", "live", "recent", "film", "tech"};
        String[] queries = {"", "인기 뮤직비디오 2026", "게임 최신 트레일러", "실시간 스트리밍 인기", "오늘 인기 동영상", "영화 공식 예고편", "최신 스마트폰 테크 리뷰"};
        String filter = categoryIdx >= 0 && categoryIdx < cats.length ? cats[categoryIdx] : "all";
        String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        String apiKey = SecureVault.get(c, MainActivity.prefs(c), PREF_API_KEY).trim();

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
                    JSONObject resp = innertubePost(c, "browse", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 28);
                    if (!out.isEmpty()) {
                        src = isGoogleSignedIn(c) ? "YouTube 홈 (동기화됨)" : "YouTube 인기 급상승";
                    }
                } else {
                    String qStr = categoryIdx < queries.length ? queries[categoryIdx] : "";
                    JSONObject body = new JSONObject()
                            .put("query", qStr)
                            .put("context", webContext());
                    if (categoryIdx == 3) body.put("params", "EgJAAQ%3D%3D"); // Live filter
                    JSONObject resp = innertubePost(c, "search", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 24);
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
        final String apiKey = SecureVault.get(c, MainActivity.prefs(c), PREF_API_KEY).trim();

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
                JSONObject resp = innertubePost(c, "search", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                if (resp != null) collectRenderers(resp, out, new HashSet<>(), 28);
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

    static final String PREF_YT_PLAYLISTS = "yt_custom_playlists_v1";
    static final String PREF_YT_LIKED = "yt_liked_ids_v1";
    static final String PREF_YT_HISTORY = "yt_watch_history_v1";
    static final String PREF_SYNCED_SUBS = "yt_synced_subs_v1";

    static final class YtPlaylist {
        final String id;
        String title;
        final boolean system;
        boolean remoteSynced;
        String thumbUrl = "";
        String subtitle = "";
        int remoteCount = -1;
        final List<YtVideo> videos = new ArrayList<>();
        YtPlaylist(String id, String title, boolean system) {
            this.id = id;
            this.title = title;
            this.system = system;
        }
    }

    static final class ChannelInfo {
        String channelId;
        String name;
        String handle = "";
        String subscribers = "";
        String videoCount = "";
        String avatarUrl = "";
        String bannerUrl = "";
        String description = "";
        String activeTab = "videos";
        final List<YtVideo> videos = new ArrayList<>();
        final List<YtPlaylist> playlists = new ArrayList<>();
    }

    static final class WatchDetails {
        String channelId = "";
        String channelName = "";
        String channelAvatar = "";
        String subscribers = "";
        String views = "";
        String date = "";
        String likeCount = "";
        String description = "";
        String commentsCountText = "";
        String commentsNextToken = "";
        String commentsTopToken = "";
        String commentsNewestToken = "";
        final List<YtComment> comments = new ArrayList<>();
        final List<YtVideo> related = new ArrayList<>();
    }

    interface WatchCb { void onLoaded(WatchDetails details); }
    interface ChannelCb { void onLoaded(ChannelInfo info); }
    interface SubsSyncCb { void onSynced(List<SubscribedChannel> channels, boolean fromGoogle); }
    interface PlaylistsSyncCb { void onSynced(List<YtPlaylist> playlists, boolean fromGoogle); }
    interface CommentsPageCb { void onLoaded(List<YtComment> comments, String nextToken); }

    // ---- Subscriptions storage + Real InnerTube FEchannels Sync ----
    static List<String[]> getUserChannels(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Include synced Google channels first
        for (SubscribedChannel sc : getCachedSyncedChannels(c)) {
            if (seen.add(sc.channelId)) out.add(new String[]{sc.channelId, sc.name, sc.avatarUrl});
        }
        String extra = MainActivity.prefs(c).getString(PREF_SUB_CHANNELS, "");
        if (!extra.isEmpty()) {
            for (String line : extra.split("\n")) {
                String[] parts = line.trim().split("\\|", 2);
                if (parts.length >= 1 && !parts[0].isEmpty() && seen.add(parts[0].trim())) {
                    out.add(new String[]{parts[0].trim(), parts.length > 1 ? parts[1].trim() : parts[0].trim(), ""});
                }
            }
        }
        return out;
    }

    static List<SubscribedChannel> getCachedSyncedChannels(Context c) {
        List<SubscribedChannel> out = new ArrayList<>();
        try {
            String raw = MainActivity.prefs(c).getString(PREF_SYNCED_SUBS, "");
            if (!raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String cid = o.optString("id");
                    if (cid.isEmpty()) continue;
                    SubscribedChannel sc = new SubscribedChannel(cid, o.optString("name"));
                    sc.avatarUrl = o.optString("av");
                    sc.handle = o.optString("h");
                    sc.subscribers = o.optString("subs");
                    out.add(sc);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void saveSyncedChannels(Context c, List<SubscribedChannel> list) {
        try {
            JSONArray arr = new JSONArray();
            for (SubscribedChannel sc : list) {
                arr.put(new JSONObject()
                        .put("id", sc.channelId)
                        .put("name", sc.name)
                        .put("av", sc.avatarUrl)
                        .put("h", sc.handle)
                        .put("subs", sc.subscribers));
            }
            MainActivity.prefs(c).edit().putString(PREF_SYNCED_SUBS, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** Syncs the signed-in Google account's real YouTube Subscriptions channel list via FEchannels + guide. */
    static void syncSubscribedChannels(Context c, SubsSyncCb cb) {
        EXEC.execute(() -> {
            List<SubscribedChannel> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean fromGoogle = false;
            if (isGoogleSignedIn(c)) {
                try {
                    JSONObject body = new JSONObject()
                            .put("browseId", "FEchannels")
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "browse", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                    if (resp != null) collectSubscribedChannels(resp, out, seen, 80);
                    if (out.isEmpty()) {
                        JSONObject gBody = new JSONObject().put("context", webContext());
                        JSONObject gResp = innertubePost(c, "guide", gBody, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                        if (gResp != null) collectSubscribedChannels(gResp, out, seen, 80);
                    }
                    if (!out.isEmpty()) {
                        fromGoogle = true;
                        saveSyncedChannels(c, out);
                    }
                } catch (Throwable ignored) {}
            }
            if (out.isEmpty()) {
                for (String[] ch : getUserChannels(c)) {
                    if (seen.add(ch[0])) {
                        SubscribedChannel sc = new SubscribedChannel(ch[0], ch[1]);
                        if (ch.length > 2 && ch[2] != null && ch[2].startsWith("http")) sc.avatarUrl = ch[2];
                        out.add(sc);
                    }
                }
            }
            final boolean fGoogle = fromGoogle;
            MAIN.post(() -> cb.onSynced(out, fGoogle));
        });
    }

    static void collectSubscribedChannels(Object node, List<SubscribedChannel> out, Set<String> seen, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject cr = o.optJSONObject("channelRenderer");
            if (cr == null) cr = o.optJSONObject("gridChannelRenderer");
            if (cr != null) {
                String cid = cr.optString("channelId");
                if (cid.isEmpty()) cid = findFirstBrowseId(cr, "UC");
                if (cid.startsWith("UC") && seen.add(cid)) {
                    SubscribedChannel sc = new SubscribedChannel(cid, readYtText(cr.optJSONObject("title")));
                    sc.avatarUrl = findFirstImageUrl(cr.optJSONObject("thumbnail"));
                    sc.subscribers = readYtText(cr.optJSONObject("subscriberCountText"));
                    if (sc.subscribers.isEmpty()) sc.subscribers = readYtText(cr.optJSONObject("videoCountText"));
                    out.add(sc);
                }
                return;
            }
            JSONObject ge = o.optJSONObject("guideEntryRenderer");
            if (ge != null) {
                String cid = findFirstBrowseId(ge.optJSONObject("navigationEndpoint"), "UC");
                if (cid.startsWith("UC") && seen.add(cid)) {
                    String nm = readYtText(ge.optJSONObject("formattedTitle"));
                    if (nm.isEmpty()) nm = ge.optString("title");
                    SubscribedChannel sc = new SubscribedChannel(cid, nm);
                    sc.avatarUrl = findFirstImageUrl(ge.optJSONObject("thumbnail"));
                    out.add(sc);
                }
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext() && out.size() < limit) {
                collectSubscribedChannels(o.opt(keys.next()), out, seen, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < limit; i++) {
                collectSubscribedChannels(a.opt(i), out, seen, limit);
            }
        }
    }

    static boolean isSubscribed(Context c, String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) return false;
        String id = channelId.trim();
        for (String[] ch : getUserChannels(c)) if (id.equals(ch[0])) return true;
        return false;
    }

    static boolean toggleSubscribe(Context c, String channelId, String name) {
        if (channelId == null || channelId.trim().isEmpty()) return false;
        String id = channelId.trim();
        String label = (name == null || name.trim().isEmpty()) ? id : name.trim();
        boolean wasSub = isSubscribed(c, id);
        boolean nowSub = !wasSub;

        // Update local manual list
        String extra = MainActivity.prefs(c).getString(PREF_SUB_CHANNELS, "");
        StringBuilder sb = new StringBuilder();
        if (!extra.isEmpty()) {
            for (String line : extra.split("\n")) {
                String[] parts = line.trim().split("\\|", 2);
                if (parts.length >= 1 && id.equals(parts[0].trim())) continue;
                if (!line.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line.trim());
                }
            }
        }
        if (nowSub) {
            String entry = id + "|" + label;
            String next = sb.length() == 0 ? entry : entry + "\n" + sb;
            MainActivity.prefs(c).edit().putString(PREF_SUB_CHANNELS, next).apply();
        } else {
            MainActivity.prefs(c).edit().putString(PREF_SUB_CHANNELS, sb.toString()).apply();
            List<SubscribedChannel> synced = getCachedSyncedChannels(c);
            for (int i = synced.size() - 1; i >= 0; i--) {
                if (id.equals(synced.get(i).channelId)) synced.remove(i);
            }
            saveSyncedChannels(c, synced);
        }
        // Real Google account subscribe / unsubscribe sync
        if (isGoogleSignedIn(c)) {
            EXEC.execute(() -> {
                try {
                    JSONObject body = new JSONObject()
                            .put("channelIds", new JSONArray().put(id))
                            .put("context", webContext());
                    innertubePost(c, nowSub ? "subscription/subscribe" : "subscription/unsubscribe",
                            body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                } catch (Throwable ignored) {}
            });
        }
        return nowSub;
    }

    static void loadSubscriptionsFeed(Context c, ListCb cb) {
        EXEC.execute(() -> {
            List<YtVideo> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String src = "구독 피드";
            if (isGoogleSignedIn(c)) {
                try {
                    JSONObject body = new JSONObject()
                            .put("browseId", "FEsubscriptions")
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "browse", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 36);
                    if (!out.isEmpty()) src = "YouTube 구독 (동기화됨)";
                } catch (Throwable ignored) {}
            }
            if (out.isEmpty()) {
                List<String[]> chans = getUserChannels(c);
                if (chans.isEmpty()) chans = subscribedChannels(c);
                int fetched = 0;
                for (String[] ch : chans) {
                    List<YtVideo> feed = fetchChannelRss(ch[0]);
                    for (int i = 0; i < Math.min(4, feed.size()); i++) {
                        YtVideo v = feed.get(i);
                        if (seen.add(v.id)) out.add(v);
                    }
                    if (++fetched >= 8) break;
                }
                src = getUserChannels(c).isEmpty() ? "추천 채널 피드" : "구독 채널 피드";
            }
            final String fSrc = src;
            MAIN.post(() -> cb.onResult(out, fSrc));
        });
    }

    // ---- Watch History (Google FEhistory Sync + Local History) ----
    static void recordYtWatchHistory(Context c, YtVideo v) {
        if (c == null || v == null || v.id.isEmpty()) return;
        try {
            List<YtVideo> list = getLocalWatchHistory(c);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (v.id.equals(list.get(i).id)) list.remove(i);
            }
            list.add(0, v);
            while (list.size() > 60) list.remove(list.size() - 1);
            JSONArray arr = new JSONArray();
            for (YtVideo x : list) {
                arr.put(new JSONObject()
                        .put("id", x.id)
                        .put("title", x.title)
                        .put("author", x.author)
                        .put("ch", x.channelId)
                        .put("av", x.channelAvatar)
                        .put("dur", x.durationSec)
                        .put("views", x.views)
                        .put("pub", x.published));
            }
            MainActivity.prefs(c).edit().putString(PREF_YT_HISTORY, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    static List<YtVideo> getLocalWatchHistory(Context c) {
        List<YtVideo> out = new ArrayList<>();
        try {
            String raw = MainActivity.prefs(c).getString(PREF_YT_HISTORY, "");
            if (!raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id");
                    if (id.isEmpty()) continue;
                    YtVideo v = new YtVideo(id, o.optString("title"), o.optString("author"));
                    v.channelId = o.optString("ch");
                    v.channelAvatar = o.optString("av");
                    v.durationSec = o.optLong("dur", 0);
                    v.views = o.optString("views");
                    v.published = o.optString("pub");
                    out.add(v);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static void loadWatchHistory(Context c, ListCb cb) {
        EXEC.execute(() -> {
            List<YtVideo> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String src = "시청 기록";
            if (isGoogleSignedIn(c)) {
                try {
                    JSONObject body = new JSONObject()
                            .put("browseId", "FEhistory")
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "browse", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 40);
                    if (!out.isEmpty()) src = "YouTube 시청 기록 (Google 동기화)";
                } catch (Throwable ignored) {}
            }
            for (YtVideo local : getLocalWatchHistory(c)) {
                if (seen.add(local.id)) out.add(local);
            }
            if (src.equals("시청 기록") && !out.isEmpty()) src = "최근 시청한 YouTube 동영상";
            final String fSrc = src;
            MAIN.post(() -> cb.onResult(out, fSrc));
        });
    }

    // ---- Authentic Full YouTube Channel Detail (Header + 5 Real Channel Tabs) ----
    static String channelTabParams(String tabKey) {
        if ("home".equals(tabKey)) return "EgRob21l";
        if ("shorts".equals(tabKey)) return "EgZzaG9ydHPyBgUKA5oBAA%3D%3D";
        if ("live".equals(tabKey)) return "EgdzdHJlYW1z8gYECgJ6AA%3D%3D";
        if ("playlists".equals(tabKey)) return "EglwbGF5bGlzdHPyBgQKAkIA";
        return "EgZ2aWRlb3PyBgQKAjoA"; // videos
    }

    static void loadChannelDetail(Context c, String channelId, String fallbackName, ChannelCb cb) {
        loadChannelDetail(c, channelId, fallbackName, "videos", cb);
    }

    static void loadChannelDetail(Context c, String channelId, String fallbackName, String tabKey, ChannelCb cb) {
        EXEC.execute(() -> {
            ChannelInfo info = new ChannelInfo();
            info.channelId = channelId == null ? "" : channelId.trim();
            info.name = (fallbackName == null || fallbackName.isEmpty()) ? "채널" : fallbackName;
            info.activeTab = tabKey == null ? "videos" : tabKey;
            Set<String> seen = new HashSet<>();
            if (!info.channelId.isEmpty()) {
                try {
                    JSONObject body = new JSONObject()
                            .put("browseId", info.channelId)
                            .put("params", channelTabParams(info.activeTab))
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "browse", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                    if (resp != null) {
                        parseChannelResponse(resp, info, seen);
                    }
                } catch (Throwable ignored) {}
                if (info.videos.isEmpty() && !"playlists".equals(info.activeTab)) {
                    List<YtVideo> rss = fetchChannelRss(info.channelId);
                    for (YtVideo v : rss) {
                        if (seen.add(v.id)) info.videos.add(v);
                    }
                    if (!rss.isEmpty() && !rss.get(0).author.isEmpty()) info.name = rss.get(0).author;
                }
            }
            MAIN.post(() -> cb.onLoaded(info));
        });
    }

    static void parseChannelResponse(JSONObject resp, ChannelInfo info, Set<String> seen) {
        if (resp == null) return;
        JSONObject meta = resp.optJSONObject("metadata");
        JSONObject cm = meta == null ? null : meta.optJSONObject("channelMetadataRenderer");
        if (cm != null) {
            String t = cm.optString("title");
            if (!t.isEmpty()) info.name = t;
            String d = cm.optString("description");
            if (!d.isEmpty()) info.description = d;
            String av = findFirstImageUrl(cm.optJSONObject("avatar"));
            if (!av.isEmpty()) info.avatarUrl = av;
            String van = cm.optString("vanityChannelUrl");
            if (van.contains("/@")) info.handle = van.substring(van.indexOf("/@") + 1);
        }
        JSONObject hdr = resp.optJSONObject("header");
        if (hdr != null) {
            // Modern pageHeaderRenderer -> content -> pageHeaderViewModel
            JSONObject phr = hdr.optJSONObject("pageHeaderRenderer");
            if (phr != null) {
                JSONObject cnt = phr.optJSONObject("content");
                JSONObject vm = cnt == null ? null : cnt.optJSONObject("pageHeaderViewModel");
                if (vm != null) {
                    String ban = findFirstImageUrl(vm.optJSONObject("banner"));
                    if (!ban.isEmpty()) info.bannerUrl = ban;
                    String av = findFirstImageUrl(vm.optJSONObject("image"));
                    if (!av.isEmpty()) info.avatarUrl = av;
                    String desc = findFirstStringByKey(vm.optJSONObject("description"), "content");
                    if (!desc.isEmpty() && info.description.isEmpty()) info.description = desc;
                    // Metadata rows: @handle, subscribers, video count
                    JSONObject mObj = vm.optJSONObject("metadata");
                    JSONObject cmm = mObj == null ? null : mObj.optJSONObject("contentMetadataViewModel");
                    JSONArray rows = cmm == null ? null : cmm.optJSONArray("metadataRows");
                    if (rows != null) {
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.optJSONObject(i);
                            JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                            if (parts == null) continue;
                            for (int j = 0; j < parts.length(); j++) {
                                JSONObject pt = parts.optJSONObject(j);
                                JSONObject tx = pt == null ? null : pt.optJSONObject("text");
                                String s = tx == null ? "" : tx.optString("content");
                                if (s.isEmpty()) continue;
                                if (s.startsWith("@") && info.handle.isEmpty()) info.handle = s;
                                else if ((s.contains("구독자") || s.toLowerCase(Locale.US).contains("subscriber")) && info.subscribers.isEmpty()) info.subscribers = s;
                                else if ((s.contains("동영상") || s.toLowerCase(Locale.US).contains("video")) && info.videoCount.isEmpty()) info.videoCount = s;
                            }
                        }
                    }
                }
            }
            // Classic c4TabbedHeaderRenderer
            JSONObject c4 = hdr.optJSONObject("c4TabbedHeaderRenderer");
            if (c4 != null) {
                if (info.bannerUrl.isEmpty()) info.bannerUrl = findFirstImageUrl(c4.optJSONObject("banner"));
                if (info.avatarUrl.isEmpty()) info.avatarUrl = findFirstImageUrl(c4.optJSONObject("avatar"));
                if (info.handle.isEmpty()) info.handle = readYtText(c4.optJSONObject("channelHandleText"));
                if (info.subscribers.isEmpty()) info.subscribers = readYtText(c4.optJSONObject("subscriberCountText"));
                if (info.videoCount.isEmpty()) info.videoCount = readYtText(c4.optJSONObject("videosCountText"));
            }
            if (info.subscribers.isEmpty()) info.subscribers = findSubscriberText(hdr);
        }
        if ("playlists".equals(info.activeTab)) {
            collectPlaylistRenderers(resp, info.playlists, new HashSet<>(), 30);
        } else {
            collectRenderers(resp, info.videos, seen, 30);
            for (YtVideo v : info.videos) {
                if (v.author.isEmpty() || "YouTube".equals(v.author) || "Shorts".equals(v.author)) v.author = info.name;
                if (v.channelId.isEmpty()) v.channelId = info.channelId;
                if (v.channelAvatar.isEmpty()) v.channelAvatar = info.avatarUrl;
            }
        }
    }

    private static String findSubscriberText(Object node) {
        if (node == null) return "";
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String s = readYtText(o.optJSONObject("subscriberCountText"));
            if (!s.isEmpty()) return s;
            String content = o.optString("content");
            if (content.contains("구독자") || content.toLowerCase(Locale.US).contains("subscriber")) return content;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String res = findSubscriberText(o.opt(keys.next()));
                if (!res.isEmpty()) return res;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String res = findSubscriberText(a.opt(i));
                if (!res.isEmpty()) return res;
            }
        }
        return "";
    }

    // ---- YouTube Playlists & Liked Videos (Local + Real Google FElibrary / VL* Sync) ----
    static List<YtPlaylist> getYtPlaylists(Context c) {
        List<YtPlaylist> out = new ArrayList<>();
        Map<String, YtPlaylist> byId = new java.util.LinkedHashMap<>();
        YtPlaylist wl = new YtPlaylist("WL", c.getString(R.string.yt_watch_later), true);
        YtPlaylist ll = new YtPlaylist("LL", c.getString(R.string.yt_liked_videos), true);
        byId.put("WL", wl);
        byId.put("LL", ll);
        try {
            String raw = MainActivity.prefs(c).getString(PREF_YT_PLAYLISTS, "");
            if (!raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id");
                    String title = o.optString("title");
                    boolean sys = o.optBoolean("sys", false);
                    YtPlaylist pl = byId.get(id);
                    if (pl == null) {
                        pl = new YtPlaylist(id, title, sys);
                        byId.put(id, pl);
                    }
                    pl.remoteSynced = o.optBoolean("rem", false);
                    pl.thumbUrl = o.optString("th");
                    pl.subtitle = o.optString("sub");
                    pl.remoteCount = o.optInt("rc", -1);
                    JSONArray vs = o.optJSONArray("videos");
                    if (vs != null) {
                        for (int j = 0; j < vs.length(); j++) {
                            JSONObject vo = vs.optJSONObject(j);
                            if (vo == null) continue;
                            YtVideo v = new YtVideo(vo.optString("id"), vo.optString("title"), vo.optString("author"));
                            v.channelId = vo.optString("ch");
                            v.channelAvatar = vo.optString("av");
                            v.durationSec = vo.optLong("dur", 0);
                            v.views = vo.optString("views");
                            pl.videos.add(v);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        out.addAll(byId.values());
        return out;
    }

    /** Syncs user's Google YouTube playlists (FElibrary + FEplaylist_aggregations) and merges with local playlists. */
    static void syncUserPlaylists(Context c, PlaylistsSyncCb cb) {
        EXEC.execute(() -> {
            List<YtPlaylist> local = getYtPlaylists(c);
            Map<String, YtPlaylist> merged = new java.util.LinkedHashMap<>();
            for (YtPlaylist p : local) merged.put(p.id, p);
            boolean fromGoogle = false;

            if (isGoogleSignedIn(c)) {
                List<YtPlaylist> remote = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                try {
                    for (String bid : new String[]{"FEplaylist_aggregations", "FElibrary"}) {
                        JSONObject body = new JSONObject()
                                .put("browseId", bid)
                                .put("context", webContext());
                        JSONObject resp = innertubePost(c, "browse", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                        if (resp != null) collectPlaylistRenderers(resp, remote, seen, 40);
                    }
                    if (!remote.isEmpty()) {
                        fromGoogle = true;
                        for (YtPlaylist rp : remote) {
                            YtPlaylist ex = merged.get(rp.id);
                            if (ex != null) {
                                if (!rp.title.isEmpty() && !ex.system) ex.title = rp.title;
                                ex.remoteSynced = true;
                                if (!rp.thumbUrl.isEmpty()) ex.thumbUrl = rp.thumbUrl;
                                if (!rp.subtitle.isEmpty()) ex.subtitle = rp.subtitle;
                                if (rp.remoteCount >= 0) ex.remoteCount = rp.remoteCount;
                            } else {
                                rp.remoteSynced = true;
                                merged.put(rp.id, rp);
                            }
                        }
                        // Also mark WL and LL as Google-synced so opening them pulls live remote items
                        if (merged.containsKey("WL")) merged.get("WL").remoteSynced = true;
                        if (merged.containsKey("LL")) merged.get("LL").remoteSynced = true;
                        saveYtPlaylists(c, new ArrayList<>(merged.values()));
                    }
                } catch (Throwable ignored) {}
            }
            List<YtPlaylist> finalOut = new ArrayList<>(merged.values());
            final boolean fGoogle = fromGoogle;
            MAIN.post(() -> cb.onSynced(finalOut, fGoogle));
        });
    }

    static void collectPlaylistRenderers(Object node, List<YtPlaylist> out, Set<String> seen, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject pr = o.optJSONObject("gridPlaylistRenderer");
            if (pr == null) pr = o.optJSONObject("playlistRenderer");
            if (pr != null) {
                String pid = pr.optString("playlistId");
                if (!pid.isEmpty() && seen.add(pid)) {
                    YtPlaylist pl = new YtPlaylist(pid, readYtText(pr.optJSONObject("title")), "WL".equals(pid) || "LL".equals(pid));
                    pl.remoteSynced = true;
                    pl.thumbUrl = findFirstImageUrl(pr.optJSONObject("thumbnail"));
                    String vc = readYtText(pr.optJSONObject("videoCountText"));
                    if (vc.isEmpty()) vc = pr.optString("videoCount");
                    pl.subtitle = vc;
                    try {
                        String digits = vc.replaceAll("\\D", "");
                        if (!digits.isEmpty()) pl.remoteCount = Integer.parseInt(digits);
                    } catch (Throwable ignored) {}
                    out.add(pl);
                }
                return;
            }
            JSONObject lvm = o.optJSONObject("lockupViewModel");
            if (lvm != null && "LOCKUP_CONTENT_TYPE_PLAYLIST".equals(lvm.optString("contentType"))) {
                String pid = lvm.optString("contentId");
                if (pid.startsWith("VL")) pid = pid.substring(2);
                if (!pid.isEmpty() && seen.add(pid)) {
                    String title = findFirstStringByKey(lvm.optJSONObject("metadata"), "content");
                    if (!title.isEmpty()) {
                        YtPlaylist pl = new YtPlaylist(pid, title, "WL".equals(pid) || "LL".equals(pid));
                        pl.remoteSynced = true;
                        pl.thumbUrl = findFirstImageUrl(lvm.optJSONObject("contentImage"));
                        pl.subtitle = findFirstStringByKey(lvm.optJSONObject("contentImage"), "text");
                        out.add(pl);
                    }
                }
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext() && out.size() < limit) {
                collectPlaylistRenderers(o.opt(keys.next()), out, seen, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < limit; i++) {
                collectPlaylistRenderers(a.opt(i), out, seen, limit);
            }
        }
    }

    /** Loads real videos inside a YouTube playlist (WL, LL, PL..., RD...) from Google InnerTube and merges local items. */
    static void loadPlaylistVideos(Context c, String playlistId, ListCb cb) {
        EXEC.execute(() -> {
            List<YtVideo> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String src = "로컬 재생목록";
            if (playlistId != null && !playlistId.startsWith("PL_")) {
                try {
                    String bid = playlistId.startsWith("VL") ? playlistId : ("VL" + playlistId);
                    JSONObject body = new JSONObject()
                            .put("browseId", bid)
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "browse", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 50);
                    if (!out.isEmpty()) src = "YouTube 재생목록 (동기화됨)";
                } catch (Throwable ignored) {}
            }
            for (YtPlaylist pl : getYtPlaylists(c)) {
                if (pl.id.equals(playlistId)) {
                    for (YtVideo v : pl.videos) {
                        if (seen.add(v.id)) out.add(v);
                    }
                    break;
                }
            }
            final String fSrc = src;
            MAIN.post(() -> cb.onResult(out, fSrc));
        });
    }

    private static void saveYtPlaylists(Context c, List<YtPlaylist> list) {
        try {
            JSONArray arr = new JSONArray();
            for (YtPlaylist pl : list) {
                JSONObject o = new JSONObject();
                o.put("id", pl.id);
                o.put("title", pl.title);
                o.put("sys", pl.system);
                o.put("rem", pl.remoteSynced);
                o.put("th", pl.thumbUrl);
                o.put("sub", pl.subtitle);
                o.put("rc", pl.remoteCount);
                JSONArray vs = new JSONArray();
                for (YtVideo v : pl.videos) {
                    vs.put(new JSONObject()
                            .put("id", v.id)
                            .put("title", v.title)
                            .put("author", v.author)
                            .put("ch", v.channelId)
                            .put("av", v.channelAvatar)
                            .put("dur", v.durationSec)
                            .put("views", v.views));
                }
                o.put("videos", vs);
                arr.put(o);
            }
            MainActivity.prefs(c).edit().putString(PREF_YT_PLAYLISTS, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    static YtPlaylist createYtPlaylist(Context c, String title) {
        List<YtPlaylist> list = getYtPlaylists(c);
        YtPlaylist pl = new YtPlaylist("PL_" + System.currentTimeMillis(), title.trim(), false);
        list.add(pl);
        saveYtPlaylists(c, list);
        return pl;
    }

    static void addToYtPlaylist(Context c, String playlistId, YtVideo v) {
        if (v == null || v.id.isEmpty()) return;
        List<YtPlaylist> list = getYtPlaylists(c);
        for (YtPlaylist pl : list) {
            if (pl.id.equals(playlistId)) {
                for (int i = pl.videos.size() - 1; i >= 0; i--) {
                    if (pl.videos.get(i).id.equals(v.id)) pl.videos.remove(i);
                }
                pl.videos.add(0, v);
                break;
            }
        }
        saveYtPlaylists(c, list);
        // Sync addition back to Google YouTube playlist (WL or real PL...) when signed in
        if (isGoogleSignedIn(c) && playlistId != null && !playlistId.startsWith("PL_") && !"LL".equals(playlistId)) {
            EXEC.execute(() -> {
                try {
                    JSONObject body = new JSONObject()
                            .put("playlistId", playlistId)
                            .put("actions", new JSONArray().put(new JSONObject()
                                    .put("action", "ACTION_ADD_VIDEO")
                                    .put("addedVideoId", v.id)))
                            .put("context", webContext());
                    innertubePost(c, "browse/edit_playlist", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                } catch (Throwable ignored) {}
            });
        }
    }

    static void removeFromYtPlaylist(Context c, String playlistId, String videoId) {
        List<YtPlaylist> list = getYtPlaylists(c);
        for (YtPlaylist pl : list) {
            if (pl.id.equals(playlistId)) {
                for (int i = pl.videos.size() - 1; i >= 0; i--) {
                    if (pl.videos.get(i).id.equals(videoId)) pl.videos.remove(i);
                }
                break;
            }
        }
        saveYtPlaylists(c, list);
    }

    static boolean isVideoLiked(Context c, String videoId) {
        if (videoId == null || videoId.isEmpty()) return false;
        for (YtPlaylist pl : getYtPlaylists(c)) {
            if ("LL".equals(pl.id)) {
                for (YtVideo v : pl.videos) if (videoId.equals(v.id)) return true;
            }
        }
        return false;
    }

    static boolean toggleLikeVideo(Context c, YtVideo v) {
        if (v == null || v.id.isEmpty()) return false;
        boolean liked = isVideoLiked(c, v.id);
        if (liked) removeFromYtPlaylist(c, "LL", v.id);
        else addToYtPlaylist(c, "LL", v);
        if (isGoogleSignedIn(c)) {
            EXEC.execute(() -> {
                try {
                    JSONObject body = new JSONObject()
                            .put("target", new JSONObject().put("videoId", v.id))
                            .put("context", webContext());
                    innertubePost(c, liked ? "like/removelike" : "like/like",
                            body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                } catch (Throwable ignored) {}
            });
        }
        return !liked;
    }

    static void showSaveToPlaylistSheet(MainActivity m, YtVideo v, View anchor) {
        List<YtPlaylist> pls = getYtPlaylists(m);
        String[] labels = new String[pls.size() + 1];
        for (int i = 0; i < pls.size(); i++) {
            int cnt = pls.get(i).remoteCount >= 0 ? pls.get(i).remoteCount : pls.get(i).videos.size();
            labels[i] = pls.get(i).title + (cnt > 0 ? " (" + cnt + ")" : "");
        }
        labels[pls.size()] = "+ 새 YouTube 재생목록";
        Widgets.pickSheet(m, anchor, m.getString(R.string.add_to_playlist), labels, -1, idx -> {
            if (idx == pls.size()) {
                LinearLayout form = new LinearLayout(m);
                form.setOrientation(LinearLayout.VERTICAL);
                EditText et = Widgets.formField(m, "재생목록 이름", "새 재생목록 이름 입력", "", InputType.TYPE_CLASS_TEXT, form);
                Widgets.showFormSheet(m, "새 YouTube 재생목록", form, m.getString(R.string.ok), () -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) {
                        YtPlaylist pl = createYtPlaylist(m, n);
                        addToYtPlaylist(m, pl.id, v);
                        Toast.makeText(m, pl.title + "에 추가했습니다", Toast.LENGTH_SHORT).show();
                        m.reloadAll();
                    }
                });
            } else {
                YtPlaylist pl = pls.get(idx);
                addToYtPlaylist(m, pl.id, v);
                Toast.makeText(m, pl.title + "에 추가했습니다", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Loads rich watch metadata, full InnerTube comments (with sort/replies/pagination tokens), and Up Next videos! */
    static void loadWatchDetails(Context c, YtVideo v, WatchCb cb) {
        final String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        final String apiKey = SecureVault.get(c, MainActivity.prefs(c), PREF_API_KEY).trim();
        EXEC.execute(() -> {
            WatchDetails d = new WatchDetails();
            d.channelId = v.channelId == null ? "" : v.channelId;
            d.channelName = v.author == null ? "" : v.author;
            d.channelAvatar = v.channelAvatar == null ? "" : v.channelAvatar;
            d.views = v.views == null ? "" : v.views;
            d.date = v.published == null ? "" : v.published;
            d.description = v.description == null ? "" : v.description;

            if (v.title.startsWith("YouTube (")) {
                YtVideo oe = fetchOEmbed(v.id);
                v.title = oe.title;
                if (v.author.isEmpty() || "YouTube".equals(v.author)) v.author = oe.author;
                d.channelName = v.author;
            }

            // 1) Direct InnerTube /next for metadata, channel info, Up Next related videos, and comments continuation token
            try {
                JSONObject body = new JSONObject()
                        .put("videoId", v.id)
                        .put("context", webContext());
                JSONObject resp = innertubePost(c, "next", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                if (resp != null) {
                    extractWatchMeta(resp, d);
                    if (!d.channelId.isEmpty()) v.channelId = d.channelId;
                    if (!d.channelName.isEmpty()) v.author = d.channelName;
                    if (!d.channelAvatar.isEmpty()) v.channelAvatar = d.channelAvatar;
                    if (!d.views.isEmpty()) v.views = d.views;
                    if (!d.date.isEmpty()) v.published = d.date;
                    if (!d.description.isEmpty()) v.description = d.description;

                    Set<String> seenRel = new HashSet<>();
                    seenRel.add(v.id);
                    collectRenderers(resp, d.related, seenRel, 16);

                    String commentsToken = findCommentsContinuation(resp);
                    if (commentsToken != null && !commentsToken.isEmpty()) {
                        JSONObject cBody = new JSONObject()
                                .put("continuation", commentsToken)
                                .put("context", webContext());
                        JSONObject cResp = innertubePost(c, "next", cBody, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                        if (cResp != null) {
                            String[] nextTok = new String[]{""};
                            String[] sortToks = new String[]{"", ""};
                            parseCommentsResponse(cResp, d.comments, nextTok, sortToks, d);
                            d.commentsNextToken = nextTok[0];
                            d.commentsTopToken = sortToks[0];
                            d.commentsNewestToken = sortToks[1];
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2) Fallback: YouTube Data API v3 or Piped if InnerTube comments came back empty
            if (d.comments.isEmpty() && !apiKey.isEmpty()) {
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
                                    YtComment cm = new YtComment(
                                            sn.optString("authorDisplayName"),
                                            sn.optString("textOriginal", sn.optString("textDisplay")),
                                            sn.optString("publishedAt"),
                                            String.valueOf(sn.optInt("likeCount", 0)));
                                    cm.authorAvatar = sn.optString("authorProfileImageUrl");
                                    cm.replyCount = top.optInt("totalReplyCount", 0);
                                    d.comments.add(cm);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (d.comments.isEmpty() && !pipedBase.isEmpty()) {
                try {
                    String base = pipedBase.endsWith("/") ? pipedBase.substring(0, pipedBase.length() - 1) : pipedBase;
                    String json = httpGet(base + "/comments/" + v.id, 4000);
                    if (json != null) {
                        JSONArray arr = new JSONObject(json).optJSONArray("comments");
                        if (arr != null) {
                            for (int i = 0; i < Math.min(25, arr.length()); i++) {
                                JSONObject o = arr.getJSONObject(i);
                                YtComment cm = new YtComment(
                                        o.optString("author"),
                                        o.optString("commentText"),
                                        o.optString("commentedTime"),
                                        String.valueOf(o.optInt("likeCount", 0)));
                                cm.authorAvatar = o.optString("thumbnail");
                                cm.isPinned = o.optBoolean("pinned", false);
                                cm.isHearted = o.optBoolean("hearted", false);
                                cm.replyCount = o.optInt("replyCount", 0);
                                d.comments.add(cm);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            MAIN.post(() -> cb.onLoaded(d));
        });
    }

    /** Loads next page of comments OR sorted comments (인기순/최신순) OR comment replies via InnerTube /next continuation. */
    static void loadCommentsContinuation(Context c, String token, CommentsPageCb cb) {
        if (token == null || token.isEmpty()) {
            MAIN.post(() -> cb.onLoaded(new ArrayList<>(), ""));
            return;
        }
        EXEC.execute(() -> {
            List<YtComment> out = new ArrayList<>();
            String[] nextTok = new String[]{""};
            try {
                JSONObject body = new JSONObject()
                        .put("continuation", token)
                        .put("context", webContext());
                JSONObject resp = innertubePost(c, "next", body, "1", WEB_CLIENT_VERSION, WEB_UA, true);
                if (resp != null) parseCommentsResponse(resp, out, nextTok, null, null);
            } catch (Throwable ignored) {}
            final String fNext = nextTok[0];
            MAIN.post(() -> cb.onLoaded(out, fNext));
        });
    }

    private static void extractWatchMeta(Object node, WatchDetails d) {
        if (node == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject pri = o.optJSONObject("videoPrimaryInfoRenderer");
            if (pri != null) {
                JSONObject vc = pri.optJSONObject("viewCount");
                if (vc != null) {
                    JSONObject vcr = vc.optJSONObject("videoViewCountRenderer");
                    if (vcr != null) {
                        String vw = readYtText(vcr.optJSONObject("shortViewCount"));
                        if (vw.isEmpty()) vw = readYtText(vcr.optJSONObject("viewCount"));
                        if (!vw.isEmpty()) d.views = vw;
                    }
                }
                String dt = readYtText(pri.optJSONObject("dateText"));
                if (!dt.isEmpty()) d.date = dt;
            }
            JSONObject sec = o.optJSONObject("videoSecondaryInfoRenderer");
            if (sec != null) {
                JSONObject owner = sec.optJSONObject("owner");
                JSONObject vor = owner == null ? null : owner.optJSONObject("videoOwnerRenderer");
                if (vor != null) {
                    String nm = readYtText(vor.optJSONObject("title"));
                    if (!nm.isEmpty()) d.channelName = nm;
                    String sub = readYtText(vor.optJSONObject("subscriberCountText"));
                    if (!sub.isEmpty()) d.subscribers = sub;
                    String av = findFirstImageUrl(vor.optJSONObject("thumbnail"));
                    if (!av.isEmpty()) d.channelAvatar = av;
                    JSONObject nav = vor.optJSONObject("navigationEndpoint");
                    JSONObject br = nav == null ? null : nav.optJSONObject("browseEndpoint");
                    String bid = br == null ? "" : br.optString("browseId");
                    if (bid.startsWith("UC")) d.channelId = bid;
                }
                String desc = readYtText(sec.optJSONObject("attributedDescription"));
                if (desc.isEmpty()) {
                    JSONObject ad = sec.optJSONObject("attributedDescription");
                    if (ad != null) desc = ad.optString("content");
                }
                if (desc.isEmpty()) desc = readYtText(sec.optJSONObject("description"));
                if (!desc.isEmpty()) d.description = desc;
            }
            JSONObject epHeader = o.optJSONObject("engagementPanelTitleHeaderRenderer");
            if (epHeader != null && d.commentsCountText.isEmpty()) {
                String t = readYtText(epHeader.optJSONObject("title"));
                if (t.contains("댓글") || t.toLowerCase(Locale.US).contains("comment")) {
                    String cnt = readYtText(epHeader.optJSONObject("contextualInfo"));
                    if (!cnt.isEmpty()) d.commentsCountText = cnt;
                }
            }
            JSONObject likeBtn = o.optJSONObject("likeButtonViewModel");
            if (likeBtn != null && d.likeCount.isEmpty()) {
                String t = findFirstStringByKey(likeBtn, "title");
                if (!t.isEmpty() && !t.equals("좋아요")) d.likeCount = t;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) extractWatchMeta(o.opt(keys.next()), d);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) extractWatchMeta(a.opt(i), d);
        }
    }

    /**
     * Parses YouTube InnerTube comments JSON (both modern commentEntityPayload + commentThreadRenderer
     * with reply continuation tokens and legacy commentRenderer), plus sort tokens and next-page pagination token.
     */
    static void parseCommentsResponse(JSONObject resp, List<YtComment> out, String[] nextTokenOut,
                                      String[] sortTokensOut, WatchDetails metaOut) {
        if (resp == null) return;
        // 1) Collect reply tokens and pinned/thread metadata indexed by commentId from commentThreadRenderer
        Map<String, String> replyTokensByKey = new HashMap<>();
        Map<String, Boolean> pinnedByKey = new HashMap<>();
        List<String> threadOrder = new ArrayList<>();
        collectCommentThreads(resp, replyTokensByKey, pinnedByKey, threadOrder, nextTokenOut, sortTokensOut, metaOut);

        // 2) Collect modern commentEntityPayload keyed by commentId/key
        Map<String, YtComment> entityMap = new java.util.LinkedHashMap<>();
        Map<String, Boolean> heartedByToolbarKey = new HashMap<>();
        collectCommentEntities(resp, entityMap, heartedByToolbarKey);

        if (!entityMap.isEmpty()) {
            // Preserve thread order if present, then any remaining
            Set<String> emitted = new HashSet<>();
            for (String key : threadOrder) {
                YtComment cm = entityMap.get(key);
                if (cm != null && emitted.add(key)) {
                    if (replyTokensByKey.containsKey(key)) cm.replyToken = replyTokensByKey.get(key);
                    if (Boolean.TRUE.equals(pinnedByKey.get(key))) cm.isPinned = true;
                    out.add(cm);
                }
            }
            for (Map.Entry<String, YtComment> e : entityMap.entrySet()) {
                if (emitted.add(e.getKey())) {
                    YtComment cm = e.getValue();
                    if (replyTokensByKey.containsKey(e.getKey())) cm.replyToken = replyTokensByKey.get(e.getKey());
                    if (Boolean.TRUE.equals(pinnedByKey.get(e.getKey()))) cm.isPinned = true;
                    out.add(cm);
                }
            }
            return;
        }

        // 3) Fallback: legacy commentRenderer
        collectLegacyComments(resp, out, 40);
    }

    private static void collectCommentThreads(Object node, Map<String, String> replyToks,
                                              Map<String, Boolean> pinned, List<String> order,
                                              String[] nextTok, String[] sortToks, WatchDetails metaOut) {
        if (node == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject hdr = o.optJSONObject("commentsHeaderRenderer");
            if (hdr != null) {
                if (metaOut != null && metaOut.commentsCountText.isEmpty()) {
                    String ct = readYtText(hdr.optJSONObject("countText"));
                    if (!ct.isEmpty()) metaOut.commentsCountText = ct;
                }
                if (sortToks != null) {
                    JSONObject sm = hdr.optJSONObject("sortMenu");
                    JSONObject sfm = sm == null ? null : sm.optJSONObject("sortFilterSubMenuRenderer");
                    JSONArray items = sfm == null ? null : sfm.optJSONArray("subMenuItems");
                    if (items != null) {
                        for (int i = 0; i < items.length() && i < 2; i++) {
                            JSONObject it = items.optJSONObject(i);
                            String tok = findFirstStringByKey(it, "token");
                            if (!tok.isEmpty()) sortToks[i] = tok;
                        }
                    }
                }
            }
            JSONObject ctr = o.optJSONObject("commentThreadRenderer");
            if (ctr != null) {
                JSONObject cvm = ctr.optJSONObject("commentViewModel");
                JSONObject innerVm = cvm == null ? null : cvm.optJSONObject("commentViewModel");
                String key = innerVm != null ? innerVm.optString("commentId") : "";
                if (key.isEmpty() && innerVm != null) key = innerVm.optString("commentKey");
                if (key.isEmpty()) {
                    JSONObject cm = ctr.optJSONObject("comment");
                    JSONObject cr = cm == null ? null : cm.optJSONObject("commentRenderer");
                    if (cr != null) key = cr.optString("commentId");
                }
                if (!key.isEmpty()) {
                    order.add(key);
                    if (innerVm != null && innerVm.has("pinnedText")) pinned.put(key, true);
                    JSONObject reps = ctr.optJSONObject("replies");
                    if (reps != null) {
                        String rTok = findFirstStringByKey(reps, "token");
                        if (!rTok.isEmpty()) replyToks.put(key, rTok);
                    }
                }
                return;
            }
            // Next-page continuation token (top-level continuationItemRenderer, NOT inside replies)
            JSONObject cir = o.optJSONObject("continuationItemRenderer");
            if (cir != null && nextTok != null && nextTok[0].isEmpty()) {
                String tok = findFirstStringByKey(cir, "token");
                if (!tok.isEmpty()) nextTok[0] = tok;
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                collectCommentThreads(o.opt(keys.next()), replyToks, pinned, order, nextTok, sortToks, metaOut);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                collectCommentThreads(a.opt(i), replyToks, pinned, order, nextTok, sortToks, metaOut);
            }
        }
    }

    private static void collectCommentEntities(Object node, Map<String, YtComment> out, Map<String, Boolean> hearted) {
        if (node == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject cep = o.optJSONObject("commentEntityPayload");
            if (cep != null) {
                JSONObject props = cep.optJSONObject("properties");
                JSONObject author = cep.optJSONObject("author");
                JSONObject toolbar = cep.optJSONObject("toolbar");
                JSONObject avatar = cep.optJSONObject("avatar");
                JSONObject content = props == null ? null : props.optJSONObject("content");
                String txt = content == null ? "" : content.optString("content");
                String au = author == null ? "" : author.optString("displayName");
                String time = props == null ? "" : props.optString("publishedTime");
                String likes = toolbar == null ? "" : toolbar.optString("likeCountNotliked");
                String cid = props == null ? "" : props.optString("commentId");
                if (cid.isEmpty()) cid = cep.optString("key");
                if (cid.isEmpty()) cid = "c_" + out.size();
                if (!txt.isEmpty()) {
                    YtComment cm = new YtComment(au, txt, time, likes);
                    cm.authorAvatar = author != null ? author.optString("avatarThumbnailUrl") : "";
                    if (cm.authorAvatar.isEmpty() && avatar != null) cm.authorAvatar = findFirstImageUrl(avatar);
                    cm.isOwner = author != null && author.optBoolean("isCreator", false);
                    cm.isHearted = toolbar != null && !toolbar.optString("heartActiveTooltip").isEmpty();
                    String rc = toolbar == null ? "" : toolbar.optString("replyCount");
                    if (!rc.isEmpty()) {
                        try { cm.replyCount = Integer.parseInt(rc.replaceAll("\\D", "")); } catch (Throwable ignored) {}
                    }
                    out.put(cid, cm);
                }
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) collectCommentEntities(o.opt(keys.next()), out, hearted);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectCommentEntities(a.opt(i), out, hearted);
        }
    }

    private static void collectLegacyComments(Object node, List<YtComment> out, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject cr = o.optJSONObject("commentRenderer");
            if (cr != null) {
                String au = readYtText(cr.optJSONObject("authorText"));
                String txt = readYtText(cr.optJSONObject("contentText"));
                String time = readYtText(cr.optJSONObject("publishedTimeText"));
                String likes = readYtText(cr.optJSONObject("voteCount"));
                if (!txt.isEmpty()) {
                    YtComment cm = new YtComment(au, txt, time, likes);
                    cm.authorAvatar = findFirstImageUrl(cr.optJSONObject("authorThumbnail"));
                    cm.isOwner = cr.optBoolean("authorIsChannelOwner", false);
                    cm.isPinned = cr.has("pinnedCommentBadge");
                    cm.replyCount = cr.optInt("replyCount", 0);
                    out.add(cm);
                }
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext() && out.size() < limit) collectLegacyComments(o.opt(keys.next()), out, limit);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < limit; i++) collectLegacyComments(a.opt(i), out, limit);
        }
    }

    private static String findFirstStringByKey(Object node, String targetKey) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String v = o.optString(targetKey);
            if (!v.isEmpty()) return v;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String r = findFirstStringByKey(o.opt(keys.next()), targetKey);
                if (!r.isEmpty()) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findFirstStringByKey(a.opt(i), targetKey);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private static String findCommentsContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String targetId = o.optString("targetId");
            String sectionId = o.optString("sectionIdentifier");
            if ("comment-item-section".equals(sectionId) || "engagement-panel-comments-section".equals(targetId)) {
                String tok = findFirstStringByKey(o, "token");
                if (!tok.isEmpty()) return tok;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String r = findCommentsContinuation(o.opt(keys.next()));
                if (r != null && !r.isEmpty()) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findCommentsContinuation(a.opt(i));
                if (r != null && !r.isEmpty()) return r;
            }
        }
        return null;
    }

    static void loadCommentsAndMeta(Context c, YtVideo v, CommentsCb cb) {
        loadWatchDetails(c, v, d -> cb.onResult(d.comments, d.description));
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

    private static void loadImageUrl(String urlStr, ThumbCb cb) {
        if (urlStr == null || urlStr.isEmpty()) return;
        final String u = urlStr.startsWith("//") ? ("https:" + urlStr) : urlStr;
        Bitmap cached = IMAGES.get(u);
        if (cached != null) {
            cb.onBitmap(cached);
            return;
        }
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        Bitmap b = BitmapFactory.decodeStream(is);
                        if (b != null) {
                            IMAGES.put(u, b);
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
    // Adaptive Floating Liquid Glass YouTube Pill Bar
    // - Phone UI: Vertical Liquid Glass pill on the RIGHT edge
    // - Foldable / Tablet UI: Horizontal Liquid Glass pill at the BOTTOM
    // Modes: 0=홈, 1=구독, 2=기록, 3=나
    // ==================================================================
    static final class YtModePill extends View {
        interface Listener { void onModeSelected(int mode); }
        private static final int[] MODE_ICONS = {Icons.HOME, Icons.SUBSCRIPTIONS, Icons.CLOCK, Icons.CHANNEL};
        private static final int[] MODE_LABELS = {R.string.yt_tab_feed, R.string.yt_tab_subs, R.string.yt_tab_history, R.string.yt_tab_you};

        private final Glass.Backdrop backdrop;
        private Listener listener;
        int selected = 0;
        boolean vertical;

        final RectF pill = new RectF(), lens = new RectF();
        private final Glass.Surface barGlass = new Glass.Surface();
        private final Glass.Surface lensGlass = new Glass.Surface();
        private final RenderNode activeNode = new RenderNode("yt-pill-active");
        private final Ui.Spring lensPos = new Ui.Spring(440f, 1.0f, 0.4f);
        private final Ui.Spring lift = new Ui.Spring(560f, 1.0f, 0.002f);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean dragging;
        private float downX, downY, touchX, touchY;
        private VelocityTracker vt;
        private float dragVel;

        YtModePill(Context c, Glass.Backdrop backdrop) {
            super(c);
            this.backdrop = backdrop;
            tp.setTypeface(Ui.semibold);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(Ui.sp(10f));

            barGlass.blurDp = Ui.barBlur();
            barGlass.refractDp = 17f;
            barGlass.edgeDp = 26f;
            barGlass.outerDp = 7f;
            barGlass.outerRefractDp = 12f;
            barGlass.chroma = 0f;
            barGlass.vibrance = 1.35f;
            barGlass.rim = 0.32f;
            barGlass.edgeDark = 0.32f;
            barGlass.shadowAlpha = 0.16f;

            lensGlass.blur = false;
            lensGlass.refractDp = 22f;
            lensGlass.edgeDp = 28f;
            lensGlass.outerDp = 8f;
            lensGlass.outerRefractDp = 14f;
            lensGlass.chroma = 0.9f;
            lensGlass.rim = 0.6f;
            lensGlass.shadowAlpha = 0.16f;

            lensPos.onUpdate = this::invalidate;
            lift.onUpdate = this::invalidate;
        }

        void setListener(Listener l) { listener = l; }
        boolean animating() { return lensPos.running || lift.running; }

        void select(int mode, boolean animate) {
            selected = Math.max(0, Math.min(3, mode));
            if (getWidth() > 0) {
                layoutPill();
                float target = slotCenter(selected);
                if (animate) lensPos.to(target); else lensPos.snap(target);
            }
            invalidate();
        }

        static boolean isVerticalFor(int W, int H) {
            return !Dock.railFor(W, H) && !Dock.tabletFor(W, H);
        }

        private void layoutPill() {
            int W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            vertical = isVerticalFor(W, H);
            if (vertical) {
                // Phone UI: vertical glass pill floating cleanly on the RIGHT side
                float pw = Ui.dpf(54);
                float ph = Ui.dpf(224);
                float right = W - Ui.dpf(12) - Ui.cutoutRight;
                float cy = H * 0.48f;
                pill.set(right - pw, cy - ph / 2f, right, cy + ph / 2f);
            } else {
                // Foldable / Tablet UI: horizontal glass pill floating cleanly at the BOTTOM (stacked above mini player if active)
                MainActivity ma = getContext() instanceof MainActivity ? (MainActivity) getContext() : null;
                float cl = ma != null ? ma.contentInsetPx() : 0f;
                float cr = W - (ma != null ? ma.sideInset() : 0f);
                float availW = Math.max(Ui.dpf(220), cr - cl);
                float pw = Math.min(availW - Ui.dpf(32), Ui.dpf(336));
                float ph = Ui.dpf(54);
                float cx = cl + availW / 2f;
                float miniLift = (VideoPlayer.current != null) ? Ui.dpf(72) : 0f;
                float bottom = H - Ui.navInset - Ui.dpf(14) - miniLift;
                pill.set(cx - pw / 2f, bottom - ph, cx + pw / 2f, bottom);
            }
            if (!lensPos.running && !dragging) lensPos.snap(slotCenter(selected));
        }

        private float slotCenter(int i) {
            return vertical
                    ? pill.top + (i + 0.5f) * (pill.height() / 4f)
                    : pill.left + (i + 0.5f) * (pill.width() / 4f);
        }

        private int slotAt(float x, float y) {
            int i = vertical
                    ? (int) ((y - pill.top) / (pill.height() / 4f))
                    : (int) ((x - pill.left) / (pill.width() / 4f));
            return Math.max(0, Math.min(3, i));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            layoutPill();
            if (changed) lensPos.snap(slotCenter(selected));
        }

        @Override protected void onDraw(Canvas c) {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            layoutPill();

            barGlass.blurDp = Ui.barBlur();
            barGlass.tint = Ui.barTint();
            barGlass.strokeColor = Ui.glassStroke();
            barGlass.pressX = touchX; barGlass.pressY = touchY;
            barGlass.pressR = Ui.dpf(40);
            barGlass.pressAmt = lift.value;
            barGlass.shapes(pill, Math.min(pill.width(), pill.height()) / 2f, null, 0, null, 0);
            barGlass.draw(c, this, backdrop);

            float l = lift.value;
            float vNorm = Ui.clamp(dragVel / 1800f, -0.18f, 0.18f);
            float stretch = 1f / (1f - Math.abs(vNorm) * 0.65f);
            float squash = 1f - Math.abs(vNorm) * 0.2f;
            if (vertical) {
                float slotH = pill.height() / 4f;
                float lh = Ui.lerp(slotH - Ui.dpf(8), slotH + Ui.dpf(6), l) * stretch;
                float lw = Ui.lerp(pill.width() - Ui.dpf(10), pill.width() + Ui.dpf(4), l) * squash;
                lens.set(pill.centerX() - lw / 2f, lensPos.value - lh / 2f, pill.centerX() + lw / 2f, lensPos.value + lh / 2f);
            } else {
                float slotW = pill.width() / 4f;
                float lw = Ui.lerp(slotW - Ui.dpf(8), slotW + Ui.dpf(10), l) * stretch;
                float lh = Ui.lerp(pill.height() - Ui.dpf(10), pill.height() + Ui.dpf(4), l) * squash;
                lens.set(lensPos.value - lw / 2f, pill.centerY() - lh / 2f, lensPos.value + lw / 2f, pill.centerY() + lh / 2f);
            }

            if (Ui.gStyle) {
                fill.setColor(Ui.alpha(0xFFFF0033, Ui.dark ? 0.24f : 0.14f));
                c.drawRoundRect(lens, Math.min(lens.width(), lens.height()) / 2f, Math.min(lens.width(), lens.height()) / 2f, fill);
                drawSlots(c, false);
            } else if (l > 0.02f && c.isHardwareAccelerated()) {
                activeNode.setPosition(0, 0, getWidth(), getHeight());
                RecordingCanvas rc = activeNode.beginRecording(getWidth(), getHeight());
                try { drawSlots(rc, true); } finally { activeNode.endRecording(); }
                lensGlass.extra = activeNode;
                lensGlass.shapes(lens, Math.min(lens.width(), lens.height()) / 2f, null, 0, null, 0);
                lensGlass.mag = 1f + 0.14f * l;
                lensGlass.magX = lens.centerX();
                lensGlass.magY = lens.centerY();
                lensGlass.tint = Ui.dark ? 0x0FFFFFFF : 0x0AFFFFFF;
                lensGlass.strokeColor = Ui.dark ? 0x40FFFFFF : 0x1A000000;
                drawSlots(c, false);
                lensGlass.draw(c, this, backdrop);
            } else {
                fill.setColor(Ui.dark ? 0x26FFFFFF : 0x14000000);
                c.drawRoundRect(lens, Math.min(lens.width(), lens.height()) / 2f, Math.min(lens.width(), lens.height()) / 2f, fill);
                drawSlots(c, false);
            }
        }

        private void drawSlots(Canvas c, boolean forceActive) {
            int fg2 = Ui.text2();
            int actCol = 0xFFFF0033;
            for (int i = 0; i < 4; i++) {
                boolean on = forceActive || i == selected;
                int col = on ? actCol : fg2;
                if (vertical) {
                    float cy = slotCenter(i);
                    Icons.draw(c, MODE_ICONS[i], pill.centerX(), cy, Ui.dpf(21), col);
                } else {
                    float cx = slotCenter(i);
                    Icons.draw(c, MODE_ICONS[i], cx, pill.centerY() - Ui.dpf(7), Ui.dpf(19), col);
                    tp.setColor(col);
                    tp.setTypeface(on ? Ui.bold : Ui.medium);
                    c.drawText(getContext().getString(MODE_LABELS[i]), cx, pill.centerY() + Ui.dpf(14), tp);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    layoutPill();
                    if (!pill.contains(x, y)) return false;
                    downX = touchX = x; downY = touchY = y;
                    dragging = false;
                    lift.to(1f);
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    touchX = x; touchY = y;
                    if (vt != null) {
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000);
                        float v = vertical ? vt.getYVelocity() : vt.getXVelocity();
                        dragVel = dragVel * 0.65f + v * 0.35f;
                    }
                    float d = vertical ? Math.abs(y - downY) : Math.abs(x - downX);
                    if (d > Ui.dpf(6)) dragging = true;
                    if (dragging) {
                        float pos = vertical
                                ? Ui.clamp(y, slotCenter(0), slotCenter(3))
                                : Ui.clamp(x, slotCenter(0), slotCenter(3));
                        lensPos.snap(pos);
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    int idx = slotAt(x, y);
                    dragging = false;
                    dragVel = 0f;
                    lift.to(0f);
                    if (vt != null) { vt.recycle(); vt = null; }
                    if (idx != selected) {
                        selected = idx;
                        lensPos.to(slotCenter(idx));
                        if (listener != null) listener.onModeSelected(idx);
                    } else {
                        lensPos.to(slotCenter(idx));
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    dragVel = 0f;
                    lift.to(0f);
                    if (vt != null) { vt.recycle(); vt = null; }
                    lensPos.to(slotCenter(selected));
                    return true;
            }
            return false;
        }
    }

    // ==================================================================
    // Authentic YouTube Hub + Home Page (Tab 0 — merged Home & YouTube!)
    // Modes: 0=홈(피드+카테고리+기기동영상), 1=구독(동기화 채널+피드), 2=기록(시청 기록), 3=나(프로필+재생목록+채널)
    // ==================================================================
    static class HomeYouTubePage extends Page {
        private int mode = 0;
        private int category = 0; // 0=전체, 1=음악, 2=게임, 3=실시간, 4=최신, 5=영화, 6=테크, 7=기기 동영상
        private String currentQuery = "";
        private boolean searchOpen = false;
        private String filterChannelId = "";
        private String filterChannelName = "";
        private LinearLayout resultsHost;
        private TextView statusLabel;

        HomeYouTubePage(MainActivity m) {
            super(m, m.getString(R.string.tab_home), true);
            headerIcons = new int[]{Icons.PLUS, Icons.SETTINGS};
        }

        void setMode(int nextMode) {
            if (mode == nextMode) {
                scrollToTop();
                return;
            }
            mode = nextMode;
            filterChannelId = "";
            filterChannelName = "";
            int[] titles = {R.string.tab_home, R.string.yt_tab_subs, R.string.yt_tab_history, R.string.yt_tab_you};
            title = main.getString(titles[Math.max(0, Math.min(3, mode))]);
            rebuild();
            scroll.scrollTo(0, 0);
        }

        @Override void applyInsets() {
            int top = Ui.statusInset + Ui.dp(largeTop() ? 64 : 60);
            int rightPillPad = (!main.railUi() && !main.tabletUi()) ? Ui.dp(68) : 0;
            content.setPadding(0, top, main.sideInset() + rightPillPad, main.bottomPadding());
        }

        @Override void build() {
            MainActivity m = main;
            applyInsets();
            boolean signedIn = isGoogleSignedIn(m);
            MicroGInfo mg = detectMicroGProvider(m);
            String accLabel = signedIn ? getSignedInLabel(m) : "";

            // Authentic YouTube Top App Bar (Logo + Search Toggle + Account Avatar)
            LinearLayout topBar = new LinearLayout(m);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(Ui.dp(16), 0, Ui.dp(12), Ui.dp(8));

            LinearLayout brand = new LinearLayout(m);
            brand.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout ytLogo = new FrameLayout(m);
            ytLogo.setBackground(Ui.round(0xFFFF0033, 6));
            ytLogo.addView(new Icons.IconView(m, Icons.PLAY, 11, 0xFFFFFFFF),
                    new FrameLayout.LayoutParams(Ui.dp(13), Ui.dp(13), Gravity.CENTER));
            brand.addView(ytLogo, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(20)));
            TextView wordmark = Ui.text(m, "YouTube", 18.5f, Ui.bold, Ui.text());
            wordmark.setPadding(Ui.dp(6), 0, Ui.dp(8), 0);
            brand.addView(wordmark);
            topBar.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Search button (toggles inline YouTube search bar)
            Icons.IconView searchToggle = new Icons.IconView(m, searchOpen ? Icons.CLOSE : Icons.SEARCH, 20, Ui.text());
            searchToggle.setBackground(Widgets.ripple());
            searchToggle.setOnClickListener(v -> {
                searchOpen = !searchOpen;
                if (!searchOpen && !currentQuery.isEmpty()) {
                    currentQuery = "";
                }
                rebuild();
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36));
            slp.rightMargin = Ui.dp(6);
            topBar.addView(searchToggle, slp);

            // Clean Account Avatar / Sign-in pill
            View accountBtn;
            if (signedIn) {
                String init = accLabel.isEmpty() ? "G" : accLabel.substring(0, 1).toUpperCase(Locale.US);
                TextView av = Ui.text(m, init, 13.5f, Ui.bold, 0xFFFFFFFF);
                av.setGravity(Gravity.CENTER);
                av.setBackground(Ui.round(channelColor(accLabel), 100));
                av.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30)));
                accountBtn = av;
            } else {
                TextView pill = Ui.text(m, mg != null && mg.isMicroG ? "microG 로그인" : m.getString(R.string.yt_google_signin),
                        12f, Ui.semibold, Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF);
                pill.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
                pill.setBackground(Ui.round(Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F, 100));
                accountBtn = pill;
            }
            accountBtn.setOnClickListener(v -> {
                if (signedIn) {
                    List<GlassMenu.Entry> es = new ArrayList<>();
                    es.add(new GlassMenu.Entry(accLabel, Icons.CHANNEL, true, null));
                    es.add(new GlassMenu.Entry("구독 · 재생목록 · 기록 지금 동기화", Icons.REFRESH, false, () -> {
                        syncSubscribedChannels(m, (ch, ok) -> {});
                        syncUserPlaylists(m, (pl, ok) -> rebuild());
                        Toast.makeText(m, "Google 계정 동기화를 실행했습니다", Toast.LENGTH_SHORT).show();
                    }));
                    if (mg != null && mg.isMicroG) {
                        es.add(new GlassMenu.Entry(m.getString(R.string.yt_microg_signin), Icons.YOUTUBE, false, () -> startMicroGAccountLogin(m, this::rebuild)));
                    }
                    es.add(new GlassMenu.Entry(m.getString(R.string.yt_source_settings), Icons.SETTINGS, false, () -> showSourceSettingsSheet(m)));
                    es.add(new GlassMenu.Entry(m.getString(R.string.yt_google_signout), Icons.CLOSE, false, () -> { signOutGoogle(m); rebuild(); }));
                    float[] o = Glass.abs(v);
                    GlassMenu.show(m, new RectF(o[0], o[1], o[0] + v.getWidth(), o[1] + v.getHeight()), es);
                } else if (mg != null && mg.isMicroG) {
                    startMicroGAccountLogin(m, this::rebuild);
                } else {
                    showGoogleLoginDialog(m, this::rebuild);
                }
            });
            Ui.press(accountBtn, 0.92f);
            topBar.addView(accountBtn);
            content.addView(topBar, Widgets.MW);

            // Inline YouTube Search Bar (visible when search icon toggled or query active)
            if (searchOpen || !currentQuery.isEmpty()) {
                LinearLayout searchPill = new LinearLayout(m);
                searchPill.setOrientation(LinearLayout.HORIZONTAL);
                searchPill.setGravity(Gravity.CENTER_VERTICAL);
                searchPill.setPadding(Ui.dp(14), Ui.dp(5), Ui.dp(6), Ui.dp(5));
                GradientDrawable sBg = Ui.round(Ui.chip(), 100);
                searchPill.setBackground(sBg);
                searchPill.addView(new Icons.IconView(m, Icons.SEARCH, 17, Ui.text2()), new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));

                EditText et = new EditText(m);
                et.setBackground(null);
                et.setSingleLine(true);
                et.setHint(m.getString(R.string.yt_search_hint));
                et.setText(currentQuery);
                et.setTextSize(14.5f);
                et.setTextColor(Ui.text());
                et.setHintTextColor(Ui.text3());
                et.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
                et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
                et.setInputType(InputType.TYPE_CLASS_TEXT);
                searchPill.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView goBtn = Ui.text(m, m.getString(R.string.search), 12.5f, Ui.semibold, Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF);
                goBtn.setPadding(Ui.dp(13), Ui.dp(6), Ui.dp(13), Ui.dp(6));
                goBtn.setBackground(Ui.round(Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F, 100));
                Runnable triggerSearch = () -> {
                    String q = et.getText().toString().trim();
                    String vid = extractVideoId(q);
                    if (vid != null && (q.contains("youtube.com") || q.contains("youtu.be") || RAW_ID_PAT.matcher(q).matches())) {
                        openVideo(m, new YtVideo(vid, "YouTube (" + vid + ")", "YouTube"));
                        return;
                    }
                    currentQuery = q;
                    mode = 0;
                    if (category == 7) category = 0;
                    fetchVideos();
                };
                goBtn.setOnClickListener(v -> triggerSearch.run());
                et.setOnEditorActionListener((v, id, ev) -> { triggerSearch.run(); return true; });
                Ui.press(goBtn, 0.94f);
                searchPill.addView(goBtn);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                spLp.setMargins(Ui.dp(14), 0, Ui.dp(12), Ui.dp(8));
                content.addView(searchPill, spLp);
            }

            if (mode == 0) buildFeedMode(m);
            else if (mode == 1) buildSubscriptionsMode(m);
            else if (mode == 2) buildHistoryMode(m);
            else buildYouMode(m);
        }

        private void buildFeedMode(MainActivity m) {
            // Authentic monochrome YouTube category chip strip
            String[] cats = {
                    m.getString(R.string.yt_cat_all),
                    m.getString(R.string.yt_cat_music),
                    m.getString(R.string.yt_cat_gaming),
                    m.getString(R.string.yt_cat_live),
                    m.getString(R.string.yt_cat_recent),
                    m.getString(R.string.yt_cat_film),
                    m.getString(R.string.yt_cat_tech),
                    m.getString(R.string.yt_cat_device)
            };
            HorizontalScrollView chipScroll = new HorizontalScrollView(m);
            chipScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = new LinearLayout(m);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setPadding(Ui.dp(14), Ui.dp(2), Ui.dp(14), Ui.dp(10));
            for (int i = 0; i < cats.length; i++) {
                final int idx = i;
                boolean sel = (idx == category && currentQuery.isEmpty());
                int bgCol = sel ? (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F) : Ui.chip();
                int fgCol = sel ? (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF) : Ui.text();
                TextView ch = Ui.text(m, cats[i], 13f, sel ? Ui.semibold : Ui.medium, fgCol);
                ch.setPadding(Ui.dp(13), Ui.dp(7), Ui.dp(13), Ui.dp(7));
                ch.setBackground(Ui.round(bgCol, 8));
                ch.setOnClickListener(v -> {
                    category = idx;
                    currentQuery = "";
                    rebuild();
                });
                Ui.press(ch, 0.94f);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) clp.leftMargin = Ui.dp(8);
                chipRow.addView(ch, clp);
            }
            chipScroll.addView(chipRow);
            content.addView(chipScroll, Widgets.MW);

            // Compact local Continue Watching rail when user has local videos in progress
            List<VideoLibrary.Video> cw = VideoLibrary.continueWatching();
            if (!cw.isEmpty() && category == 0 && currentQuery.isEmpty()) {
                content.addView(Widgets.section(m, m.getString(R.string.continue_watching)));
                LinearLayout rail = Widgets.rail(content, 12);
                for (VideoLibrary.Video v : cw) {
                    rail.addView(Widgets.continueCard(m, v, vv -> m.playVideo(v, cw)));
                }
            }

            statusLabel = Ui.text(m, m.getString(R.string.yt_loading), 12f, Ui.medium, Ui.text2());
            statusLabel.setPadding(Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(8));
            content.addView(statusLabel, Widgets.MW);

            resultsHost = new LinearLayout(m);
            resultsHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(resultsHost, Widgets.MW);

            fetchVideos();
        }

        private void buildSubscriptionsMode(MainActivity m) {
            // Live-synced Google Subscribed Channels Avatar Strip (authentic YouTube Subscriptions top strip!)
            HorizontalScrollView chScroll = new HorizontalScrollView(m);
            chScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(m);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(Ui.dp(14), Ui.dp(2), Ui.dp(14), Ui.dp(10));
            chScroll.addView(strip);
            content.addView(chScroll, Widgets.MW);

            statusLabel = Ui.text(m, "구독 피드 동기화 중…", 12f, Ui.medium, Ui.text2());
            statusLabel.setPadding(Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(8));
            content.addView(statusLabel, Widgets.MW);

            resultsHost = new LinearLayout(m);
            resultsHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(resultsHost, Widgets.MW);

            // Sync real Google subscribed channels for the avatar strip
            syncSubscribedChannels(m, (channels, fromGoogle) -> {
                strip.removeAllViews();
                List<SubscribedChannel> display = new ArrayList<>(channels);
                if (display.isEmpty()) {
                    for (String[] cc : CURATED_CHANNELS) display.add(new SubscribedChannel(cc[0], cc[1]));
                }
                for (SubscribedChannel ch : display) {
                    LinearLayout item = new LinearLayout(m);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setGravity(Gravity.CENTER_HORIZONTAL);
                    RemoteImgView av = new RemoteImgView(m, true);
                    av.bindAvatar(ch.avatarUrl, ch.name);
                    item.addView(av, new LinearLayout.LayoutParams(Ui.dp(54), Ui.dp(54)));

                    TextView nm = Ui.text(m, ch.name, 11.5f, Ui.medium, Ui.text2());
                    nm.setGravity(Gravity.CENTER);
                    nm.setMaxLines(1);
                    nm.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.dp(66), ViewGroup.LayoutParams.WRAP_CONTENT);
                    nlp.topMargin = Ui.dp(5);
                    item.addView(nm, nlp);
                    item.setOnClickListener(v -> m.push(new ChannelPage(m, ch.channelId, ch.name)));
                    Ui.press(item, 0.93f);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    ilp.rightMargin = Ui.dp(8);
                    strip.addView(item, ilp);
                }
            });

            loadSubscriptionsFeed(m, (videos, src) -> {
                if (resultsHost == null) return;
                resultsHost.removeAllViews();
                if (videos.isEmpty()) {
                    statusLabel.setText("구독 중인 채널의 최신 영상이 없습니다.");
                    return;
                }
                statusLabel.setText(src + " · " + videos.size() + "개 동영상");
                renderVideoCards(m, resultsHost, videos, rightContentWidth(m));
            });
        }

        private void buildHistoryMode(MainActivity m) {
            statusLabel = Ui.text(m, "시청 기록 동기화 중…", 12.5f, Ui.medium, Ui.text2());
            statusLabel.setPadding(Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(8));
            content.addView(statusLabel, Widgets.MW);

            resultsHost = new LinearLayout(m);
            resultsHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(resultsHost, Widgets.MW);

            loadWatchHistory(m, (videos, src) -> {
                if (resultsHost == null) return;
                resultsHost.removeAllViews();
                if (videos.isEmpty()) {
                    statusLabel.setText("시청 기록이 없습니다. Google 로그인 후 동영상을 시청하면 자동으로 동기화됩니다.");
                    return;
                }
                statusLabel.setText(src + " · " + videos.size() + "개");
                for (YtVideo v : videos) {
                    resultsHost.addView(buildCompactVideoRow(m, v), Widgets.MW);
                }
            });
        }

        private void buildYouMode(MainActivity m) {
            boolean signedIn = isGoogleSignedIn(m);
            String accName = signedIn ? getSignedInLabel(m) : "게스트 모드";

            // Authentic YouTube "나 / You" Account Profile Header
            LinearLayout prof = new LinearLayout(m);
            prof.setOrientation(LinearLayout.HORIZONTAL);
            prof.setGravity(Gravity.CENTER_VERTICAL);
            prof.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(14));
            RemoteImgView av = new RemoteImgView(m, true);
            av.bindAvatar("", accName);
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56));
            avLp.rightMargin = Ui.dp(14);
            prof.addView(av, avLp);

            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(m, accName, 19f, Ui.bold, Ui.text()));
            col.addView(Ui.text(m, signedIn ? "Google 계정 · 구독/기록/재생목록 동기화됨" : "Google 로그인하여 기록과 재생목록 동기화",
                    12.5f, Ui.regular, Ui.text2()));
            prof.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            content.addView(prof, Widgets.MW);

            // 1) Watch History horizontal rail (exact YouTube "나" tab!)
            LinearLayout histHdr = new LinearLayout(m);
            histHdr.setOrientation(LinearLayout.HORIZONTAL);
            histHdr.setGravity(Gravity.CENTER_VERTICAL);
            histHdr.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(8));
            histHdr.addView(Ui.text(m, "기록", 17f, Ui.bold, Ui.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView allHist = Ui.text(m, "모두 보기", 12.5f, Ui.semibold, Ui.text());
            allHist.setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5));
            GradientDrawable ahBg = Ui.round(0x00000000, 100);
            ahBg.setStroke(Math.max(1, Ui.dp(0.9f)), Ui.separator());
            allHist.setBackground(ahBg);
            allHist.setOnClickListener(v -> {
                if (m.ytPill != null) m.ytPill.select(2, true);
                setMode(2);
            });
            histHdr.addView(allHist);
            content.addView(histHdr, Widgets.MW);

            HorizontalScrollView histScroll = new HorizontalScrollView(m);
            histScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout histRail = new LinearLayout(m);
            histRail.setOrientation(LinearLayout.HORIZONTAL);
            histRail.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(14));
            histScroll.addView(histRail);
            content.addView(histScroll, Widgets.MW);

            loadWatchHistory(m, (videos, src) -> {
                histRail.removeAllViews();
                if (videos.isEmpty()) {
                    histRail.addView(Ui.text(m, "최근 시청한 동영상이 없습니다", 13f, Ui.regular, Ui.text2()));
                    return;
                }
                int max = Math.min(10, videos.size());
                for (int i = 0; i < max; i++) {
                    YtVideo v = videos.get(i);
                    View card = buildMiniHorizontalCard(m, v);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(156), ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) lp.leftMargin = Ui.dp(10);
                    histRail.addView(card, lp);
                }
            });

            // 2) Synced YouTube Playlists (WL, LL, and Google user playlists)
            LinearLayout plHdr = new LinearLayout(m);
            plHdr.setOrientation(LinearLayout.HORIZONTAL);
            plHdr.setGravity(Gravity.CENTER_VERTICAL);
            plHdr.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(6));
            plHdr.addView(Ui.text(m, "재생목록", 17f, Ui.bold, Ui.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView newPl = Ui.text(m, "+ 새 재생목록", 12.5f, Ui.semibold, Ui.text());
            newPl.setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5));
            GradientDrawable npBg = Ui.round(0x00000000, 100);
            npBg.setStroke(Math.max(1, Ui.dp(0.9f)), Ui.separator());
            newPl.setBackground(npBg);
            newPl.setOnClickListener(v -> {
                LinearLayout form = new LinearLayout(m);
                form.setOrientation(LinearLayout.VERTICAL);
                EditText et = Widgets.formField(m, "이름", "재생목록 이름 입력", "", InputType.TYPE_CLASS_TEXT, form);
                Widgets.showFormSheet(m, "새 YouTube 재생목록", form, m.getString(R.string.ok), () -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) { createYtPlaylist(m, n); rebuild(); }
                });
            });
            plHdr.addView(newPl);
            content.addView(plHdr, Widgets.MW);

            LinearLayout plHost = new LinearLayout(m);
            plHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(plHost, Widgets.MW);

            syncUserPlaylists(m, (pls, fromGoogle) -> {
                plHost.removeAllViews();
                Widgets.Group g = new Widgets.Group(m, fromGoogle ? "Google 동기화됨 (" + pls.size() + ")" : null);
                for (YtPlaylist pl : pls) {
                    int ic = "LL".equals(pl.id) ? Icons.THUMB_UP_FILL : ("WL".equals(pl.id) ? Icons.CLOCK : Icons.PLAYLIST);
                    int tint = "LL".equals(pl.id) ? 0xFFFF0033 : Ui.ACCENT;
                    String sub = !pl.subtitle.isEmpty() ? pl.subtitle
                            : (pl.remoteCount >= 0 ? m.getString(R.string.n_videos, pl.remoteCount)
                            : (pl.remoteSynced ? "Google 동기화" : m.getString(R.string.n_videos, pl.videos.size())));
                    g.row(ic, tint, pl.title, sub, true, v -> m.push(new YtPlaylistDetailPage(m, pl.id, pl.title)));
                }
                plHost.addView(g, Widgets.MW);
            });

            // 3) Synced Subscribed Channels
            LinearLayout subsHost = new LinearLayout(m);
            subsHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(subsHost, Widgets.MW);

            syncSubscribedChannels(m, (chans, fromGoogle) -> {
                subsHost.removeAllViews();
                if (!chans.isEmpty()) {
                    Widgets.Group sg = new Widgets.Group(m, (fromGoogle ? "구독 중인 채널 (Google 동기화 · " : "구독 중인 채널 (") + chans.size() + ")");
                    int max = Math.min(12, chans.size());
                    for (int i = 0; i < max; i++) {
                        SubscribedChannel ch = chans.get(i);
                        addChannelRow(m, sg, ch.channelId, ch.name, ch.avatarUrl, ch.subscribers, true);
                    }
                    subsHost.addView(sg, Widgets.MW);
                }
            });

            if (!VideoLibrary.playlists.isEmpty()) {
                Widgets.Group loc = new Widgets.Group(m, "기기 재생목록");
                for (VideoLibrary.Playlist pl : VideoLibrary.playlists) {
                    loc.row(Icons.PLAYLIST, Ui.ACCENT, pl.name, m.getString(R.string.n_videos, pl.videoKeys.size()), true,
                            v -> m.openLibrarySection("playlists"));
                }
                content.addView(loc, Widgets.MW);
            }
        }

        private void addChannelRow(MainActivity m, Widgets.Group g, String chId, String chName, String avatarUrl, String subs, boolean subscribed) {
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(16), Ui.dp(11));
            row.setBackground(Widgets.ripple());

            RemoteImgView av = new RemoteImgView(m, true);
            av.bindAvatar(avatarUrl, chName);
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38));
            avlp.rightMargin = Ui.dp(12);
            row.addView(av, avlp);

            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(m, chName, 15f, Ui.medium, Ui.text()));
            if (subs != null && !subs.isEmpty()) col.addView(Ui.text(m, subs, 12f, Ui.regular, Ui.text2()));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView subBtn = Ui.text(m, m.getString(subscribed ? R.string.yt_subscribed : R.string.yt_subscribe),
                    12f, Ui.semibold, subscribed ? Ui.text() : (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF));
            subBtn.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
            subBtn.setBackground(Ui.round(subscribed ? Ui.chip() : (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F), 100));
            subBtn.setOnClickListener(v -> {
                boolean now = toggleSubscribe(m, chId, chName);
                Toast.makeText(m, chName + (now ? " 채널을 구독했습니다" : " 구독을 취소했습니다"), Toast.LENGTH_SHORT).show();
                rebuild();
            });
            Ui.press(subBtn, 0.92f);
            row.addView(subBtn);

            row.setOnClickListener(v -> m.push(new ChannelPage(m, chId, chName)));
            g.addCustom(row);
        }

        private int rightContentWidth(MainActivity m) {
            int extraRight = (!m.railUi() && !m.tabletUi()) ? Ui.dp(68) : 0;
            return Math.max(Ui.dp(220), Widgets.contentW(m) - extraRight);
        }

        private void fetchVideos() {
            if (resultsHost == null) return;
            resultsHost.removeAllViews();
            if (category == 7 && currentQuery.isEmpty()) {
                statusLabel.setText("기기 동영상 · " + VideoLibrary.allVideos.size() + "개");
                if (VideoLibrary.allVideos.isEmpty()) {
                    resultsHost.addView(Pages.emptyBox(main, !VideoLibrary.hasPermission(main)), Widgets.MW);
                } else {
                    Pages.addVideoList(main, resultsHost, VideoLibrary.allVideos);
                }
                return;
            }
            statusLabel.setText(main.getString(R.string.yt_loading));
            ListCb cb = (videos, src) -> {
                if (resultsHost == null) return;
                resultsHost.removeAllViews();
                if (videos.isEmpty()) {
                    statusLabel.setText(main.getString(R.string.yt_empty));
                    return;
                }
                statusLabel.setText(src + " · " + videos.size() + "개 동영상");
                renderVideoCards(main, resultsHost, videos, rightContentWidth(main));
            };
            if (!currentQuery.isEmpty()) searchVideos(main, currentQuery, cb);
            else loadCategory(main, category, cb);
        }
    }

    /** Backwards-compatible alias kept for tests & deep links. */
    static final class YouTubePage extends HomeYouTubePage {
        YouTubePage(MainActivity m) { super(m); }
    }

    // ==================================================================
    // Authentic Full YouTube Channel Page (Banner + Avatar + Handle/Stats + Bio + 5 Real Channel Tabs)
    // ==================================================================
    static final class ChannelPage extends Page {
        private static final String[] TAB_KEYS = {"home", "videos", "shorts", "live", "playlists"};
        private static final int[] TAB_LABELS = {
                R.string.yt_channel_home, R.string.yt_channel_videos, R.string.yt_channel_shorts,
                R.string.yt_channel_live, R.string.yt_channel_playlists
        };

        private final String channelId;
        private String channelName;
        private String activeTab = "videos";
        private ChannelInfo latestInfo;
        private RemoteImgView bannerView, avatarView;
        private TextView nameTv, metaTv, bioTv;
        private LinearLayout tabBarHost, bodyHost;

        ChannelPage(MainActivity m, String channelId, String channelName) {
            super(m, (channelName == null || channelName.isEmpty()) ? "채널" : channelName, true);
            this.channelId = channelId == null ? "" : channelId;
            this.channelName = (channelName == null || channelName.isEmpty()) ? "채널" : channelName;
            back = true;
            headerIcons = new int[0];
        }

        @Override void build() {
            MainActivity m = main;

            // 1. Channel Banner (authentic rounded 16:4 YouTube channel banner)
            bannerView = new RemoteImgView(m, false);
            bannerView.setRadiusDp(14f);
            bannerView.bindBanner(latestInfo != null ? latestInfo.bannerUrl : "", channelName);
            LinearLayout.LayoutParams banLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(106));
            banLp.setMargins(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(14));
            content.addView(bannerView, banLp);

            // 2. Avatar + Channel Title + @handle · Subscribers · Videos
            LinearLayout hdr = new LinearLayout(m);
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            hdr.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10));

            avatarView = new RemoteImgView(m, true);
            avatarView.bindAvatar(latestInfo != null ? latestInfo.avatarUrl : "", channelName);
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(Ui.dp(66), Ui.dp(66));
            avlp.rightMargin = Ui.dp(14);
            hdr.addView(avatarView, avlp);

            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);
            nameTv = Ui.text(m, channelName, 20f, Ui.bold, Ui.text());
            metaTv = Ui.text(m, "채널 정보 불러오는 중…", 12.5f, Ui.regular, Ui.text2());
            metaTv.setSingleLine(false);
            metaTv.setMaxLines(2);
            metaTv.setPadding(0, Ui.dp(3), 0, 0);
            col.addView(nameTv);
            col.addView(metaTv);
            hdr.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            content.addView(hdr, Widgets.MW);

            // 3. Clickable Bio line -> opens full Channel About Glass Sheet
            bioTv = Ui.text(m, "", 13f, Ui.regular, Ui.text2());
            bioTv.setSingleLine(false);
            bioTv.setMaxLines(2);
            bioTv.setEllipsize(TextUtils.TruncateAt.END);
            bioTv.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(12));
            bioTv.setVisibility(GONE);
            bioTv.setOnClickListener(v -> showChannelAboutSheet(m));
            content.addView(bioTv, Widgets.MW);

            // 4. Full-width monochrome YouTube Subscribe button
            boolean sub = isSubscribed(m, channelId);
            TextView subBtn = Ui.text(m, m.getString(sub ? R.string.yt_subscribed : R.string.yt_subscribe),
                    14f, Ui.semibold, sub ? Ui.text() : (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF));
            subBtn.setGravity(Gravity.CENTER);
            subBtn.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10));
            subBtn.setBackground(Ui.round(sub ? Ui.chip() : (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F), 100));
            subBtn.setOnClickListener(v -> {
                boolean now = toggleSubscribe(m, channelId, channelName);
                subBtn.setText(m.getString(now ? R.string.yt_subscribed : R.string.yt_subscribe));
                subBtn.setTextColor(now ? Ui.text() : (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF));
                subBtn.setBackground(Ui.round(now ? Ui.chip() : (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F), 100));
                Toast.makeText(m, channelName + (now ? " 채널을 구독했습니다" : " 구독을 취소했습니다"), Toast.LENGTH_SHORT).show();
            });
            Ui.press(subBtn, 0.96f);
            LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sblp.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(14));
            content.addView(subBtn, sblp);

            // 5. Authentic YouTube Channel Tab Bar (홈 | 동영상 | Shorts | 실시간 | 재생목록)
            HorizontalScrollView tabScroll = new HorizontalScrollView(m);
            tabScroll.setHorizontalScrollBarEnabled(false);
            tabBarHost = new LinearLayout(m);
            tabBarHost.setOrientation(LinearLayout.HORIZONTAL);
            tabBarHost.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
            tabScroll.addView(tabBarHost);
            content.addView(tabScroll, Widgets.MW);

            View hair = new View(m);
            hair.setBackgroundColor(Ui.separator());
            content.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.6f))));

            bodyHost = new LinearLayout(m);
            bodyHost.setOrientation(LinearLayout.VERTICAL);
            bodyHost.setPadding(0, Ui.dp(12), 0, 0);
            content.addView(bodyHost, Widgets.MW);

            renderChannelTabs(m);
            loadActiveTab(m);
        }

        private void renderChannelTabs(MainActivity m) {
            tabBarHost.removeAllViews();
            for (int i = 0; i < TAB_KEYS.length; i++) {
                final String key = TAB_KEYS[i];
                boolean on = key.equals(activeTab);
                LinearLayout tabItem = new LinearLayout(m);
                tabItem.setOrientation(LinearLayout.VERTICAL);
                tabItem.setGravity(Gravity.CENTER_HORIZONTAL);
                tabItem.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), 0);

                TextView tv = Ui.text(m, m.getString(TAB_LABELS[i]), 14f, on ? Ui.bold : Ui.medium, on ? Ui.text() : Ui.text2());
                tabItem.addView(tv);

                View indicator = new View(m);
                indicator.setBackgroundColor(on ? Ui.text() : Color.TRANSPARENT);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(2.5f));
                ilp.topMargin = Ui.dp(9);
                tabItem.addView(indicator, ilp);

                tabItem.setOnClickListener(v -> {
                    if (!activeTab.equals(key)) {
                        activeTab = key;
                        renderChannelTabs(m);
                        loadActiveTab(m);
                    }
                });
                tabBarHost.addView(tabItem);
            }
        }

        private void loadActiveTab(MainActivity m) {
            bodyHost.removeAllViews();
            bodyHost.addView(Widgets.caption(m, "불러오는 중…"));
            final String tabRequested = activeTab;
            loadChannelDetail(m, channelId, channelName, tabRequested, info -> {
                if (bodyHost == null || !tabRequested.equals(activeTab)) return;
                latestInfo = info;
                channelName = info.name;
                nameTv.setText(info.name);
                if (bigTitle != null) bigTitle.setText(info.name);
                if (!info.bannerUrl.isEmpty()) bannerView.bindBanner(info.bannerUrl, info.name);
                if (!info.avatarUrl.isEmpty()) avatarView.bindAvatar(info.avatarUrl, info.name);

                StringBuilder ms = new StringBuilder();
                if (!info.handle.isEmpty()) ms.append(info.handle);
                if (!info.subscribers.isEmpty()) { if (ms.length() > 0) ms.append(" · "); ms.append(info.subscribers); }
                if (!info.videoCount.isEmpty()) { if (ms.length() > 0) ms.append(" · "); ms.append(info.videoCount); }
                if (ms.length() == 0) ms.append("동영상 ").append(info.videos.size()).append("개");
                metaTv.setText(ms.toString());

                if (!info.description.isEmpty()) {
                    bioTv.setText(info.description.replace('\n', ' ') + "  …더보기");
                    bioTv.setVisibility(VISIBLE);
                }

                bodyHost.removeAllViews();
                if ("playlists".equals(tabRequested)) {
                    if (info.playlists.isEmpty()) {
                        bodyHost.addView(Widgets.caption(m, "공개된 채널 재생목록이 없습니다."));
                        return;
                    }
                    Widgets.Group pg = new Widgets.Group(m, null);
                    for (YtPlaylist pl : info.playlists) {
                        String sub = !pl.subtitle.isEmpty() ? pl.subtitle : "재생목록";
                        pg.row(Icons.PLAYLIST, Ui.ACCENT, pl.title, sub, true,
                                v -> m.push(new YtPlaylistDetailPage(m, pl.id, pl.title)));
                    }
                    bodyHost.addView(pg, Widgets.MW);
                } else if ("shorts".equals(tabRequested)) {
                    if (info.videos.isEmpty()) {
                        bodyHost.addView(Widgets.caption(m, "표시할 Shorts가 없습니다."));
                        return;
                    }
                    renderShortsGrid(m, bodyHost, info.videos, Widgets.contentW(m));
                } else {
                    if (info.videos.isEmpty()) {
                        bodyHost.addView(Widgets.caption(m, "표시할 동영상이 없습니다."));
                        return;
                    }
                    renderVideoCards(m, bodyHost, info.videos, Widgets.contentW(m));
                }
            });
        }

        private void showChannelAboutSheet(MainActivity m) {
            if (latestInfo == null) return;
            LinearLayout box = new LinearLayout(m);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(18), Ui.dp(4), Ui.dp(18), Ui.dp(14));
            if (!latestInfo.description.isEmpty()) {
                TextView d = Ui.text(m, latestInfo.description, 14f, Ui.regular, Ui.text());
                d.setSingleLine(false);
                box.addView(d, Widgets.MW);
            }
            StringBuilder stats = new StringBuilder();
            if (!latestInfo.handle.isEmpty()) stats.append("핸들: ").append(latestInfo.handle).append('\n');
            if (!latestInfo.subscribers.isEmpty()) stats.append(latestInfo.subscribers).append('\n');
            if (!latestInfo.videoCount.isEmpty()) stats.append(latestInfo.videoCount).append('\n');
            stats.append("https://www.youtube.com/channel/").append(channelId);
            TextView st = Ui.text(m, stats.toString(), 13f, Ui.medium, Ui.text2());
            st.setSingleLine(false);
            st.setPadding(0, Ui.dp(12), 0, 0);
            box.addView(st, Widgets.MW);
            RectF anchor = new RectF(Ui.screenW / 2f - Ui.dpf(40), Ui.screenH - Ui.dpf(80), Ui.screenW / 2f + Ui.dpf(40), Ui.screenH - Ui.dpf(40));
            GlassMenu.customSheet(m, anchor, latestInfo.name + " · 채널 정보", box, null);
        }
    }

    // ==================================================================
    // YouTube Playlist Detail Page (Live Google Sync + Local Items)
    // ==================================================================
    static final class YtPlaylistDetailPage extends Page {
        private final String playlistId;
        private final String fallbackTitle;
        private LinearLayout listHost;
        private TextView subInfo;

        YtPlaylistDetailPage(MainActivity m, String playlistId) {
            this(m, playlistId, "재생목록");
        }

        YtPlaylistDetailPage(MainActivity m, String playlistId, String fallbackTitle) {
            super(m, (fallbackTitle == null || fallbackTitle.isEmpty()) ? "재생목록" : fallbackTitle, true);
            this.playlistId = playlistId;
            this.fallbackTitle = (fallbackTitle == null || fallbackTitle.isEmpty()) ? "재생목록" : fallbackTitle;
            back = true;
            headerIcons = new int[0];
        }

        @Override void build() {
            MainActivity m = main;
            String resolvedTitle = fallbackTitle;
            for (YtPlaylist pl : getYtPlaylists(m)) {
                if (pl.id.equals(playlistId) && !pl.title.isEmpty()) { resolvedTitle = pl.title; break; }
            }
            title = resolvedTitle;
            if (bigTitle != null) bigTitle.setText(resolvedTitle);

            subInfo = Ui.text(m, "재생목록 불러오는 중…", 13f, Ui.medium, Ui.text2());
            subInfo.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(10));
            content.addView(subInfo, Widgets.MW);

            listHost = new LinearLayout(m);
            listHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(listHost, Widgets.MW);

            loadPlaylistVideos(m, playlistId, (videos, src) -> {
                if (listHost == null) return;
                listHost.removeAllViews();
                if (videos.isEmpty()) {
                    subInfo.setText("저장된 동영상이 없습니다");
                    listHost.addView(Widgets.caption(m, "동영상 메뉴나 재생 화면에서 '저장'을 눌러 추가하거나 Google 계정에 로그인해 동기화하세요."));
                    return;
                }
                subInfo.setText(src + " · " + videos.size() + "개 동영상");
                LinearLayout topAct = new LinearLayout(m);
                topAct.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(14));
                topAct.addView(Widgets.button(m, "모두 재생 (" + videos.size() + ")", true, v -> openVideo(m, videos.get(0))));
                listHost.addView(topAct, Widgets.MW);
                for (YtVideo v : videos) {
                    listHost.addView(buildCompactVideoRow(m, v), Widgets.MW);
                }
            });
        }
    }

    // ---- Shared Authentic YouTube Video Card & Row Renderers ----
    private static int channelColor(String author) {
        int[] palette = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFFB8C00, 0xFF00ACC1, 0xFFD81B60};
        int h = author == null ? 0 : Math.abs(author.hashCode());
        return palette[h % palette.length];
    }

    private static void renderVideoCards(MainActivity m, LinearLayout host, List<YtVideo> videos, int contentW) {
        int cols = contentW / Ui.density >= 840 ? 3 : (contentW / Ui.density >= 560 ? 2 : 1);
        int gap = Ui.dp(14);
        int pad = cols == 1 ? 0 : Ui.dp(16); // Authentic YouTube mobile 1-column full-bleed thumbnails!
        int avail = contentW - pad * 2 - gap * (cols - 1);
        int cardW = Math.max(Ui.dp(150), avail / cols);

        for (int i = 0; i < videos.size(); i += cols) {
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(pad, 0, pad, Ui.dp(cols == 1 ? 20 : 18));
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < videos.size()) {
                    YtVideo v = videos.get(idx);
                    View card = buildYtCard(m, v, cardW, cols == 1);
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
            host.addView(row, Widgets.MW);
        }
    }

    private static void renderShortsGrid(MainActivity m, LinearLayout host, List<YtVideo> shorts, int contentW) {
        int cols = contentW / Ui.density >= 700 ? 4 : 3;
        int gap = Ui.dp(8);
        int pad = Ui.dp(16);
        int cardW = Math.max(Ui.dp(92), (contentW - pad * 2 - gap * (cols - 1)) / cols);
        int cardH = Math.round(cardW * 16f / 9f);
        for (int i = 0; i < shorts.size(); i += cols) {
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(pad, 0, pad, Ui.dp(10));
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < shorts.size()) {
                    YtVideo v = shorts.get(idx);
                    FrameLayout box = new FrameLayout(m);
                    YtThumbView tv = new YtThumbView(m, 12f);
                    tv.bind(v);
                    box.addView(tv, new FrameLayout.LayoutParams(cardW, cardH));
                    View scrim = new View(m);
                    scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
                    box.addView(scrim, new FrameLayout.LayoutParams(cardW, Ui.dp(64), Gravity.BOTTOM));
                    TextView tt = Ui.text(m, v.title, 12f, Ui.semibold, 0xFFFFFFFF);
                    tt.setSingleLine(false);
                    tt.setMaxLines(2);
                    tt.setEllipsize(TextUtils.TruncateAt.END);
                    tt.setPadding(Ui.dp(8), 0, Ui.dp(8), Ui.dp(8));
                    box.addView(tt, new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
                    box.setOnClickListener(vv -> openVideo(m, v));
                    Ui.press(box, 0.96f);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                    if (c < cols - 1) lp.rightMargin = gap;
                    row.addView(box, lp);
                } else {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, 1);
                    if (c < cols - 1) lp.rightMargin = gap;
                    row.addView(new View(m), lp);
                }
            }
            host.addView(row, Widgets.MW);
        }
    }

    private static View buildMiniHorizontalCard(MainActivity m, YtVideo v) {
        LinearLayout card = new LinearLayout(m);
        card.setOrientation(LinearLayout.VERTICAL);
        int w = Ui.dp(156), h = Ui.dp(88);
        FrameLayout tb = new FrameLayout(m);
        YtThumbView tv = new YtThumbView(m, 10f);
        tv.bind(v);
        tb.addView(tv, new FrameLayout.LayoutParams(w, h));
        if (v.durationSec > 0) {
            TextView dur = Widgets.pillBadge(m, Ui.time(v.durationSec * 1000L), 0xCC000000, 0xFFFFFFFF);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            dlp.setMargins(0, 0, Ui.dp(6), Ui.dp(6));
            tb.addView(dur, dlp);
        }
        card.addView(tb, new LinearLayout.LayoutParams(w, h));
        TextView tt = Ui.text(m, v.title, 13f, Ui.semibold, Ui.text());
        tt.setSingleLine(false);
        tt.setMaxLines(2);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setPadding(Ui.dp(2), Ui.dp(6), Ui.dp(2), 0);
        card.addView(tt, Widgets.MW);
        TextView au = Ui.text(m, v.author, 11.5f, Ui.regular, Ui.text2());
        au.setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), 0);
        card.addView(au, Widgets.MW);
        card.setOnClickListener(vv -> openVideo(m, v));
        Ui.press(card, 0.96f);
        return card;
    }

    private static View buildCompactVideoRow(MainActivity m, YtVideo v) {
        LinearLayout r = new LinearLayout(m);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(16), Ui.dp(7), Ui.dp(12), Ui.dp(7));
        r.setBackground(Widgets.ripple());

        int tw = Ui.dp(144), th = Ui.dp(81);
        FrameLayout tb = new FrameLayout(m);
        YtThumbView tv = new YtThumbView(m, 10f);
        tv.bind(v);
        tb.addView(tv, new FrameLayout.LayoutParams(tw, th));
        if (v.durationSec > 0) {
            TextView dur = Widgets.pillBadge(m, Ui.time(v.durationSec * 1000L), 0xCC000000, 0xFFFFFFFF);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            dlp.setMargins(0, 0, Ui.dp(5), Ui.dp(5));
            tb.addView(dur, dlp);
        }
        r.addView(tb, new LinearLayout.LayoutParams(tw, th));

        LinearLayout col = new LinearLayout(m);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(12), 0, Ui.dp(4), 0);
        TextView t = Ui.text(m, v.title, 14f, Ui.semibold, Ui.text());
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t, Widgets.MW);
        StringBuilder sb = new StringBuilder(v.author);
        if (!v.views.isEmpty()) sb.append(" · ").append(v.views);
        TextView st = Ui.text(m, sb.toString(), 12f, Ui.regular, Ui.text2());
        st.setPadding(0, Ui.dp(4), 0, 0);
        col.addView(st, Widgets.MW);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Icons.IconView moreBtn = new Icons.IconView(m, Icons.MORE, 18, Ui.text2());
        moreBtn.setBackground(Widgets.ripple());
        moreBtn.setOnClickListener(vv -> showYtVideoMenu(m, v, vv));
        r.addView(moreBtn, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30)));

        r.setOnClickListener(vv -> openVideo(m, v));
        r.setOnLongClickListener(vv -> { showYtVideoMenu(m, v, vv); return true; });
        return r;
    }

    private static View buildYtCard(MainActivity m, YtVideo v, int widthPx, boolean fullBleed) {
        int h = (int) (widthPx * 9f / 16f);
        float rad = fullBleed ? 0f : 12f;
        LinearLayout card = new LinearLayout(m);
        card.setOrientation(LinearLayout.VERTICAL);

        FrameLayout thumbBox = new FrameLayout(m);
        YtThumbView tv = new YtThumbView(m, rad);
        tv.bind(v);
        thumbBox.addView(tv, new FrameLayout.LayoutParams(widthPx, h));

        if (v.durationSec > 0) {
            TextView dur = Ui.text(m, Ui.time(v.durationSec * 1000L), 11f, Ui.bold, 0xFFFFFFFF);
            dur.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2));
            dur.setBackground(Ui.round(0xCC000000, 5));
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            dlp.setMargins(0, 0, Ui.dp(8), Ui.dp(8));
            thumbBox.addView(dur, dlp);
        }
        card.addView(thumbBox, new LinearLayout.LayoutParams(widthPx, h));

        LinearLayout metaRow = new LinearLayout(m);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.TOP);
        metaRow.setPadding(Ui.dp(fullBleed ? 12 : 2), Ui.dp(11), Ui.dp(fullBleed ? 6 : 0), Ui.dp(2));

        RemoteImgView chAvatar = new RemoteImgView(m, true);
        chAvatar.bindAvatar(v.channelAvatar, v.author);
        if (!v.channelId.isEmpty()) {
            chAvatar.setOnClickListener(vv -> m.push(new ChannelPage(m, v.channelId, v.author)));
        }
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36));
        avLp.topMargin = Ui.dp(2);
        avLp.rightMargin = Ui.dp(12);
        metaRow.addView(chAvatar, avLp);

        LinearLayout txtCol = new LinearLayout(m);
        txtCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(m, v.title, 15f, Ui.semibold, Ui.text());
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        txtCol.addView(title, Widgets.MW);

        StringBuilder meta = new StringBuilder(v.author);
        if (!v.views.isEmpty()) meta.append(" · ").append(v.views);
        if (!v.published.isEmpty()) meta.append(" · ").append(v.published);
        TextView sub = Ui.text(m, meta.toString(), 12.5f, Ui.regular, Ui.text2());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(3);
        txtCol.addView(sub, slp);
        metaRow.addView(txtCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Icons.IconView moreBtn = new Icons.IconView(m, Icons.MORE, 18, Ui.text2());
        moreBtn.setBackground(Widgets.ripple());
        moreBtn.setOnClickListener(vv -> showYtVideoMenu(m, v, vv));
        metaRow.addView(moreBtn, new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32)));

        card.addView(metaRow, new LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnClickListener(vv -> openVideo(m, v));
        card.setOnLongClickListener(vv -> { showYtVideoMenu(m, v, vv); return true; });
        Ui.press(card, 0.985f);
        return card;
    }

    static void showYtVideoMenu(MainActivity m, YtVideo v, View anchorView) {
        float[] o = Glass.abs(anchorView);
        RectF r = new RectF(o[0], o[1], o[0] + anchorView.getWidth(), o[1] + anchorView.getHeight());
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.yt_play_in_app), Icons.PLAY, false, () -> openVideo(m, v)));
        es.add(new GlassMenu.Entry(m.getString(R.string.yt_watch_later) + "에 저장", Icons.CLOCK, false, () -> {
            addToYtPlaylist(m, "WL", v);
            Toast.makeText(m, "나중에 볼 동영상에 저장했습니다", Toast.LENGTH_SHORT).show();
        }));
        es.add(new GlassMenu.Entry(m.getString(R.string.add_to_playlist), Icons.PLAYLIST, false, () -> showSaveToPlaylistSheet(m, v, anchorView)));
        if (!v.channelId.isEmpty()) {
            es.add(new GlassMenu.Entry(v.author + " 채널 이동", Icons.CHANNEL, false, () -> m.push(new ChannelPage(m, v.channelId, v.author))));
        }
        es.add(new GlassMenu.Entry(m.getString(R.string.yt_download), Icons.DOWNLOAD, false, () -> showDownloadSheet(m, v, null, anchorView)));
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
            TextView mBtn = Ui.text(m, m.getString(R.string.yt_microg_signin), 13f, Ui.semibold, Ui.ACCENT);
            mBtn.setPadding(Ui.dp(14), Ui.dp(9), Ui.dp(14), Ui.dp(9));
            mBtn.setBackground(Ui.round(Ui.dark ? 0x22FFFFFF : 0x14000000, 12));
            mBtn.setOnClickListener(v -> startMicroGAccountLogin(m, m::reloadAll));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.leftMargin = Ui.dp(8);
            btnRow.addView(mBtn, mlp);
        }
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.bottomMargin = Ui.dp(14);
        form.addView(btnRow, brlp);

        String curPiped = MainActivity.prefs(m).getString(PREF_PIPED_URL, DEFAULT_PIPED);
        String curKey = SecureVault.get(m, MainActivity.prefs(m), PREF_API_KEY);
        EditText pipedEt = Widgets.formField(m, m.getString(R.string.yt_piped_instance), DEFAULT_PIPED, curPiped,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, form);
        EditText keyEt = Widgets.formField(m, m.getString(R.string.yt_api_key_label), m.getString(R.string.yt_api_key_hint), curKey,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, form);
        EditText chanEt = Widgets.formField(m, m.getString(R.string.yt_add_channel), "UC... (채널 ID)", "",
                InputType.TYPE_CLASS_TEXT, form);
        Widgets.showFormSheet(m, m.getString(R.string.yt_source_settings), form, m.getString(R.string.save), () -> {
            String pu = pipedEt.getText().toString().trim();
            if (pu.isEmpty()) pu = DEFAULT_PIPED;
            if (!pu.startsWith("https://")) {
                Toast.makeText(m, "Piped 인스턴스는 HTTPS 주소만 허용됩니다.", Toast.LENGTH_SHORT).show();
                pu = DEFAULT_PIPED;
            }
            SharedPreferences sp = MainActivity.prefs(m);
            sp.edit().putString(PREF_PIPED_URL, pu).apply();
            SecureVault.put(m, sp, PREF_API_KEY, keyEt.getText().toString().trim());
            String ch = chanEt.getText().toString().trim();
            if (!ch.isEmpty()) addChannelSubscription(m, ch, ch);
            m.reloadAll();
        });
    }

    // ---- Reusable Circular Avatar / Rounded Banner Remote Image View ----
    private static final class RemoteImgView extends View {
        private final boolean circle;
        private float radiusDp = 12f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private Bitmap bmp;
        private String boundUrl = "";
        private String fallbackInitial = "Y";
        private int fallbackColor = 0xFF3A3A3C;
        private boolean isBanner;

        RemoteImgView(Context c, boolean circle) {
            super(c);
            this.circle = circle;
            tp.setTypeface(Ui.bold);
            tp.setColor(0xFFFFFFFF);
            tp.setTextAlign(Paint.Align.CENTER);
        }

        void setRadiusDp(float r) { this.radiusDp = r; }

        void bindAvatar(String url, String name) {
            this.isBanner = false;
            this.fallbackInitial = (name != null && !name.trim().isEmpty()) ? name.trim().substring(0, 1).toUpperCase(Locale.US) : "Y";
            this.fallbackColor = channelColor(name);
            bindUrlInternal(url);
        }

        void bindBanner(String url, String name) {
            this.isBanner = true;
            this.fallbackColor = channelColor(name);
            bindUrlInternal(url);
        }

        private void bindUrlInternal(String url) {
            this.boundUrl = url == null ? "" : url.trim();
            this.bmp = this.boundUrl.isEmpty() ? null : IMAGES.get(this.boundUrl.startsWith("//") ? "https:" + this.boundUrl : this.boundUrl);
            invalidate();
            if (bmp == null && !this.boundUrl.isEmpty()) {
                final String target = this.boundUrl;
                loadImageUrl(target, b -> {
                    if (target.equals(boundUrl)) {
                        bmp = b;
                        invalidate();
                    }
                });
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            rect.set(0, 0, w, h);
            float r = circle ? Math.min(w, h) / 2f : Ui.dpf(radiusDp);
            if (bmp != null && !bmp.isRecycled()) {
                c.save();
                clip.reset();
                clip.addRoundRect(rect, r, r, Path.Direction.CW);
                c.clipPath(clip);
                float s = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                float dx = (w - bmp.getWidth() * s) / 2f;
                float dy = (h - bmp.getHeight() * s) / 2f;
                c.translate(dx, dy);
                c.scale(s, s);
                c.drawBitmap(bmp, 0, 0, paint);
                c.restore();
            } else {
                paint.setColor(isBanner ? Ui.alpha(fallbackColor, Ui.dark ? 0.35f : 0.22f) : fallbackColor);
                c.drawRoundRect(rect, r, r, paint);
                if (!isBanner) {
                    tp.setTextSize(Math.min(w, h) * 0.42f);
                    Paint.FontMetrics fm = tp.getFontMetrics();
                    float ty = h / 2f - (fm.ascent + fm.descent) / 2f;
                    c.drawText(fallbackInitial, w / 2f, ty, tp);
                }
            }
        }
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
        private YtVideo video;
        private final LinearLayout watchRoot;
        private final FrameLayout videoStage;
        private final ScrollView detailsScroll;
        private final LinearLayout detailsBody;
        private final TextureView texture;
        private final WebView webFallback;
        private final ProgressBar spinner;
        private final TextView statusPill;
        private final FrameLayout chrome;
        private final TextView titleOverlay, speedBadge, qualityBadge, timeLabel;
        private final Widgets.Slider seekBar;
        private final Icons.IconView playBtn, fsBtn;

        // Watch sheet live views
        private TextView watchTitle, watchMeta, watchChannelName, watchChannelSubs, subBtn, likeLabel, descView, commentHeader, commentPreview;
        private RemoteImgView watchChannelAvatar, commentPreviewAvatar;
        private Icons.IconView likeIcon;
        private LinearLayout commentsPanel, commentsListBox, relatedSection, relatedHost;
        private TextView sortTopChip, sortNewestChip, loadMoreCommentsBtn;
        private boolean commentsOpen = false, descExpanded = false;
        private int commentSortMode = 0; // 0=인기순, 1=최신순
        private String commentsNextToken = "", commentsTopToken = "", commentsNewestToken = "";
        private List<YtComment> loadedComments = new ArrayList<>();

        private MediaPlayer mp;
        private MediaPlayer audioMp;
        private boolean videoReady = false, audioReady = false;
        private Surface surface;
        private ResolvedStream currentStream;
        private QualityOption activeQuality;
        private int pendingSeekMs = 0;
        private boolean webMode = false;
        private boolean fullscreen = false;
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
                        if (audioMp != null && audioReady && playing) {
                            int aPos = audioMp.getCurrentPosition();
                            if (Math.abs(pos - aPos) > 260) audioMp.seekTo(pos);
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
            setBackgroundColor(Ui.bg());
            setClickable(true);

            watchRoot = new LinearLayout(m);
            watchRoot.setOrientation(LinearLayout.VERTICAL);
            addView(watchRoot, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Top status bar black band above 16:9 player in portrait watch mode
            View statusPad = new View(m);
            statusPad.setBackgroundColor(0xFF000000);
            statusPad.setTag("statusPad");
            watchRoot.addView(statusPad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.statusInset));

            // 1. 16:9 Video Stage (expands to MATCH_PARENT in fullscreen)
            videoStage = new FrameLayout(m);
            videoStage.setBackgroundColor(0xFF000000);
            int stageH = Math.max(Ui.dp(210), Math.round(m.layoutW() * 9f / 16f));
            watchRoot.addView(videoStage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageH));

            texture = new TextureView(m);
            texture.setSurfaceTextureListener(this);
            texture.setOnClickListener(vv -> toggleChrome());
            videoStage.addView(texture, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            webFallback = new WebView(m);
            webFallback.setBackgroundColor(Color.BLACK);
            webFallback.setVisibility(GONE);
            WebSettings ws = webFallback.getSettings();
            hardenWebSettings(ws);
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
                    String u = req != null && req.getUrl() != null ? req.getUrl().toString() : null;
                    return !isTrustedGoogleOrYouTubeUrl(u);
                }
            });
            videoStage.addView(webFallback, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout centerBox = new LinearLayout(m);
            centerBox.setOrientation(LinearLayout.VERTICAL);
            centerBox.setGravity(Gravity.CENTER);
            spinner = new ProgressBar(m);
            centerBox.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
            statusPill = Ui.text(m, m.getString(R.string.yt_resolving_stream), 12f, Ui.medium, 0xDDFFFFFF);
            statusPill.setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5));
            statusPill.setBackground(Ui.round(0x88000000, 100));
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stlp.topMargin = Ui.dp(8);
            centerBox.addView(statusPill, stlp);
            videoStage.addView(centerBox, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            // Video Player Overlay Chrome (clean YouTube transport controls)
            chrome = new FrameLayout(m);
            chrome.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x99000000, 0x22000000, 0xB8000000}));
            videoStage.addView(chrome, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout topBar = new LinearLayout(m);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(8), Ui.dp(4));
            topBar.addView(iconBtn(Icons.BACK, vv -> {
                if (fullscreen) setFullscreen(false); else close();
            }));
            titleOverlay = Ui.text(m, v.title, 14f, Ui.semibold, 0xFFFFFFFF);
            titleOverlay.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            topBar.addView(titleOverlay, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            speedBadge = badgePill("1.0x", this::showSpeedSheet);
            qualityBadge = badgePill("HD", this::showQualitySheet);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.leftMargin = Ui.dp(6);
            topBar.addView(speedBadge);
            topBar.addView(qualityBadge, qlp);
            topBar.addView(iconBtn(Icons.PIP, vv -> m.enterPip()));
            topBar.addView(iconBtn(Icons.MORE, this::showMoreSheet));
            chrome.addView(topBar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

            // Center Play/Pause + Rewind/Forward controls (authentic YouTube)
            LinearLayout midRow = new LinearLayout(m);
            midRow.setOrientation(LinearLayout.HORIZONTAL);
            midRow.setGravity(Gravity.CENTER);
            midRow.addView(circleTransport(Icons.REWIND, 42, 20, vv -> seekDelta(-10)));
            playBtn = new Icons.IconView(m, Icons.PAUSE, 26, 0xFFFFFFFF);
            playBtn.setBackground(Ui.round(0x88000000, 100));
            playBtn.setOnClickListener(vv -> togglePlay());
            Ui.press(playBtn, 0.88f);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(54), Ui.dp(54));
            plp.setMargins(Ui.dp(26), 0, Ui.dp(26), 0);
            midRow.addView(playBtn, plp);
            midRow.addView(circleTransport(Icons.FORWARD, 42, 20, vv -> seekDelta(10)));
            chrome.addView(midRow, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            // Bottom time + fullscreen toggle + seekbar
            LinearLayout botBox = new LinearLayout(m);
            botBox.setOrientation(LinearLayout.VERTICAL);
            botBox.setPadding(Ui.dp(10), 0, Ui.dp(8), Ui.dp(4));

            LinearLayout timeRow = new LinearLayout(m);
            timeRow.setGravity(Gravity.CENTER_VERTICAL);
            timeLabel = Ui.text(m, "0:00 / 0:00", 11.5f, Ui.semibold, 0xEEFFFFFF);
            timeLabel.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            timeRow.addView(timeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            fsBtn = iconBtn(Icons.FULLSCREEN, vv -> setFullscreen(!fullscreen));
            timeRow.addView(fsBtn);
            botBox.addView(timeRow, Widgets.MW);

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
            botBox.addView(seekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(24)));
            chrome.addView(botBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            // 2. Scrollable YouTube Watch Sheet (Title, Channel+Subscribe, Actions, Comments, Up Next)
            detailsScroll = new ScrollView(m);
            detailsScroll.setVerticalScrollBarEnabled(false);
            detailsScroll.setBackgroundColor(Ui.bg());
            detailsBody = new LinearLayout(m);
            detailsBody.setOrientation(LinearLayout.VERTICAL);
            detailsBody.setPadding(0, Ui.dp(12), 0, Ui.navInset + Ui.dp(24));
            detailsScroll.addView(detailsBody, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            watchRoot.addView(detailsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            buildWatchSheet(v);
            loadVideoContent(v);

            openP.onUpdate = () -> {
                float k = Ui.clamp(openP.value, 0f, 1f);
                setAlpha(k);
                setTranslationY((1f - k) * Ui.dpf(28));
            };
            openP.onEnd = () -> {
                if (closing) {
                    m.setFullscreenImmersive(false);
                    releasePlayer();
                    try { webFallback.destroy(); } catch (Throwable ignored) {}
                    m.root.removeView(this);
                }
            };
            openP.snap(0f);
            openP.to(1f);
        }

        private void buildWatchSheet(YtVideo v) {
            detailsBody.removeAllViews();
            commentsOpen = false;
            descExpanded = false;
            commentSortMode = 0;
            commentsNextToken = commentsTopToken = commentsNewestToken = "";
            loadedComments.clear();

            // 1. Title & Meta (authentic YouTube watch hierarchy)
            LinearLayout headBox = new LinearLayout(m);
            headBox.setOrientation(LinearLayout.VERTICAL);
            headBox.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(8));
            watchTitle = Ui.text(m, v.title, 18.5f, Ui.bold, Ui.text());
            watchTitle.setSingleLine(false);
            watchTitle.setMaxLines(3);
            headBox.addView(watchTitle, Widgets.MW);

            String metaStr = (v.views.isEmpty() ? "" : v.views) + (v.published.isEmpty() ? "" : (v.views.isEmpty() ? "" : " · ") + v.published);
            watchMeta = Ui.text(m, metaStr.isEmpty() ? "정보 불러오는 중…" : metaStr + " · …더보기", 12.5f, Ui.regular, Ui.text2());
            watchMeta.setPadding(0, Ui.dp(5), 0, Ui.dp(4));
            headBox.addView(watchMeta, Widgets.MW);

            descView = Ui.text(m, "", 13f, Ui.regular, Ui.text());
            descView.setSingleLine(false);
            descView.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            descView.setBackground(Ui.round(Ui.chip(), 12));
            descView.setVisibility(GONE);
            headBox.addView(descView, Widgets.MW);

            headBox.setOnClickListener(vv -> {
                if (descView.getText().length() == 0) return;
                descExpanded = !descExpanded;
                descView.setVisibility(descExpanded ? VISIBLE : GONE);
            });
            detailsBody.addView(headBox, Widgets.MW);

            // 2. Channel Row + Monochrome Subscribe Toggle
            LinearLayout chRow = new LinearLayout(m);
            chRow.setOrientation(LinearLayout.HORIZONTAL);
            chRow.setGravity(Gravity.CENTER_VERTICAL);
            chRow.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(10));

            watchChannelAvatar = new RemoteImgView(m, true);
            watchChannelAvatar.bindAvatar(v.channelAvatar, v.author);
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38));
            avlp.rightMargin = Ui.dp(11);
            chRow.addView(watchChannelAvatar, avlp);

            LinearLayout chCol = new LinearLayout(m);
            chCol.setOrientation(LinearLayout.VERTICAL);
            watchChannelName = Ui.text(m, v.author, 15f, Ui.semibold, Ui.text());
            watchChannelSubs = Ui.text(m, "", 12f, Ui.regular, Ui.text2());
            chCol.addView(watchChannelName);
            chCol.addView(watchChannelSubs);
            chRow.addView(chCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            OnClickListener openCh = vv -> {
                if (!video.channelId.isEmpty()) {
                    close();
                    m.push(new ChannelPage(m, video.channelId, video.author));
                }
            };
            watchChannelAvatar.setOnClickListener(openCh);
            chCol.setOnClickListener(openCh);

            boolean subbed = isSubscribed(m, v.channelId);
            subBtn = Ui.text(m, m.getString(subbed ? R.string.yt_subscribed : R.string.yt_subscribe),
                    13f, Ui.semibold, subbed ? Ui.text() : (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF));
            subBtn.setPadding(Ui.dp(15), Ui.dp(8), Ui.dp(15), Ui.dp(8));
            subBtn.setBackground(Ui.round(subbed ? Ui.chip() : (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F), 100));
            subBtn.setOnClickListener(vv -> {
                String cid = video.channelId.isEmpty() ? ("CH_" + video.author) : video.channelId;
                boolean now = toggleSubscribe(m, cid, video.author);
                refreshSubBtn(now);
                Toast.makeText(m, video.author + (now ? " 채널을 구독했습니다" : " 구독을 취소했습니다"), Toast.LENGTH_SHORT).show();
            });
            Ui.press(subBtn, 0.92f);
            chRow.addView(subBtn);
            detailsBody.addView(chRow, Widgets.MW);

            // 3. Authentic YouTube Action Pill Strip (Split Like|Dislike, Share, Download, Save, Web)
            HorizontalScrollView actScroll = new HorizontalScrollView(m);
            actScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout actRow = new LinearLayout(m);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setPadding(Ui.dp(14), Ui.dp(2), Ui.dp(14), Ui.dp(12));

            boolean liked = isVideoLiked(m, v.id);
            LinearLayout splitVote = new LinearLayout(m);
            splitVote.setOrientation(LinearLayout.HORIZONTAL);
            splitVote.setGravity(Gravity.CENTER_VERTICAL);
            splitVote.setBackground(Ui.round(Ui.chip(), 100));
            LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            svlp.rightMargin = Ui.dp(8);

            LinearLayout likeHalf = new LinearLayout(m);
            likeHalf.setOrientation(LinearLayout.HORIZONTAL);
            likeHalf.setGravity(Gravity.CENTER_VERTICAL);
            likeHalf.setPadding(Ui.dp(13), Ui.dp(8), Ui.dp(11), Ui.dp(8));
            likeIcon = new Icons.IconView(m, liked ? Icons.THUMB_UP_FILL : Icons.THUMB_UP, 16, liked ? Ui.ACCENT : Ui.text());
            LinearLayout.LayoutParams lilp = new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
            lilp.rightMargin = Ui.dp(6);
            likeHalf.addView(likeIcon, lilp);
            likeLabel = Ui.text(m, m.getString(R.string.yt_like), 12.5f, Ui.semibold, liked ? Ui.ACCENT : Ui.text());
            likeHalf.addView(likeLabel);
            likeHalf.setOnClickListener(vv -> {
                boolean now = toggleLikeVideo(m, video);
                likeIcon.set(now ? Icons.THUMB_UP_FILL : Icons.THUMB_UP, now ? Ui.ACCENT : Ui.text());
                likeLabel.setTextColor(now ? Ui.ACCENT : Ui.text());
            });
            splitVote.addView(likeHalf);

            View vSep = new View(m);
            vSep.setBackgroundColor(Ui.separator());
            splitVote.addView(vSep, new LinearLayout.LayoutParams(Math.max(1, Ui.dp(0.8f)), Ui.dp(18)));

            Icons.IconView dislikeIcon = new Icons.IconView(m, Icons.THUMB_DOWN, 16, Ui.text());
            dislikeIcon.setPadding(Ui.dp(11), Ui.dp(8), Ui.dp(13), Ui.dp(8));
            dislikeIcon.setOnClickListener(vv -> Toast.makeText(m, "싫어요를 표시했습니다", Toast.LENGTH_SHORT).show());
            splitVote.addView(dislikeIcon, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(34)));
            actRow.addView(splitVote, svlp);

            actRow.addView(actionPill(Icons.SHARE, m.getString(R.string.yt_share), vv -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, video.title);
                    i.putExtra(Intent.EXTRA_TEXT, video.watchUrl());
                    m.startActivity(Intent.createChooser(i, m.getString(R.string.yt_share)));
                } catch (Throwable ignored) {}
            }));
            actRow.addView(actionPill(Icons.DOWNLOAD, "오프라인 저장", vv -> showDownloadSheet(m, video, currentStream, vv)));
            actRow.addView(actionPill(Icons.PLAYLIST, m.getString(R.string.yt_save_playlist), vv -> showSaveToPlaylistSheet(m, video, vv)));
            actRow.addView(actionPill(Icons.GLOBE, webMode ? "네이티브" : "웹 전환", vv -> switchPlaybackMode(!webMode)));
            actScroll.addView(actRow);
            detailsBody.addView(actScroll, Widgets.MW);

            // 4. Compact Comments Preview Card (tapping opens full YouTube Comments Panel!)
            LinearLayout commentCard = new LinearLayout(m);
            commentCard.setOrientation(LinearLayout.VERTICAL);
            commentCard.setBackground(Ui.round(Ui.chip(), 14));
            commentCard.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(14), Ui.dp(11));
            LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cclp.setMargins(Ui.dp(14), Ui.dp(2), Ui.dp(14), Ui.dp(12));

            commentHeader = Ui.text(m, "댓글", 13.5f, Ui.bold, Ui.text());
            commentCard.addView(commentHeader, Widgets.MW);

            LinearLayout prevRow = new LinearLayout(m);
            prevRow.setOrientation(LinearLayout.HORIZONTAL);
            prevRow.setGravity(Gravity.CENTER_VERTICAL);
            prevRow.setPadding(0, Ui.dp(7), 0, 0);
            commentPreviewAvatar = new RemoteImgView(m, true);
            commentPreviewAvatar.bindAvatar("", "C");
            LinearLayout.LayoutParams pavLp = new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24));
            pavLp.rightMargin = Ui.dp(9);
            prevRow.addView(commentPreviewAvatar, pavLp);

            commentPreview = Ui.text(m, m.getString(R.string.yt_loading_comments), 13f, Ui.regular, Ui.text());
            commentPreview.setSingleLine(false);
            commentPreview.setMaxLines(2);
            commentPreview.setEllipsize(TextUtils.TruncateAt.END);
            prevRow.addView(commentPreview, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            commentCard.addView(prevRow, Widgets.MW);

            commentCard.setOnClickListener(vv -> {
                if (loadedComments.isEmpty()) return;
                setCommentsPanelOpen(!commentsOpen);
            });
            Ui.press(commentCard, 0.985f);
            detailsBody.addView(commentCard, cclp);

            // 5. Full Interactive YouTube Comments Panel (Sort + Avatars + Badges + Expandable Replies + Pagination!)
            commentsPanel = new LinearLayout(m);
            commentsPanel.setOrientation(LinearLayout.VERTICAL);
            commentsPanel.setVisibility(GONE);

            LinearLayout cTop = new LinearLayout(m);
            cTop.setOrientation(LinearLayout.HORIZONTAL);
            cTop.setGravity(Gravity.CENTER_VERTICAL);
            cTop.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(12), Ui.dp(8));
            cTop.addView(Ui.text(m, "댓글", 17f, Ui.bold, Ui.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Icons.IconView closeCm = new Icons.IconView(m, Icons.CLOSE, 20, Ui.text());
            closeCm.setBackground(Widgets.ripple());
            closeCm.setOnClickListener(vv -> setCommentsPanelOpen(false));
            cTop.addView(closeCm, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
            commentsPanel.addView(cTop, Widgets.MW);

            LinearLayout sortRow = new LinearLayout(m);
            sortRow.setOrientation(LinearLayout.HORIZONTAL);
            sortRow.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10));
            sortTopChip = buildSortChip(m.getString(R.string.yt_sort_top), true, vv -> switchCommentSort(0));
            sortNewestChip = buildSortChip(m.getString(R.string.yt_sort_newest), false, vv -> switchCommentSort(1));
            LinearLayout.LayoutParams nclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nclp.leftMargin = Ui.dp(8);
            sortRow.addView(sortTopChip);
            sortRow.addView(sortNewestChip, nclp);
            commentsPanel.addView(sortRow, Widgets.MW);

            View cDiv = new View(m);
            cDiv.setBackgroundColor(Ui.separator());
            commentsPanel.addView(cDiv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.6f))));

            commentsListBox = new LinearLayout(m);
            commentsListBox.setOrientation(LinearLayout.VERTICAL);
            commentsPanel.addView(commentsListBox, Widgets.MW);

            loadMoreCommentsBtn = Ui.text(m, m.getString(R.string.yt_load_more_comments), 13.5f, Ui.semibold, Ui.ACCENT);
            loadMoreCommentsBtn.setGravity(Gravity.CENTER);
            loadMoreCommentsBtn.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
            loadMoreCommentsBtn.setBackground(Ui.round(Ui.chip(), 100));
            loadMoreCommentsBtn.setVisibility(GONE);
            loadMoreCommentsBtn.setOnClickListener(vv -> loadNextCommentsPage());
            LinearLayout.LayoutParams lmlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lmlp.setMargins(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(16));
            commentsPanel.addView(loadMoreCommentsBtn, lmlp);
            detailsBody.addView(commentsPanel, Widgets.MW);

            // 6. Up Next / Related Videos
            relatedSection = new LinearLayout(m);
            relatedSection.setOrientation(LinearLayout.VERTICAL);
            relatedSection.addView(Widgets.section(m, m.getString(R.string.yt_up_next)));
            relatedHost = new LinearLayout(m);
            relatedHost.setOrientation(LinearLayout.VERTICAL);
            relatedSection.addView(relatedHost, Widgets.MW);
            detailsBody.addView(relatedSection, Widgets.MW);
        }

        private TextView buildSortChip(String label, boolean sel, OnClickListener click) {
            TextView tv = Ui.text(m, label, 12.5f, sel ? Ui.semibold : Ui.medium,
                    sel ? (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF) : Ui.text());
            tv.setPadding(Ui.dp(13), Ui.dp(6), Ui.dp(13), Ui.dp(6));
            tv.setBackground(Ui.round(sel ? (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F) : Ui.chip(), 8));
            tv.setOnClickListener(click);
            Ui.press(tv, 0.94f);
            return tv;
        }

        private void updateSortChipStyles() {
            boolean top = commentSortMode == 0;
            sortTopChip.setBackground(Ui.round(top ? (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F) : Ui.chip(), 8));
            sortTopChip.setTextColor(top ? (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF) : Ui.text());
            sortNewestChip.setBackground(Ui.round(!top ? (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F) : Ui.chip(), 8));
            sortNewestChip.setTextColor(!top ? (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF) : Ui.text());
        }

        private void setCommentsPanelOpen(boolean open) {
            commentsOpen = open;
            commentsPanel.setVisibility(open ? VISIBLE : GONE);
            relatedSection.setVisibility(open ? GONE : VISIBLE);
            if (open) renderAllComments();
        }

        private void switchCommentSort(int mode) {
            if (commentSortMode == mode) return;
            String tok = (mode == 0) ? commentsTopToken : commentsNewestToken;
            commentSortMode = mode;
            updateSortChipStyles();
            if (tok == null || tok.isEmpty()) return;
            commentsListBox.removeAllViews();
            commentsListBox.addView(Widgets.caption(m, "댓글 정렬 중…"));
            loadCommentsContinuation(m, tok, (list, nextTok) -> {
                if (closing) return;
                loadedComments.clear();
                loadedComments.addAll(list);
                commentsNextToken = nextTok == null ? "" : nextTok;
                renderAllComments();
            });
        }

        private void loadNextCommentsPage() {
            if (commentsNextToken == null || commentsNextToken.isEmpty()) return;
            String tok = commentsNextToken;
            commentsNextToken = "";
            loadMoreCommentsBtn.setText("댓글 불러오는 중…");
            loadCommentsContinuation(m, tok, (more, nextTok) -> {
                if (closing) return;
                loadedComments.addAll(more);
                commentsNextToken = nextTok == null ? "" : nextTok;
                loadMoreCommentsBtn.setText(m.getString(R.string.yt_load_more_comments));
                renderAllComments();
            });
        }

        private void renderAllComments() {
            commentsListBox.removeAllViews();
            for (YtComment cm : loadedComments) {
                commentsListBox.addView(buildCommentRow(cm, false), Widgets.MW);
            }
            loadMoreCommentsBtn.setVisibility(commentsNextToken != null && !commentsNextToken.isEmpty() ? VISIBLE : GONE);
        }

        private View buildCommentRow(YtComment cm, boolean isReply) {
            LinearLayout wrap = new LinearLayout(m);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(Ui.dp(isReply ? 52 : 16), Ui.dp(10), Ui.dp(16), Ui.dp(10));

            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            int avSz = Ui.dp(isReply ? 24 : 32);
            RemoteImgView av = new RemoteImgView(m, true);
            av.bindAvatar(cm.authorAvatar, cm.author);
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(avSz, avSz);
            avlp.rightMargin = Ui.dp(11);
            avlp.topMargin = Ui.dp(2);
            row.addView(av, avlp);

            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);

            // Pinned badge if pinned
            if (cm.isPinned) {
                TextView pin = Ui.text(m, "고정됨", 11f, Ui.semibold, Ui.text2());
                pin.setPadding(0, 0, 0, Ui.dp(2));
                col.addView(pin);
            }

            // Author + owner pill + time
            LinearLayout auRow = new LinearLayout(m);
            auRow.setOrientation(LinearLayout.HORIZONTAL);
            auRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView auTv = Ui.text(m, cm.author, 12f, Ui.semibold, cm.isOwner ? (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF) : Ui.text2());
            if (cm.isOwner) {
                auTv.setPadding(Ui.dp(7), Ui.dp(1.5f), Ui.dp(7), Ui.dp(1.5f));
                auTv.setBackground(Ui.round(Ui.dark ? 0xFFD9D9D9 : 0xFF606060, 100));
            }
            auRow.addView(auTv);
            if (!cm.time.isEmpty()) {
                TextView tm = Ui.text(m, "  " + cm.time, 11.5f, Ui.regular, Ui.text3());
                auRow.addView(tm);
            }
            col.addView(auRow, Widgets.MW);

            // Comment body text
            TextView body = Ui.text(m, cm.text, 14f, Ui.regular, Ui.text());
            body.setSingleLine(false);
            body.setPadding(0, Ui.dp(4), 0, Ui.dp(6));
            col.addView(body, Widgets.MW);

            // Likes + Creator Heart row
            LinearLayout metaRow = new LinearLayout(m);
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            metaRow.addView(new Icons.IconView(m, Icons.THUMB_UP, 14, Ui.text2()), new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
            if (!cm.likes.isEmpty() && !"0".equals(cm.likes)) {
                TextView lk = Ui.text(m, cm.likes, 12f, Ui.medium, Ui.text2());
                lk.setPadding(Ui.dp(5), 0, Ui.dp(12), 0);
                metaRow.addView(lk);
            }
            if (cm.isHearted) {
                TextView ht = Ui.text(m, "♥ 크리에이터", 11.5f, Ui.semibold, 0xFFFF0033);
                ht.setPadding(Ui.dp(6), 0, 0, 0);
                metaRow.addView(ht);
            }
            col.addView(metaRow, Widgets.MW);

            // Expandable Replies button + inline replies container
            if (!isReply && (cm.replyCount > 0 || (cm.replyToken != null && !cm.replyToken.isEmpty()))) {
                LinearLayout repliesBox = new LinearLayout(m);
                repliesBox.setOrientation(LinearLayout.VERTICAL);
                repliesBox.setVisibility(cm.repliesExpanded ? VISIBLE : GONE);

                String repLbl = cm.replyCount > 0 ? m.getString(R.string.yt_replies_n, cm.replyCount) : "답글 보기";
                TextView repBtn = Ui.text(m, repLbl + (cm.repliesExpanded ? " ▴" : " ▾"), 12.5f, Ui.semibold, 0xFF3EA6FF);
                repBtn.setPadding(0, Ui.dp(8), Ui.dp(12), Ui.dp(4));
                repBtn.setOnClickListener(vv -> {
                    cm.repliesExpanded = !cm.repliesExpanded;
                    repBtn.setText(repLbl + (cm.repliesExpanded ? " ▴" : " ▾"));
                    repliesBox.setVisibility(cm.repliesExpanded ? VISIBLE : GONE);
                    if (cm.repliesExpanded && !cm.repliesLoaded && cm.replyToken != null && !cm.replyToken.isEmpty()) {
                        cm.repliesLoaded = true;
                        repliesBox.removeAllViews();
                        repliesBox.addView(Ui.text(m, "답글 불러오는 중…", 12f, Ui.regular, Ui.text2()));
                        loadCommentsContinuation(m, cm.replyToken, (reps, ignored) -> {
                            if (closing) return;
                            cm.replies.clear();
                            cm.replies.addAll(reps);
                            repliesBox.removeAllViews();
                            if (reps.isEmpty()) {
                                repliesBox.addView(Ui.text(m, "답글을 불러오지 못했습니다", 12f, Ui.regular, Ui.text2()));
                            } else {
                                for (YtComment rep : reps) repliesBox.addView(buildCommentRow(rep, true), Widgets.MW);
                            }
                        });
                    }
                });
                col.addView(repBtn);
                for (YtComment rep : cm.replies) repliesBox.addView(buildCommentRow(rep, true), Widgets.MW);
                col.addView(repliesBox, Widgets.MW);
            }

            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            wrap.addView(row, Widgets.MW);

            if (!isReply) {
                View sep = new View(m);
                sep.setBackgroundColor(Ui.separator());
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
                slp.topMargin = Ui.dp(8);
                wrap.addView(sep, slp);
            }
            return wrap;
        }

        private void refreshSubBtn(boolean subbed) {
            subBtn.setText(m.getString(subbed ? R.string.yt_subscribed : R.string.yt_subscribe));
            subBtn.setBackground(Ui.round(subbed ? Ui.chip() : (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F), 100));
            subBtn.setTextColor(subbed ? Ui.text() : (Ui.dark ? 0xFF0F0F0F : 0xFFFFFFFF));
        }

        private LinearLayout actionPill(int icon, String label, OnClickListener click) {
            LinearLayout p = new LinearLayout(m);
            p.setOrientation(LinearLayout.HORIZONTAL);
            p.setGravity(Gravity.CENTER_VERTICAL);
            p.setPadding(Ui.dp(13), Ui.dp(8), Ui.dp(14), Ui.dp(8));
            p.setBackground(Ui.round(Ui.chip(), 100));
            Icons.IconView iv = new Icons.IconView(m, icon, 16, Ui.text());
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
            ilp.rightMargin = Ui.dp(6);
            p.addView(iv, ilp);
            p.addView(Ui.text(m, label, 12.5f, Ui.semibold, Ui.text()));
            p.setOnClickListener(click);
            Ui.press(p, 0.93f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(8);
            p.setLayoutParams(lp);
            return p;
        }

        private void loadVideoContent(YtVideo v) {
            this.video = v;
            recordYtWatchHistory(m, v);
            titleOverlay.setText(v.title);
            spinner.setVisibility(VISIBLE);
            statusPill.setText(m.getString(R.string.yt_resolving_stream));
            statusPill.setVisibility(VISIBLE);
            currentStream = null;
            activeQuality = null;

            resolveStream(m, v.id, stream -> {
                if (closing || !v.id.equals(video.id)) return;
                currentStream = stream;
                if (stream.title != null && !stream.title.isEmpty()) {
                    video.title = stream.title;
                    titleOverlay.setText(video.title);
                    watchTitle.setText(video.title);
                }
                if (stream.author != null && !stream.author.isEmpty()) {
                    video.author = stream.author;
                    watchChannelName.setText(video.author);
                }
                if (stream.description != null && !stream.description.isEmpty()) {
                    video.description = stream.description;
                    descView.setText(video.description);
                }
                recordYtWatchHistory(m, video);
                if (stream.url != null && !stream.url.isEmpty()) {
                    if (!stream.qualities.isEmpty()) {
                        activeQuality = stream.qualities.get(0);
                        for (QualityOption qo : stream.qualities) {
                            if (qo.label.equals(stream.qualityLabel)) { activeQuality = qo; break; }
                        }
                    }
                    qualityBadge.setText(shortQualityTag(stream.qualityLabel));
                    startNativeStream(stream);
                } else {
                    Toast.makeText(m, m.getString(R.string.yt_stream_fallback_web), Toast.LENGTH_SHORT).show();
                    switchPlaybackMode(true);
                }
            });

            loadWatchDetails(m, v, d -> {
                if (closing || !v.id.equals(video.id)) return;
                if (!d.channelId.isEmpty()) video.channelId = d.channelId;
                if (!d.channelName.isEmpty()) {
                    video.author = d.channelName;
                    watchChannelName.setText(d.channelName);
                }
                if (!d.channelAvatar.isEmpty()) {
                    video.channelAvatar = d.channelAvatar;
                    watchChannelAvatar.bindAvatar(d.channelAvatar, video.author);
                }
                if (!d.subscribers.isEmpty()) watchChannelSubs.setText(d.subscribers);
                refreshSubBtn(isSubscribed(m, video.channelId));
                StringBuilder ms = new StringBuilder();
                if (!d.views.isEmpty()) ms.append(d.views);
                if (!d.date.isEmpty()) { if (ms.length() > 0) ms.append(" · "); ms.append(d.date); }
                if (!d.description.isEmpty()) {
                    descView.setText(d.description);
                    if (ms.length() > 0) ms.append(" · …더보기");
                }
                if (ms.length() > 0) watchMeta.setText(ms.toString());
                if (!d.likeCount.isEmpty()) likeLabel.setText(d.likeCount);

                loadedComments.clear();
                loadedComments.addAll(d.comments);
                commentsNextToken = d.commentsNextToken == null ? "" : d.commentsNextToken;
                commentsTopToken = d.commentsTopToken == null ? "" : d.commentsTopToken;
                commentsNewestToken = d.commentsNewestToken == null ? "" : d.commentsNewestToken;

                if (d.comments.isEmpty()) {
                    commentHeader.setText("댓글");
                    commentPreview.setText(m.getString(R.string.yt_no_comments));
                } else {
                    String cntLbl = !d.commentsCountText.isEmpty() ? ("댓글 " + d.commentsCountText)
                            : m.getString(R.string.yt_comments_count, d.comments.size());
                    commentHeader.setText(cntLbl);
                    YtComment first = d.comments.get(0);
                    commentPreviewAvatar.bindAvatar(first.authorAvatar, first.author);
                    commentPreview.setText(first.text);
                    if (commentsOpen) renderAllComments();
                }

                relatedHost.removeAllViews();
                for (YtVideo rel : d.related) {
                    relatedHost.addView(buildRelatedRow(rel), Widgets.MW);
                }
            });
        }

        private View buildRelatedRow(YtVideo rel) {
            LinearLayout r = new LinearLayout(m);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
            r.setBackground(Widgets.ripple());

            int tw = Ui.dp(136), th = Ui.dp(77);
            FrameLayout tb = new FrameLayout(m);
            YtThumbView tv = new YtThumbView(m, 10f);
            tv.bind(rel);
            tb.addView(tv, new FrameLayout.LayoutParams(tw, th));
            if (rel.durationSec > 0) {
                TextView dur = Widgets.pillBadge(m, Ui.time(rel.durationSec * 1000L), 0xCC000000, 0xFFFFFFFF);
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                dlp.setMargins(0, 0, Ui.dp(5), Ui.dp(5));
                tb.addView(dur, dlp);
            }
            r.addView(tb, new LinearLayout.LayoutParams(tw, th));

            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(12), 0, 0, 0);
            TextView t = Ui.text(m, rel.title, 14f, Ui.semibold, Ui.text());
            t.setSingleLine(false);
            t.setMaxLines(2);
            col.addView(t, Widgets.MW);
            String sub = rel.author + (rel.views.isEmpty() ? "" : " · " + rel.views);
            TextView st = Ui.text(m, sub, 12f, Ui.regular, Ui.text2());
            st.setPadding(0, Ui.dp(4), 0, 0);
            col.addView(st, Widgets.MW);
            r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            r.setOnClickListener(vv -> {
                releasePlayer();
                detailsScroll.scrollTo(0, 0);
                buildWatchSheet(rel);
                loadVideoContent(rel);
            });
            return r;
        }

        private void setFullscreen(boolean fs) {
            fullscreen = fs;
            View pad = watchRoot.findViewWithTag("statusPad");
            if (pad != null) pad.setVisibility(fs ? GONE : VISIBLE);
            detailsScroll.setVisibility(fs ? GONE : VISIBLE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) videoStage.getLayoutParams();
            if (fs) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.weight = 1f;
            } else {
                lp.height = Math.max(Ui.dp(210), Math.round(m.layoutW() * 9f / 16f));
                lp.weight = 0f;
            }
            videoStage.setLayoutParams(lp);
            fsBtn.set(fs ? Icons.FULLSCREEN_EXIT : Icons.FULLSCREEN, 0xFFFFFFFF);
            m.setFullscreenImmersive(fs);
            videoStage.post(this::updateTextureTransform);
        }

        private TextView badgePill(String text, OnClickListener click) {
            TextView t = Ui.text(m, text, 11.5f, Ui.bold, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(9), Ui.dp(5), Ui.dp(9), Ui.dp(5));
            t.setBackground(Ui.round(0x40000000, 100));
            t.setOnClickListener(click);
            Ui.press(t, 0.92f);
            return t;
        }

        private Icons.IconView circleTransport(int icon, int boxDp, int icDp, OnClickListener click) {
            Icons.IconView iv = new Icons.IconView(m, icon, icDp, 0xFFFFFFFF);
            iv.setBackground(Ui.round(0x66000000, 100));
            iv.setOnClickListener(click);
            Ui.press(iv, 0.88f);
            iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(boxDp), Ui.dp(boxDp)));
            return iv;
        }

        void setPipMode(boolean pip) {
            chrome.setVisibility(pip ? GONE : VISIBLE);
            if (pip) {
                detailsScroll.setVisibility(GONE);
                View pad = watchRoot.findViewWithTag("statusPad");
                if (pad != null) pad.setVisibility(GONE);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) videoStage.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.weight = 1f;
                videoStage.setLayoutParams(lp);
            } else if (!fullscreen) {
                setFullscreen(false);
            }
        }

        private void toggleChrome() {
            chromeVisible = !chromeVisible;
            chrome.animate().alpha(chromeVisible ? 1f : 0f).setDuration(180).start();
        }

        private void startNativeStream(ResolvedStream stream) {
            if (closing || webMode) return;
            releasePlayer();
            try {
                String ua = uaForClient(stream.clientUsed);
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", ua);

                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                if (stream.dualTrack) {
                    mp.setVolume(0f, 0f);
                    mp.setDataSource(new RangeMediaDataSource(stream.url, stream.videoContentLength, ua));
                } else {
                    mp.setDataSource(m, Uri.parse(stream.url), headers);
                }
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
                    audioMp.setDataSource(new RangeMediaDataSource(stream.audioUrl, stream.audioContentLength, ua));
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
            if (pendingSeekMs > 0) {
                try { mp.seekTo(pendingSeekMs); } catch (Throwable ignored) {}
            }
            if (dual && audioMp != null) {
                if (pendingSeekMs > 0) {
                    try { audioMp.seekTo(pendingSeekMs); } catch (Throwable ignored) {}
                }
                audioMp.start();
            }
            pendingSeekMs = 0;
            MAIN.removeCallbacks(tickRun);
            MAIN.post(tickRun);
        }

        private static String shortQualityTag(String lbl) {
            if (lbl == null || lbl.isEmpty()) return "HD";
            if (lbl.contains("자동") || lbl.contains("HLS")) return "자동";
            int sp = lbl.indexOf(' ');
            return sp > 0 ? lbl.substring(0, sp) : lbl;
        }

        private void showQualitySheet(View anchor) {
            if (currentStream == null || currentStream.qualities.isEmpty()) {
                Toast.makeText(m, "화질 목록을 불러오는 중입니다", Toast.LENGTH_SHORT).show();
                return;
            }
            List<QualityOption> opts = new ArrayList<>();
            for (QualityOption q : currentStream.qualities) if (!q.audioOnly) opts.add(q);
            if (opts.isEmpty()) return;
            String[] labels = new String[opts.size()];
            int sel = 0;
            for (int i = 0; i < opts.size(); i++) {
                QualityOption q = opts.get(i);
                labels[i] = q.label;
                if (activeQuality != null && q.label.equals(activeQuality.label)) sel = i;
            }
            Widgets.pickSheet(m, anchor, m.getString(R.string.yt_quality), labels, sel, idx -> {
                QualityOption picked = opts.get(idx);
                activeQuality = picked;
                if (mp != null && prepared) {
                    try { pendingSeekMs = mp.getCurrentPosition(); } catch (Throwable ignored) {}
                }
                currentStream.url = picked.videoUrl;
                currentStream.videoContentLength = picked.videoContentLength;
                currentStream.audioUrl = picked.audioUrl;
                currentStream.audioContentLength = picked.audioContentLength;
                currentStream.qualityLabel = picked.label;
                currentStream.clientUsed = picked.clientUsed;
                currentStream.hls = picked.hls;
                currentStream.dualTrack = picked.dualTrack;
                qualityBadge.setText(shortQualityTag(picked.label));
                if (webMode) switchPlaybackMode(false);
                else {
                    spinner.setVisibility(VISIBLE);
                    statusPill.setText(picked.label + " 전환 중…");
                    statusPill.setVisibility(VISIBLE);
                    startNativeStream(currentStream);
                }
            });
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
            if (mp != null) { try { mp.setSurface(surface); } catch (Throwable ignored) {} }
            updateTextureTransform();
        }

        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { updateTextureTransform(); }

        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            if (mp != null) { try { mp.setSurface(null); } catch (Throwable ignored) {} }
            if (surface != null) { surface.release(); surface = null; }
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
                        boolean wasPlaying = playing && mp.isPlaying();
                        PlaybackParams pp = mp.getPlaybackParams();
                        pp.setSpeed(speed);
                        mp.setPlaybackParams(pp);
                        if (!wasPlaying) mp.pause();
                        if (audioMp != null && audioReady) {
                            PlaybackParams app = audioMp.getPlaybackParams();
                            app.setSpeed(speed);
                            audioMp.setPlaybackParams(app);
                            if (!wasPlaying) audioMp.pause();
                        }
                    } catch (Throwable ignored) {}
                }
            });
        }

        private void showMoreSheet(View anchor) {
            float[] o = Glass.abs(anchor);
            RectF r = new RectF(o[0], o[1], o[0] + anchor.getWidth(), o[1] + anchor.getHeight());
            List<GlassMenu.Entry> es = new ArrayList<>();
            es.add(new GlassMenu.Entry(m.getString(R.string.yt_quality) + " (" + qualityBadge.getText() + ")",
                    Icons.ASPECT, false, () -> showQualitySheet(anchor)));
            es.add(new GlassMenu.Entry(m.getString(R.string.add_to_playlist),
                    Icons.PLAYLIST, false, () -> showSaveToPlaylistSheet(m, video, anchor)));
            es.add(new GlassMenu.Entry(m.getString(R.string.yt_download),
                    Icons.DOWNLOAD, false, () -> showDownloadSheet(m, video, currentStream, anchor)));
            es.add(new GlassMenu.Entry(m.getString(R.string.psa_title) + " (" + NpuVideo.summary(m) + ")",
                    Icons.PROSCALER, NpuVideo.enabled(m), () -> NpuVideo.showQuickSheet(m, r, texture)));
            es.add(new GlassMenu.Entry(m.getString(R.string.bpa_title) + " (" + NpuAudio.summary(m) + ")",
                    Icons.HEADPHONES, NpuAudio.enabled(m), () -> {
                close();
                m.push(new Settings.BitPerfectAssistPage(m));
            }));
            es.add(new GlassMenu.Entry(webMode ? m.getString(R.string.yt_mode_native) : m.getString(R.string.yt_mode_web),
                    Icons.GLOBE, webMode, () -> switchPlaybackMode(!webMode)));
            if (isNewPipeInstalled(m)) {
                es.add(new GlassMenu.Entry(m.getString(R.string.yt_open_newpipe), Icons.YOUTUBE, false, () -> openInNewPipe(m, video.id)));
            }
            es.add(new GlassMenu.Entry(m.getString(R.string.yt_open_external), Icons.GLOBE, false, () -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + video.id));
                    m.startActivity(Intent.createChooser(i, m.getString(R.string.yt_open_external)));
                } catch (Throwable ignored) {}
            }));
            GlassMenu.sheet(m, r, video.title, es, anchor);
        }

        private Icons.IconView iconBtn(int icon, OnClickListener click) {
            Icons.IconView iv = new Icons.IconView(m, icon, 20, 0xFFFFFFFF);
            iv.setBackground(Widgets.ripple());
            iv.setOnClickListener(click);
            Ui.press(iv, 0.88f);
            iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));
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
