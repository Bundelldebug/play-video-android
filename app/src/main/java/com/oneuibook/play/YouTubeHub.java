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
                MainActivity.prefs(m).edit()
                        .putString(PREF_GOOGLE_COOKIE, existing.trim())
                        .putString(PREF_GOOGLE_ACCOUNT, accName)
                        .apply();
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
                            MainActivity.prefs(m).edit()
                                    .putString(PREF_GOOGLE_OAUTH, tok)
                                    .putString(PREF_GOOGLE_ACCOUNT, accName)
                                    .apply();
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

    /** Silently exchanges a Google/microG MergeSession URL for real YouTube cookies without showing any login UI. */
    private static void exchangeMergeSessionSilent(MainActivity m, String accName, String mergeUrl) {
        try {
            WebView wv = new WebView(m);
            WebSettings ws = wv.getSettings();
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
                @Override public void onPageFinished(WebView view, String url) {
                    if (finished[0]) return;
                    cm.flush();
                    String ckYt = cm.getCookie("https://www.youtube.com");
                    String ckMob = cm.getCookie("https://m.youtube.com");
                    String merged = (ckYt != null ? ckYt : "") + (ckMob != null && !ckMob.equals(ckYt) ? "; " + ckMob : "");
                    if (merged.contains("SAPISID=") || merged.contains("__Secure-3PAPISID=") || merged.contains("SID=")) {
                        cleanup.run();
                        MainActivity.prefs(m).edit()
                                .putString(PREF_GOOGLE_COOKIE, merged.trim())
                                .putString(PREF_GOOGLE_ACCOUNT, accName)
                                .apply();
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
        return MainActivity.prefs(c).getString(PREF_GOOGLE_OAUTH, "").trim();
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
                } else if (!oauth.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + oauth);
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
                    v.channelId = extractChannelIdFromRenderer(vr);
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

    static final String PREF_YT_PLAYLISTS = "yt_custom_playlists_v1";
    static final String PREF_YT_LIKED = "yt_liked_ids_v1";

    static final class YtPlaylist {
        final String id;
        String title;
        final boolean system;
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
        String subscribers = "";
        String description = "";
        final List<YtVideo> videos = new ArrayList<>();
    }

    static final class WatchDetails {
        String channelId = "";
        String channelName = "";
        String subscribers = "";
        String views = "";
        String date = "";
        String likeCount = "";
        String description = "";
        final List<YtComment> comments = new ArrayList<>();
        final List<YtVideo> related = new ArrayList<>();
    }

    interface WatchCb { void onLoaded(WatchDetails details); }
    interface ChannelCb { void onLoaded(ChannelInfo info); }

    // ---- Subscriptions storage + InnerTube sync ----
    static List<String[]> getUserChannels(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String extra = MainActivity.prefs(c).getString(PREF_SUB_CHANNELS, "");
        if (!extra.isEmpty()) {
            for (String line : extra.split("\n")) {
                String[] parts = line.trim().split("\\|", 2);
                if (parts.length >= 1 && !parts[0].isEmpty() && seen.add(parts[0].trim())) {
                    out.add(new String[]{parts[0].trim(), parts.length > 1 ? parts[1].trim() : parts[0].trim(), "sub"});
                }
            }
        }
        return out;
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
        List<String[]> list = getUserChannels(c);
        boolean wasSub = false;
        StringBuilder sb = new StringBuilder();
        for (String[] ch : list) {
            if (id.equals(ch[0])) { wasSub = true; continue; }
            if (sb.length() > 0) sb.append('\n');
            sb.append(ch[0]).append('|').append(ch[1]);
        }
        boolean nowSub = !wasSub;
        if (nowSub) {
            String entry = id + "|" + label;
            String next = sb.length() == 0 ? entry : entry + "\n" + sb;
            MainActivity.prefs(c).edit().putString(PREF_SUB_CHANNELS, next).apply();
        } else {
            MainActivity.prefs(c).edit().putString(PREF_SUB_CHANNELS, sb.toString()).apply();
        }
        // Best-effort sync to Google account if signed in
        if (isGoogleSignedIn(c)) {
            EXEC.execute(() -> {
                try {
                    JSONObject body = new JSONObject()
                            .put("channelIds", new JSONArray().put(id))
                            .put("context", webContext());
                    innertubePost(c, nowSub ? "subscription/subscribe" : "subscription/unsubscribe",
                            body, "1", "2.20250301.00.00", WEB_UA, true);
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
                    JSONObject resp = innertubePost(c, "browse", body, "1", "2.20250301.00.00", WEB_UA, true);
                    if (resp != null) collectRenderers(resp, out, seen, 24);
                    if (!out.isEmpty()) src = "YouTube 구독 (Google)";
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

    static void loadChannelDetail(Context c, String channelId, String fallbackName, ChannelCb cb) {
        EXEC.execute(() -> {
            ChannelInfo info = new ChannelInfo();
            info.channelId = channelId == null ? "" : channelId.trim();
            info.name = (fallbackName == null || fallbackName.isEmpty()) ? "채널" : fallbackName;
            Set<String> seen = new HashSet<>();
            if (!info.channelId.isEmpty()) {
                try {
                    JSONObject body = new JSONObject()
                            .put("browseId", info.channelId)
                            .put("params", "EgZ2aWRlb3PyBgQKAjoA")
                            .put("context", webContext());
                    JSONObject resp = innertubePost(c, "browse", body, "1", "2.20250301.00.00", WEB_UA, true);
                    if (resp != null) {
                        JSONObject meta = resp.optJSONObject("metadata");
                        JSONObject cm = meta == null ? null : meta.optJSONObject("channelMetadataRenderer");
                        if (cm != null) {
                            String t = cm.optString("title");
                            if (!t.isEmpty()) info.name = t;
                            info.description = cm.optString("description");
                        }
                        JSONObject hdr = resp.optJSONObject("header");
                        if (hdr != null) {
                            String subTxt = findSubscriberText(hdr);
                            if (!subTxt.isEmpty()) info.subscribers = subTxt;
                        }
                        collectRenderers(resp, info.videos, seen, 24);
                        for (YtVideo v : info.videos) {
                            if (v.author.isEmpty() || "YouTube".equals(v.author)) v.author = info.name;
                            if (v.channelId.isEmpty()) v.channelId = info.channelId;
                        }
                    }
                } catch (Throwable ignored) {}
                if (info.videos.isEmpty()) {
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

    // ---- YouTube Playlists & Liked Videos (local persistence + Google sync) ----
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
                    JSONArray vs = o.optJSONArray("videos");
                    if (vs != null) {
                        for (int j = 0; j < vs.length(); j++) {
                            JSONObject vo = vs.optJSONObject(j);
                            if (vo == null) continue;
                            YtVideo v = new YtVideo(vo.optString("id"), vo.optString("title"), vo.optString("author"));
                            v.channelId = vo.optString("ch");
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

    private static void saveYtPlaylists(Context c, List<YtPlaylist> list) {
        try {
            JSONArray arr = new JSONArray();
            for (YtPlaylist pl : list) {
                JSONObject o = new JSONObject();
                o.put("id", pl.id);
                o.put("title", pl.title);
                o.put("sys", pl.system);
                JSONArray vs = new JSONArray();
                for (YtVideo v : pl.videos) {
                    vs.put(new JSONObject()
                            .put("id", v.id)
                            .put("title", v.title)
                            .put("author", v.author)
                            .put("ch", v.channelId)
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
                            body, "1", "2.20250301.00.00", WEB_UA, true);
                } catch (Throwable ignored) {}
            });
        }
        return !liked;
    }

    static void showSaveToPlaylistSheet(MainActivity m, YtVideo v, View anchor) {
        List<YtPlaylist> pls = getYtPlaylists(m);
        String[] labels = new String[pls.size() + 1];
        for (int i = 0; i < pls.size(); i++) {
            labels[i] = pls.get(i).title + " (" + pls.get(i).videos.size() + ")";
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

    /** Loads rich watch metadata, real InnerTube comments, and Up Next related videos in one call! */
    static void loadWatchDetails(Context c, YtVideo v, WatchCb cb) {
        final String pipedBase = MainActivity.prefs(c).getString(PREF_PIPED_URL, DEFAULT_PIPED).trim();
        final String apiKey = MainActivity.prefs(c).getString(PREF_API_KEY, "").trim();
        EXEC.execute(() -> {
            WatchDetails d = new WatchDetails();
            d.channelId = v.channelId == null ? "" : v.channelId;
            d.channelName = v.author == null ? "" : v.author;
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
            String commentsToken = null;
            try {
                JSONObject body = new JSONObject()
                        .put("videoId", v.id)
                        .put("context", webContext());
                JSONObject resp = innertubePost(c, "next", body, "1", "2.20250301.00.00", WEB_UA, true);
                if (resp != null) {
                    extractWatchMeta(resp, d);
                    if (!d.channelId.isEmpty()) v.channelId = d.channelId;
                    if (!d.channelName.isEmpty()) v.author = d.channelName;
                    if (!d.views.isEmpty()) v.views = d.views;
                    if (!d.date.isEmpty()) v.published = d.date;
                    if (!d.description.isEmpty()) v.description = d.description;

                    Set<String> seenRel = new HashSet<>();
                    seenRel.add(v.id);
                    collectRenderers(resp, d.related, seenRel, 14);

                    commentsToken = findCommentsContinuation(resp);
                    if (commentsToken != null && !commentsToken.isEmpty()) {
                        JSONObject cBody = new JSONObject()
                                .put("continuation", commentsToken)
                                .put("context", webContext());
                        JSONObject cResp = innertubePost(c, "next", cBody, "1", "2.20250301.00.00", WEB_UA, true);
                        if (cResp != null) collectInnerTubeComments(cResp, d.comments, 30);
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
                                    d.comments.add(new YtComment(
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
            if (d.comments.isEmpty() && !pipedBase.isEmpty()) {
                try {
                    String base = pipedBase.endsWith("/") ? pipedBase.substring(0, pipedBase.length() - 1) : pipedBase;
                    String json = httpGet(base + "/comments/" + v.id, 4000);
                    if (json != null) {
                        JSONArray arr = new JSONObject(json).optJSONArray("comments");
                        if (arr != null) {
                            for (int i = 0; i < Math.min(25, arr.length()); i++) {
                                JSONObject o = arr.getJSONObject(i);
                                d.comments.add(new YtComment(
                                        o.optString("author"),
                                        o.optString("commentText"),
                                        o.optString("commentedTime"),
                                        String.valueOf(o.optInt("likeCount", 0))));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            MAIN.post(() -> cb.onLoaded(d));
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

    private static void collectInnerTubeComments(Object node, List<YtComment> out, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            // Modern YouTube commentEntityPayload
            JSONObject cep = o.optJSONObject("commentEntityPayload");
            if (cep != null) {
                JSONObject props = cep.optJSONObject("properties");
                JSONObject author = cep.optJSONObject("author");
                JSONObject toolbar = cep.optJSONObject("toolbar");
                JSONObject content = props == null ? null : props.optJSONObject("content");
                String txt = content == null ? "" : content.optString("content");
                String au = author == null ? "" : author.optString("displayName");
                String time = props == null ? "" : props.optString("publishedTime");
                String likes = toolbar == null ? "" : toolbar.optString("likeCountNotliked");
                if (!txt.isEmpty()) out.add(new YtComment(au, txt, time, likes));
                return;
            }
            // Legacy commentRenderer
            JSONObject cr = o.optJSONObject("commentRenderer");
            if (cr != null) {
                String au = readYtText(cr.optJSONObject("authorText"));
                String txt = readYtText(cr.optJSONObject("contentText"));
                String time = readYtText(cr.optJSONObject("publishedTimeText"));
                String likes = readYtText(cr.optJSONObject("voteCount"));
                if (!txt.isEmpty()) out.add(new YtComment(au, txt, time, likes));
                return;
            }
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext() && out.size() < limit) {
                collectInnerTubeComments(o.opt(keys.next()), out, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < limit; i++) {
                collectInnerTubeComments(a.opt(i), out, limit);
            }
        }
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

    private interface ThumbCb { void onBitmap(Bitmap b); }

    // ==================================================================
    // Adaptive Floating Liquid Glass YouTube Pill Bar
    // - Phone UI: Vertical Liquid Glass pill on the RIGHT edge
    // - Foldable / Tablet UI: Horizontal Liquid Glass pill at the BOTTOM
    // ==================================================================
    static final class YtModePill extends View {
        interface Listener { void onModeSelected(int mode); }
        private static final int[] MODE_ICONS = {Icons.YOUTUBE, Icons.SUBSCRIPTIONS, Icons.CHANNEL, Icons.PLAYLIST};
        private static final int[] MODE_LABELS = {R.string.yt_tab_feed, R.string.yt_tab_subs, R.string.yt_tab_channels, R.string.yt_tab_playlists};

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
    // Unified Home + YouTube Hub Page (Tab 0 — merged Home & YouTube!)
    // Modes: 0=피드(홈+기기 동영상), 1=구독, 2=채널, 3=재생목록
    // ==================================================================
    static class HomeYouTubePage extends Page {
        private int mode = 0;
        private int category = 0; // 0=전체, 1=음악, 2=게임, 3=영화, 4=테크, 5=기기 동영상
        private String currentQuery = "";
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
            int[] titles = {R.string.tab_home, R.string.yt_tab_subs, R.string.yt_tab_channels, R.string.yt_tab_playlists};
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

            // Clean Prism-grade Account Strip
            LinearLayout topStrip = new LinearLayout(m);
            topStrip.setOrientation(LinearLayout.HORIZONTAL);
            topStrip.setGravity(Gravity.CENTER_VERTICAL);
            topStrip.setPadding(Ui.dp(18), 0, Ui.dp(14), Ui.dp(10));

            LinearLayout brand = new LinearLayout(m);
            brand.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout ytLogo = new FrameLayout(m);
            ytLogo.setBackground(Ui.round(0xFFFF0033, 7));
            ytLogo.addView(new Icons.IconView(m, Icons.PLAY, 12, 0xFFFFFFFF),
                    new FrameLayout.LayoutParams(Ui.dp(14), Ui.dp(14), Gravity.CENTER));
            brand.addView(ytLogo, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(19)));
            TextView brandTxt = Ui.text(m, signedIn ? getSignedInLabel(m) : "YouTube", 13.5f, Ui.semibold, Ui.text2());
            brandTxt.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
            brand.addView(brandTxt);
            topStrip.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (mg != null && mg.isMicroG) {
                TextView mgBtn = Ui.text(m, signedIn ? "microG" : "microG 연결", 12f, Ui.semibold, signedIn ? 0xFF34C759 : Ui.ACCENT);
                mgBtn.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
                GradientDrawable mbg = Ui.round(Ui.dark ? 0x1FFFFFFF : 0x10000000, 100);
                mbg.setStroke(Math.max(1, Ui.dp(0.8f)), signedIn ? 0x5534C759 : Ui.alpha(Ui.ACCENT, 0.35f));
                mgBtn.setBackground(mbg);
                mgBtn.setOnClickListener(v -> startMicroGAccountLogin(m, this::rebuild));
                Ui.press(mgBtn, 0.94f);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.rightMargin = Ui.dp(6);
                topStrip.addView(mgBtn, mlp);
            }

            TextView avatarBtn = Ui.text(m, signedIn ? "계정" : m.getString(R.string.yt_google_signin), 12f, Ui.semibold, 0xFFFFFFFF);
            avatarBtn.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
            avatarBtn.setBackground(Ui.round(signedIn ? 0xFF34C759 : Ui.ACCENT, 100));
            avatarBtn.setOnClickListener(v -> {
                if (signedIn) {
                    List<GlassMenu.Entry> es = new ArrayList<>();
                    es.add(new GlassMenu.Entry(getSignedInLabel(m), Icons.INFO, true, null));
                    if (mg != null && mg.isMicroG) {
                        es.add(new GlassMenu.Entry(m.getString(R.string.yt_microg_signin), Icons.YOUTUBE, false, () -> startMicroGAccountLogin(m, this::rebuild)));
                    }
                    es.add(new GlassMenu.Entry(m.getString(R.string.yt_google_signout), Icons.CLOSE, false, () -> { signOutGoogle(m); rebuild(); }));
                    float[] o = Glass.abs(v);
                    GlassMenu.show(m, new RectF(o[0], o[1], o[0] + v.getWidth(), o[1] + v.getHeight()), es);
                } else {
                    showGoogleLoginDialog(m, this::rebuild);
                }
            });
            Ui.press(avatarBtn, 0.92f);
            topStrip.addView(avatarBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(topStrip, Widgets.MW);

            if (mode == 0) buildFeedMode(m);
            else if (mode == 1) buildSubscriptionsMode(m);
            else if (mode == 2) buildChannelsMode(m);
            else buildPlaylistsMode(m);
        }

        private void buildFeedMode(MainActivity m) {
            // Compact local Continue Watching rail when user has local videos in progress
            List<VideoLibrary.Video> cw = VideoLibrary.continueWatching();
            if (!cw.isEmpty()) {
                content.addView(Widgets.section(m, m.getString(R.string.continue_watching)));
                LinearLayout rail = Widgets.rail(content, 12);
                for (VideoLibrary.Video v : cw) {
                    rail.addView(Widgets.continueCard(m, v, vv -> m.playVideo(v, cw)));
                }
            }

            // Search Pill
            LinearLayout searchPill = new LinearLayout(m);
            searchPill.setOrientation(LinearLayout.HORIZONTAL);
            searchPill.setGravity(Gravity.CENTER_VERTICAL);
            searchPill.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(7), Ui.dp(7));
            GradientDrawable sBg = Ui.round(Ui.card(), 100);
            sBg.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.separator());
            searchPill.setBackground(sBg);
            searchPill.addView(new Icons.IconView(m, Icons.SEARCH, 18, Ui.text2()), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));

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

            TextView goBtn = Ui.text(m, m.getString(R.string.search), 13f, Ui.semibold, 0xFFFFFFFF);
            goBtn.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
            goBtn.setBackground(Ui.round(0xFFFF0033, 100));
            Runnable triggerSearch = () -> {
                String q = et.getText().toString().trim();
                String vid = extractVideoId(q);
                if (vid != null && (q.contains("youtube.com") || q.contains("youtu.be") || RAW_ID_PAT.matcher(q).matches())) {
                    openVideo(m, new YtVideo(vid, "YouTube (" + vid + ")", "YouTube"));
                    return;
                }
                currentQuery = q;
                if (category == 5) category = 0;
                fetchVideos();
            };
            goBtn.setOnClickListener(v -> triggerSearch.run());
            et.setOnEditorActionListener((v, id, ev) -> { triggerSearch.run(); return true; });
            Ui.press(goBtn, 0.94f);
            searchPill.addView(goBtn);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            spLp.setMargins(Ui.dp(16), Ui.dp(6), Ui.dp(14), Ui.dp(8));
            content.addView(searchPill, spLp);

            // Category Chips (including local Device Videos!)
            String[] cats = {
                    m.getString(R.string.yt_cat_all),
                    m.getString(R.string.yt_cat_music),
                    m.getString(R.string.yt_cat_gaming),
                    m.getString(R.string.yt_cat_film),
                    m.getString(R.string.yt_cat_tech),
                    m.getString(R.string.yt_cat_device)
            };
            Widgets.Chips chips = new Widgets.Chips(m, cats, category, idx -> {
                category = idx;
                currentQuery = "";
                et.setText("");
                fetchVideos();
            });
            content.addView(chips, Widgets.MW);

            LinearLayout metaRow = new LinearLayout(m);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            metaRow.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(16), Ui.dp(8));
            statusLabel = Ui.text(m, m.getString(R.string.yt_loading), 12f, Ui.medium, Ui.text2());
            metaRow.addView(statusLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView cfgBtn = Ui.text(m, m.getString(R.string.yt_source_settings), 12f, Ui.semibold, Ui.ACCENT);
            cfgBtn.setOnClickListener(v -> showSourceSettingsSheet(m));
            metaRow.addView(cfgBtn);
            content.addView(metaRow, Widgets.MW);

            resultsHost = new LinearLayout(m);
            resultsHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(resultsHost, Widgets.MW);

            fetchVideos();
        }

        private void buildSubscriptionsMode(MainActivity m) {
            // Horizontal Subscribed Channels Avatar Strip
            List<String[]> chans = getUserChannels(m);
            if (chans.isEmpty()) chans = subscribedChannels(m);
            LinearLayout strip = Widgets.rail(content, 12);
            strip.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(12));
            for (String[] ch : chans) {
                LinearLayout item = new LinearLayout(m);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                String init = ch[1].isEmpty() ? "C" : ch[1].substring(0, 1).toUpperCase(Locale.US);
                TextView av = Ui.text(m, init, 18f, Ui.bold, 0xFFFFFFFF);
                av.setGravity(Gravity.CENTER);
                av.setBackground(Ui.round(channelColor(ch[1]), 100));
                item.addView(av, new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(50)));
                TextView nm = Ui.text(m, ch[1], 11.5f, Ui.medium, Ui.text2());
                nm.setGravity(Gravity.CENTER);
                nm.setMaxLines(1);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = Ui.dp(5);
                item.addView(nm, nlp);
                item.setOnClickListener(v -> m.push(new ChannelPage(m, ch[0], ch[1])));
                Ui.press(item, 0.93f);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ilp.rightMargin = Ui.dp(8);
                strip.addView(item, ilp);
            }

            // Status + Feed
            statusLabel = Ui.text(m, "구독 피드 불러오는 중…", 12.5f, Ui.medium, Ui.text2());
            statusLabel.setPadding(Ui.dp(20), Ui.dp(2), Ui.dp(16), Ui.dp(10));
            content.addView(statusLabel, Widgets.MW);

            resultsHost = new LinearLayout(m);
            resultsHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(resultsHost, Widgets.MW);

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

        private void buildChannelsMode(MainActivity m) {
            List<String[]> userChans = getUserChannels(m);
            if (!userChans.isEmpty()) {
                Widgets.Group g = new Widgets.Group(m, "내 구독 채널 (" + userChans.size() + ")");
                for (String[] ch : userChans) addChannelRow(m, g, ch[0], ch[1], true);
                content.addView(g, Widgets.MW);
            }

            Widgets.Group rec = new Widgets.Group(m, "추천 채널");
            for (String[] ch : CURATED_CHANNELS) {
                boolean sub = isSubscribed(m, ch[0]);
                addChannelRow(m, rec, ch[0], ch[1], sub);
            }
            content.addView(rec, Widgets.MW);

            LinearLayout addWrap = new LinearLayout(m);
            addWrap.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(8));
            addWrap.addView(Widgets.button(m, "+ 채널 ID로 구독 추가", false, v -> showSourceSettingsSheet(m)));
            content.addView(addWrap, Widgets.MW);
        }

        private void addChannelRow(MainActivity m, Widgets.Group g, String chId, String chName, boolean subscribed) {
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
            row.setBackground(Widgets.ripple());

            String init = chName.isEmpty() ? "C" : chName.substring(0, 1).toUpperCase(Locale.US);
            TextView av = Ui.text(m, init, 15f, Ui.bold, 0xFFFFFFFF);
            av.setGravity(Gravity.CENTER);
            av.setBackground(Ui.round(channelColor(chName), 100));
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38));
            avlp.rightMargin = Ui.dp(12);
            row.addView(av, avlp);

            TextView nm = Ui.text(m, chName, 15.5f, Ui.medium, Ui.text());
            row.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView subBtn = Ui.text(m, m.getString(subscribed ? R.string.yt_subscribed : R.string.yt_subscribe),
                    12.5f, Ui.semibold, subscribed ? Ui.text() : 0xFFFFFFFF);
            subBtn.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
            subBtn.setBackground(Ui.round(subscribed ? Ui.chip() : 0xFFFF0033, 100));
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

        private void buildPlaylistsMode(MainActivity m) {
            List<YtPlaylist> pls = getYtPlaylists(m);
            Widgets.Group g = new Widgets.Group(m, "YouTube 재생목록");
            for (YtPlaylist pl : pls) {
                int ic = "LL".equals(pl.id) ? Icons.THUMB_UP_FILL : ("WL".equals(pl.id) ? Icons.CLOCK : Icons.PLAYLIST);
                int tint = "LL".equals(pl.id) ? 0xFFFF0033 : Ui.ACCENT;
                g.row(ic, tint, pl.title, m.getString(R.string.n_videos, pl.videos.size()), true,
                        v -> m.push(new YtPlaylistDetailPage(m, pl.id)));
            }
            content.addView(g, Widgets.MW);

            LinearLayout act = new LinearLayout(m);
            act.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(8));
            act.addView(Widgets.button(m, "+ 새 YouTube 재생목록", false, v -> {
                LinearLayout form = new LinearLayout(m);
                form.setOrientation(LinearLayout.VERTICAL);
                EditText et = Widgets.formField(m, "이름", "재생목록 이름 입력", "", InputType.TYPE_CLASS_TEXT, form);
                Widgets.showFormSheet(m, "새 YouTube 재생목록", form, m.getString(R.string.ok), () -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) { createYtPlaylist(m, n); rebuild(); }
                });
            }));
            content.addView(act, Widgets.MW);

            if (!VideoLibrary.playlists.isEmpty()) {
                Widgets.Group loc = new Widgets.Group(m, "기기 재생목록");
                for (VideoLibrary.Playlist pl : VideoLibrary.playlists) {
                    loc.row(Icons.PLAYLIST, Ui.ACCENT, pl.name, m.getString(R.string.n_videos, pl.videoKeys.size()), true,
                            v -> m.openLibrarySection("playlists"));
                }
                content.addView(loc, Widgets.MW);
            }
        }

        private int rightContentWidth(MainActivity m) {
            int extraRight = (!m.railUi() && !m.tabletUi()) ? Ui.dp(68) : 0;
            return Math.max(Ui.dp(220), Widgets.contentW(m) - extraRight);
        }

        private void fetchVideos() {
            if (resultsHost == null) return;
            resultsHost.removeAllViews();
            if (category == 5) {
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
    // Channel Detail Page
    // ==================================================================
    static final class ChannelPage extends Page {
        private final String channelId;
        private String channelName;
        private LinearLayout bodyHost;

        ChannelPage(MainActivity m, String channelId, String channelName) {
            super(m, (channelName == null || channelName.isEmpty()) ? "채널" : channelName, true);
            this.channelId = channelId == null ? "" : channelId;
            this.channelName = (channelName == null || channelName.isEmpty()) ? "채널" : channelName;
            back = true;
            headerIcons = new int[0];
        }

        @Override void build() {
            MainActivity m = main;
            LinearLayout hdr = new LinearLayout(m);
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            hdr.setPadding(Ui.dp(18), Ui.dp(4), Ui.dp(18), Ui.dp(14));

            String init = channelName.substring(0, 1).toUpperCase(Locale.US);
            TextView av = Ui.text(m, init, 22f, Ui.bold, 0xFFFFFFFF);
            av.setGravity(Gravity.CENTER);
            av.setBackground(Ui.round(channelColor(channelName), 100));
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56));
            avlp.rightMargin = Ui.dp(14);
            hdr.addView(av, avlp);

            LinearLayout col = new LinearLayout(m);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView nameTv = Ui.text(m, channelName, 17.5f, Ui.bold, Ui.text());
            TextView subCountTv = Ui.text(m, "채널 동영상 불러오는 중…", 13f, Ui.regular, Ui.text2());
            col.addView(nameTv);
            col.addView(subCountTv);
            hdr.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            boolean sub = isSubscribed(m, channelId);
            TextView subBtn = Ui.text(m, m.getString(sub ? R.string.yt_subscribed : R.string.yt_subscribe),
                    13f, Ui.semibold, sub ? Ui.text() : 0xFFFFFFFF);
            subBtn.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
            subBtn.setBackground(Ui.round(sub ? Ui.chip() : 0xFFFF0033, 100));
            subBtn.setOnClickListener(v -> {
                boolean now = toggleSubscribe(m, channelId, channelName);
                subBtn.setText(m.getString(now ? R.string.yt_subscribed : R.string.yt_subscribe));
                subBtn.setTextColor(now ? Ui.text() : 0xFFFFFFFF);
                subBtn.setBackground(Ui.round(now ? Ui.chip() : 0xFFFF0033, 100));
            });
            Ui.press(subBtn, 0.92f);
            hdr.addView(subBtn);
            content.addView(hdr, Widgets.MW);

            bodyHost = new LinearLayout(m);
            bodyHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(bodyHost, Widgets.MW);

            loadChannelDetail(m, channelId, channelName, info -> {
                if (bodyHost == null) return;
                channelName = info.name;
                nameTv.setText(info.name);
                subCountTv.setText(info.subscribers.isEmpty() ? ("동영상 " + info.videos.size() + "개") : info.subscribers);
                bodyHost.removeAllViews();
                if (info.videos.isEmpty()) {
                    bodyHost.addView(Widgets.caption(m, "불러온 채널 동영상이 없습니다."));
                    return;
                }
                renderVideoCards(m, bodyHost, info.videos, Widgets.contentW(m));
            });
        }
    }

    // ==================================================================
    // YouTube Playlist Detail Page
    // ==================================================================
    static final class YtPlaylistDetailPage extends Page {
        private final String playlistId;

        YtPlaylistDetailPage(MainActivity m, String playlistId) {
            super(m, "재생목록", true);
            this.playlistId = playlistId;
            back = true;
            headerIcons = new int[0];
        }

        @Override void build() {
            MainActivity m = main;
            YtPlaylist target = null;
            for (YtPlaylist pl : getYtPlaylists(m)) if (pl.id.equals(playlistId)) { target = pl; break; }
            if (target == null) return;
            title = target.title;
            if (bigTitle != null) bigTitle.setText(target.title);
            if (target.videos.isEmpty()) {
                content.addView(Widgets.caption(m, "저장된 동영상이 없습니다. 동영상 메뉴나 재생 화면에서 '재생목록'을 눌러 추가하세요."));
                return;
            }
            final YtPlaylist pl = target;
            LinearLayout topAct = new LinearLayout(m);
            topAct.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(14));
            topAct.addView(Widgets.button(m, "첫 동영상 재생 (" + pl.videos.size() + ")", true, v -> openVideo(m, pl.videos.get(0))));
            content.addView(topAct, Widgets.MW);
            renderVideoCards(m, content, pl.videos, Widgets.contentW(m));
        }
    }

    // ---- Shared YouTube Video Card Grid Renderer ----
    private static int channelColor(String author) {
        int[] palette = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFFB8C00, 0xFF00ACC1, 0xFFD81B60};
        int h = author == null ? 0 : Math.abs(author.hashCode());
        return palette[h % palette.length];
    }

    private static void renderVideoCards(MainActivity m, LinearLayout host, List<YtVideo> videos, int contentW) {
        int cols = contentW / Ui.density >= 840 ? 3 : (contentW / Ui.density >= 560 ? 2 : 1);
        int gap = Ui.dp(14);
        int pad = Ui.dp(16);
        int avail = contentW - pad * 2 - gap * (cols - 1);
        int cardW = Math.max(Ui.dp(150), avail / cols);

        for (int i = 0; i < videos.size(); i += cols) {
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(pad, 0, pad, Ui.dp(18));
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
            host.addView(row, Widgets.MW);
        }
    }

    private static View buildYtCard(MainActivity m, YtVideo v, int widthPx) {
        int h = (int) (widthPx * 9f / 16f);
        float rad = Ui.gStyle ? 18f : 16f;
        LinearLayout card = new LinearLayout(m);
        card.setOrientation(LinearLayout.VERTICAL);

        FrameLayout thumbBox = new FrameLayout(m);
        YtThumbView tv = new YtThumbView(m, rad);
        tv.bind(v);
        thumbBox.addView(tv, new FrameLayout.LayoutParams(widthPx, h));

        if (v.durationSec > 0) {
            TextView dur = Widgets.pillBadge(m, Ui.time(v.durationSec * 1000L), 0xCC000000, 0xFFFFFFFF);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            dlp.setMargins(0, 0, Ui.dp(10), Ui.dp(10));
            thumbBox.addView(dur, dlp);
        }
        card.addView(thumbBox, new LinearLayout.LayoutParams(widthPx, h));

        LinearLayout metaRow = new LinearLayout(m);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.TOP);
        metaRow.setPadding(Ui.dp(2), Ui.dp(11), 0, Ui.dp(2));

        String initial = (v.author != null && !v.author.isEmpty()) ? v.author.substring(0, 1).toUpperCase(Locale.US) : "Y";
        TextView chAvatar = Ui.text(m, initial, 14f, Ui.bold, 0xFFFFFFFF);
        chAvatar.setGravity(Gravity.CENTER);
        chAvatar.setBackground(Ui.round(channelColor(v.author), 100));
        if (!v.channelId.isEmpty()) {
            chAvatar.setOnClickListener(vv -> m.push(new ChannelPage(m, v.channelId, v.author)));
        }
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36));
        avLp.topMargin = Ui.dp(2);
        avLp.rightMargin = Ui.dp(11);
        metaRow.addView(chAvatar, avLp);

        LinearLayout txtCol = new LinearLayout(m);
        txtCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(m, v.title, 14.5f, Ui.semibold, Ui.text());
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        txtCol.addView(title, Widgets.MW);

        StringBuilder meta = new StringBuilder(v.author);
        if (!v.views.isEmpty()) meta.append(" · ").append(v.views);
        if (!v.published.isEmpty()) meta.append(" · ").append(v.published);
        TextView sub = Ui.text(m, meta.toString(), 12f, Ui.regular, Ui.text2());
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
        Ui.press(card, Ui.gStyle ? 0.985f : 0.97f);
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
        private Icons.IconView likeIcon;
        private LinearLayout commentsExpandedBox, relatedHost;
        private boolean commentsExpanded = false, descExpanded = false;
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
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setUserAgentString(WEB_UA);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webFallback, true);
            webFallback.setWebChromeClient(new WebChromeClient());
            webFallback.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) { return false; }
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
            commentsExpanded = false;
            descExpanded = false;
            loadedComments.clear();

            // Title & Meta
            LinearLayout headBox = new LinearLayout(m);
            headBox.setOrientation(LinearLayout.VERTICAL);
            headBox.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(8));
            watchTitle = Ui.text(m, v.title, 18f, Ui.bold, Ui.text());
            watchTitle.setSingleLine(false);
            watchTitle.setMaxLines(3);
            headBox.addView(watchTitle, Widgets.MW);

            String metaStr = (v.views.isEmpty() ? "" : v.views) + (v.published.isEmpty() ? "" : (v.views.isEmpty() ? "" : " · ") + v.published);
            watchMeta = Ui.text(m, metaStr.isEmpty() ? "정보 불러오는 중…" : metaStr + " · 더보기", 12.5f, Ui.regular, Ui.text2());
            watchMeta.setPadding(0, Ui.dp(5), 0, Ui.dp(4));
            headBox.addView(watchMeta, Widgets.MW);

            descView = Ui.text(m, "", 13f, Ui.regular, Ui.text2());
            descView.setSingleLine(false);
            descView.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            descView.setBackground(Ui.round(Ui.chip(), 14));
            descView.setVisibility(GONE);
            headBox.addView(descView, Widgets.MW);

            headBox.setOnClickListener(vv -> {
                if (descView.getText().length() == 0) return;
                descExpanded = !descExpanded;
                descView.setVisibility(descExpanded ? VISIBLE : GONE);
            });
            detailsBody.addView(headBox, Widgets.MW);

            // Channel Row + Subscribe Toggle
            LinearLayout chRow = new LinearLayout(m);
            chRow.setOrientation(LinearLayout.HORIZONTAL);
            chRow.setGravity(Gravity.CENTER_VERTICAL);
            chRow.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(10));

            String init = (v.author != null && !v.author.isEmpty()) ? v.author.substring(0, 1).toUpperCase(Locale.US) : "Y";
            TextView av = Ui.text(m, init, 15f, Ui.bold, 0xFFFFFFFF);
            av.setGravity(Gravity.CENTER);
            av.setBackground(Ui.round(channelColor(v.author), 100));
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38));
            avlp.rightMargin = Ui.dp(11);
            chRow.addView(av, avlp);

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
            av.setOnClickListener(openCh);
            chCol.setOnClickListener(openCh);

            boolean subbed = isSubscribed(m, v.channelId);
            subBtn = Ui.text(m, m.getString(subbed ? R.string.yt_subscribed : R.string.yt_subscribe),
                    13f, Ui.semibold, subbed ? Ui.text() : 0xFFFFFFFF);
            subBtn.setPadding(Ui.dp(15), Ui.dp(8), Ui.dp(15), Ui.dp(8));
            subBtn.setBackground(Ui.round(subbed ? Ui.chip() : (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F), 100));
            if (!subbed) subBtn.setTextColor(Ui.dark ? 0xFF000000 : 0xFFFFFFFF);
            subBtn.setOnClickListener(vv -> {
                String cid = video.channelId.isEmpty() ? ("CH_" + video.author) : video.channelId;
                boolean now = toggleSubscribe(m, cid, video.author);
                refreshSubBtn(now);
                Toast.makeText(m, video.author + (now ? " 채널을 구독했습니다" : " 구독을 취소했습니다"), Toast.LENGTH_SHORT).show();
            });
            Ui.press(subBtn, 0.92f);
            chRow.addView(subBtn);
            detailsBody.addView(chRow, Widgets.MW);

            // Action Pill Strip (Like, Playlist, Download, Share, Web)
            HorizontalScrollView actScroll = new HorizontalScrollView(m);
            actScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout actRow = new LinearLayout(m);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setPadding(Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(12));

            boolean liked = isVideoLiked(m, v.id);
            LinearLayout likePill = actionPill(liked ? Icons.THUMB_UP_FILL : Icons.THUMB_UP, m.getString(R.string.yt_like), vv -> {
                boolean now = toggleLikeVideo(m, video);
                likeIcon.set(now ? Icons.THUMB_UP_FILL : Icons.THUMB_UP, now ? Ui.ACCENT : Ui.text());
                likeLabel.setTextColor(now ? Ui.ACCENT : Ui.text());
            });
            likeIcon = (Icons.IconView) likePill.getChildAt(0);
            likeLabel = (TextView) likePill.getChildAt(1);
            if (liked) { likeIcon.set(Icons.THUMB_UP_FILL, Ui.ACCENT); likeLabel.setTextColor(Ui.ACCENT); }
            actRow.addView(likePill);
            actRow.addView(actionPill(Icons.PLAYLIST, m.getString(R.string.yt_save_playlist), vv -> showSaveToPlaylistSheet(m, video, vv)));
            actRow.addView(actionPill(Icons.DOWNLOAD, "저장", vv -> showDownloadSheet(m, video, currentStream, vv)));
            actRow.addView(actionPill(Icons.SHARE, m.getString(R.string.yt_share), vv -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, video.title);
                    i.putExtra(Intent.EXTRA_TEXT, video.watchUrl());
                    m.startActivity(Intent.createChooser(i, m.getString(R.string.yt_share)));
                } catch (Throwable ignored) {}
            }));
            actRow.addView(actionPill(Icons.GLOBE, webMode ? "네이티브" : "웹 전환", vv -> switchPlaybackMode(!webMode)));
            actScroll.addView(actRow);
            detailsBody.addView(actScroll, Widgets.MW);

            // Comments Card (expandable real InnerTube comments!)
            LinearLayout commentCard = new LinearLayout(m);
            commentCard.setOrientation(LinearLayout.VERTICAL);
            commentCard.setBackground(Ui.round(Ui.card(), 16));
            commentCard.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
            LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cclp.setMargins(Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(14));

            commentHeader = Ui.text(m, "댓글", 14f, Ui.bold, Ui.text());
            commentCard.addView(commentHeader, Widgets.MW);

            commentPreview = Ui.text(m, m.getString(R.string.yt_loading_comments), 13f, Ui.regular, Ui.text2());
            commentPreview.setSingleLine(false);
            commentPreview.setMaxLines(2);
            commentPreview.setPadding(0, Ui.dp(6), 0, 0);
            commentCard.addView(commentPreview, Widgets.MW);

            commentsExpandedBox = new LinearLayout(m);
            commentsExpandedBox.setOrientation(LinearLayout.VERTICAL);
            commentsExpandedBox.setVisibility(GONE);
            commentCard.addView(commentsExpandedBox, Widgets.MW);

            commentCard.setOnClickListener(vv -> {
                if (loadedComments.isEmpty()) return;
                commentsExpanded = !commentsExpanded;
                commentPreview.setVisibility(commentsExpanded ? GONE : VISIBLE);
                commentsExpandedBox.setVisibility(commentsExpanded ? VISIBLE : GONE);
            });
            detailsBody.addView(commentCard, cclp);

            // Up Next / Related Videos
            detailsBody.addView(Widgets.section(m, m.getString(R.string.yt_up_next)));
            relatedHost = new LinearLayout(m);
            relatedHost.setOrientation(LinearLayout.VERTICAL);
            detailsBody.addView(relatedHost, Widgets.MW);
        }

        private void refreshSubBtn(boolean subbed) {
            subBtn.setText(m.getString(subbed ? R.string.yt_subscribed : R.string.yt_subscribe));
            subBtn.setBackground(Ui.round(subbed ? Ui.chip() : (Ui.dark ? 0xFFFFFFFF : 0xFF0F0F0F), 100));
            subBtn.setTextColor(subbed ? Ui.text() : (Ui.dark ? 0xFF000000 : 0xFFFFFFFF));
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
                if (!d.subscribers.isEmpty()) watchChannelSubs.setText(d.subscribers);
                refreshSubBtn(isSubscribed(m, video.channelId));
                StringBuilder ms = new StringBuilder();
                if (!d.views.isEmpty()) ms.append(d.views);
                if (!d.date.isEmpty()) { if (ms.length() > 0) ms.append(" · "); ms.append(d.date); }
                if (!d.description.isEmpty()) {
                    descView.setText(d.description);
                    if (ms.length() > 0) ms.append(" · 더보기");
                }
                if (ms.length() > 0) watchMeta.setText(ms.toString());
                if (!d.likeCount.isEmpty()) likeLabel.setText(d.likeCount);

                loadedComments.clear();
                loadedComments.addAll(d.comments);
                commentsExpandedBox.removeAllViews();
                if (d.comments.isEmpty()) {
                    commentHeader.setText("댓글");
                    commentPreview.setText(m.getString(R.string.yt_no_comments));
                } else {
                    commentHeader.setText(m.getString(R.string.yt_comments_count, d.comments.size()) + "  ▾");
                    YtComment first = d.comments.get(0);
                    commentPreview.setText(first.author + ": " + first.text);
                    for (YtComment cm : d.comments) {
                        LinearLayout row = new LinearLayout(m);
                        row.setOrientation(LinearLayout.VERTICAL);
                        row.setPadding(0, Ui.dp(10), 0, Ui.dp(6));
                        String h = cm.author + (cm.time.isEmpty() ? "" : " · " + cm.time)
                                + (cm.likes.isEmpty() || "0".equals(cm.likes) ? "" : " · 👍 " + cm.likes);
                        row.addView(Ui.text(m, h, 12f, Ui.semibold, Ui.text2()));
                        TextView b = Ui.text(m, cm.text, 13.5f, Ui.regular, Ui.text());
                        b.setSingleLine(false);
                        b.setPadding(0, Ui.dp(3), 0, 0);
                        row.addView(b);
                        commentsExpandedBox.addView(row, Widgets.MW);
                    }
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
