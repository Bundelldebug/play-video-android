package com.oneuibook.play;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates from GitHub releases with full Markdown release-notes rendering.
 */
final class Updater {
    private Updater() {}

    static final String REPO = "Bundelldebug/play-video-android";
    static final String PREF_AUTO = "update_auto";
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Release {
        String version, notes, apkUrl, pageUrl;
    }

    interface Cb { void done(Release newer, String error); }

    static void autoCheck(Activity a) {
        android.content.SharedPreferences p = MainActivity.prefs(a);
        if (!p.getBoolean(PREF_AUTO, true) || "robolectric".equals(android.os.Build.FINGERPRINT)) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("update_last", 0) < DAY) return;
        p.edit().putLong("update_last", now).apply();
        check((r, err) -> { if (r != null && !a.isFinishing() && !a.isDestroyed()) offer(a, r); });
    }

    static void check(Cb cb) {
        new Thread(() -> {
            Release r = null;
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Play-Android-Updater");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                JSONObject o = new JSONObject(read(c.getInputStream()));
                String tag = o.optString("tag_name").replaceFirst("^[vV]", "");
                if (newer(tag, MainActivity.VERSION)) {
                    r = new Release();
                    r.version = tag;
                    r.notes = o.optString("body");
                    r.pageUrl = o.optString("html_url");
                    JSONArray as = o.optJSONArray("assets");
                    if (as != null) {
                        for (int i = 0; i < as.length(); i++) {
                            JSONObject x = as.optJSONObject(i);
                            if (x != null && x.optString("name").toLowerCase().endsWith(".apk")) {
                                r.apkUrl = x.optString("browser_download_url");
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final Release fr = r;
            final String fe = err;
            MAIN.post(() -> cb.done(fr, fe));
        }, "play-update").start();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }

    static CharSequence formatReleaseNotes(String raw) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (raw == null || raw.trim().isEmpty()) return sb;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.length() > 4000) text = text.substring(0, 4000) + "…";
        String[] lines = text.split("\n");
        boolean lastBlank = true;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!lastBlank && sb.length() > 0) { sb.append('\n'); lastBlank = true; }
                continue;
            }
            if (t.matches("^[-*_]{3,}$")) continue;
            if (sb.length() > 0) sb.append('\n');
            lastBlank = false;

            int hLevel = 0;
            while (hLevel < t.length() && t.charAt(hLevel) == '#') hLevel++;
            if (hLevel > 0 && hLevel < t.length() && t.charAt(hLevel) == ' ') {
                String heading = t.substring(hLevel + 1).trim();
                if (sb.length() > 1 && sb.charAt(sb.length() - 2) != '\n') sb.append('\n');
                int start = sb.length();
                appendInlineMarkdown(sb, heading);
                int end = sb.length();
                if (end > start) {
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(hLevel <= 2 ? 1.12f : 1.05f), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }

            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) indent++;
            String afterIndent = line.substring(indent);
            if (afterIndent.length() >= 2
                    && (afterIndent.charAt(0) == '-' || afterIndent.charAt(0) == '*' || afterIndent.charAt(0) == '+')
                    && afterIndent.charAt(1) == ' ') {
                boolean nested = indent >= 2;
                int start = sb.length();
                sb.append(nested ? "◦  " : "•  ");
                appendInlineMarkdown(sb, afterIndent.substring(2).trim());
                int end = sb.length();
                sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(Ui.dp(nested ? 18 : 6), Ui.dp(nested ? 28 : 16)), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            java.util.regex.Matcher numM = java.util.regex.Pattern.compile("^(\\d+)[.)]\\s+(.*)$").matcher(afterIndent);
            if (numM.matches()) {
                int start = sb.length();
                sb.append(numM.group(1)).append(". ");
                appendInlineMarkdown(sb, numM.group(2).trim());
                int end = sb.length();
                sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(Ui.dp(4), Ui.dp(18)), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            appendInlineMarkdown(sb, t);
        }
        return sb;
    }

    private static void appendInlineMarkdown(android.text.SpannableStringBuilder sb, String s) {
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < n) { sb.append(s.charAt(i + 1)); i += 2; continue; }
            if (ch == '[') {
                int closeBr = s.indexOf(']', i + 1);
                if (closeBr > i + 1 && closeBr + 1 < n && s.charAt(closeBr + 1) == '(') {
                    int closePar = s.indexOf(')', closeBr + 2);
                    if (closePar > closeBr + 2) {
                        int st = sb.length();
                        appendInlineMarkdown(sb, s.substring(i + 1, closeBr));
                        int en = sb.length();
                        if (en > st) sb.setSpan(new android.text.style.UnderlineSpan(), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = closePar + 1;
                        continue;
                    }
                }
            }
            if (ch == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = sb.length();
                    sb.append(s, i + 1, end);
                    int en = sb.length();
                    sb.setSpan(new android.text.style.TypefaceSpan("monospace"), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.92f), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if ((ch == '*' || ch == '_') && i + 1 < n && s.charAt(i + 1) == ch) {
                String delim = ch == '*' ? "**" : "__";
                int end = s.indexOf(delim, i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    appendInlineMarkdown(sb, s.substring(i + 2, end));
                    int en = sb.length();
                    if (en > st) sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
    }

    static void offer(Activity a, Release r) {
        CharSequence notes = formatReleaseNotes(r.notes);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        android.widget.TextView tv = new android.widget.TextView(a);
        tv.setText(notes);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setLineSpacing(0f, 1.32f);
        tv.setTextColor(Ui.dark ? 0xFFEAEAF0 : 0xFF1C1C1E);
        tv.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), Ui.dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(a, Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(a.getString(R.string.upd_available, r.version))
                .setView(sv)
                .setPositiveButton(R.string.upd_now, (d, w) -> install(a, r))
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    private static long pending = -1;

    static void install(Activity a, Release r) {
        if (r.apkUrl == null || r.apkUrl.isEmpty()) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))); } catch (Exception ignored) {}
            return;
        }
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, R.string.upd_allow, Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        DownloadManager dm = a.getSystemService(DownloadManager.class);
        if (dm == null) { Toast.makeText(a, R.string.upd_failed, Toast.LENGTH_LONG).show(); return; }
        String name = "Play-" + r.version + ".apk";
        java.io.File old = new java.io.File(a.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name);
        if (old.exists()) old.delete();
        DownloadManager.Request q = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle(a.getString(R.string.app_name) + " " + r.version)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(a, android.os.Environment.DIRECTORY_DOWNLOADS, name);
        Context app = a.getApplicationContext();
        if (activeReceiver != null) {
            try { app.unregisterReceiver(activeReceiver); } catch (Exception ignored) {}
            activeReceiver = null;
        }
        pending = dm.enqueue(q);
        Toast.makeText(a, R.string.upd_downloading, Toast.LENGTH_SHORT).show();
        final long expectedId = pending;
        BroadcastReceiver done = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != expectedId) return;
                try { app.unregisterReceiver(this); } catch (Exception ignored) {}
                if (activeReceiver == this) activeReceiver = null;
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) { Toast.makeText(app, R.string.upd_failed, Toast.LENGTH_LONG).show(); return; }
                Intent in = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(in); } catch (Exception e) { Toast.makeText(app, R.string.upd_failed, Toast.LENGTH_LONG).show(); }
            }
        };
        activeReceiver = done;
        app.registerReceiver(done, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
    }
    private static BroadcastReceiver activeReceiver;
}
