package com.oneuibook.play;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local video library, folder grouping, async thumbnail cache, and persistent user metadata
 * (resume positions, watch history, favorites, playlists, bookmarks, network streams).
 */
final class VideoLibrary {
    private VideoLibrary() {}

    static final class Video {
        long id;
        String uri;
        String path;
        String title;
        String folder;
        long durationMs;
        long sizeBytes;
        long dateAddedSec;
        int width;
        int height;
        boolean isStream;
        boolean hasSubtitle;

        String key() { return uri != null && !uri.isEmpty() ? uri : (path != null ? path : String.valueOf(id)); }

        String resolutionBadge() {
            int h = Math.min(width, height);
            int w = Math.max(width, height);
            if (w >= 3800 || h >= 2100) return "4K";
            if (w >= 2500 || h >= 1400) return "QHD";
            if (w >= 1900 || h >= 1060) return "FHD";
            if (w >= 1260 || h >= 700) return "HD";
            return "";
        }
    }

    static final class Folder {
        String name;
        final List<Video> videos = new ArrayList<>();
        long totalSize;
    }

    static final class Bookmark {
        long posMs;
        String label;
        Bookmark(long posMs, String label) { this.posMs = posMs; this.label = label; }
    }

    static final class Playlist {
        String id;
        String name;
        final List<String> videoKeys = new ArrayList<>();
    }

    static final List<Video> allVideos = new ArrayList<>();
    static final List<Folder> folders = new ArrayList<>();
    static final Map<String, Long> resumePos = new HashMap<>();
    static final List<String> history = new ArrayList<>();
    static final Set<String> favorites = new HashSet<>();
    static final List<Playlist> playlists = new ArrayList<>();
    static final List<Video> streams = new ArrayList<>();
    static final Map<String, List<Bookmark>> bookmarks = new HashMap<>();

    static boolean loaded;
    static int sortMode; // 0: date desc, 1: name asc, 2: size desc, 3: duration desc
    static boolean gridMode = true;

    private static final ExecutorService SCAN_IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService THUMB_IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context appCtx;

    private static final LruCache<String, Bitmap> THUMB_MEM = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    static void init(Context c) {
        appCtx = c.getApplicationContext();
        sortMode = MainActivity.prefs(appCtx).getInt("sort_mode", 0);
        gridMode = MainActivity.prefs(appCtx).getBoolean("grid_mode", true);
        loadState();
    }

    static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static String permissionName() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static void scan(Context c, Runnable onDone) {
        Context app = c.getApplicationContext();
        SCAN_IO.execute(() -> {
            List<Video> found = new ArrayList<>();
            if (hasPermission(app)) {
                String[] proj = {
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED,
                        MediaStore.Video.Media.WIDTH,
                        MediaStore.Video.Media.HEIGHT,
                        MediaStore.Video.Media.DATA
                };
                try (Cursor cur = app.getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                        MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                    if (cur != null) {
                        int iId = cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                        int iName = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                        int iBucket = cur.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                        int iDur = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                        int iSize = cur.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                        int iDate = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                        int iW = cur.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
                        int iH = cur.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
                        int iData = cur.getColumnIndex(MediaStore.Video.Media.DATA);
                        while (cur.moveToNext()) {
                            Video v = new Video();
                            v.id = cur.getLong(iId);
                            v.uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id).toString();
                            String rawName = cur.getString(iName);
                            v.title = stripExt(rawName == null ? "Video " + v.id : rawName);
                            v.folder = cur.getString(iBucket);
                            if (v.folder == null || v.folder.isEmpty()) v.folder = "내 기기";
                            v.durationMs = cur.getLong(iDur);
                            v.sizeBytes = cur.getLong(iSize);
                            v.dateAddedSec = cur.getLong(iDate);
                            v.width = cur.getInt(iW);
                            v.height = cur.getInt(iH);
                            if (iData >= 0) v.path = cur.getString(iData);
                            v.hasSubtitle = Subtitle.findSidecar(v.path) != null || AiSubtitle.cachedSrtFor(app, v.key()).exists();
                            found.add(v);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            MAIN.post(() -> {
                allVideos.clear();
                allVideos.addAll(found);
                rebuildFolders();
                loaded = true;
                if (onDone != null) onDone.run();
            });
        });
    }

    static void rebuildFolders() {
        Map<String, Folder> map = new LinkedHashMap<>();
        for (Video v : allVideos) {
            Folder f = map.get(v.folder);
            if (f == null) {
                f = new Folder();
                f.name = v.folder;
                map.put(v.folder, f);
            }
            f.videos.add(v);
            f.totalSize += v.sizeBytes;
        }
        folders.clear();
        folders.addAll(map.values());
        Collections.sort(folders, (a, b) -> Integer.compare(b.videos.size(), a.videos.size()));
    }

    static List<Video> sorted(List<Video> src) {
        List<Video> copy = new ArrayList<>(src);
        switch (sortMode) {
            case 1: Collections.sort(copy, (a, b) -> a.title.compareToIgnoreCase(b.title)); break;
            case 2: Collections.sort(copy, (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes)); break;
            case 3: Collections.sort(copy, (a, b) -> Long.compare(b.durationMs, a.durationMs)); break;
            default: Collections.sort(copy, (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec)); break;
        }
        return copy;
    }

    static Video findByKey(String key) {
        if (key == null) return null;
        for (Video v : allVideos) if (key.equals(v.key())) return v;
        for (Video v : streams) if (key.equals(v.key())) return v;
        return null;
    }

    static List<Video> continueWatching() {
        List<Video> out = new ArrayList<>();
        for (String key : history) {
            Video v = findByKey(key);
            if (v != null) {
                long pos = getResume(key);
                if (pos > 3000 && (v.durationMs <= 0 || pos < v.durationMs - 5000)) out.add(v);
            }
            if (out.size() >= 12) break;
        }
        return out;
    }

    static List<Video> historyVideos() {
        List<Video> out = new ArrayList<>();
        for (String key : history) {
            Video v = findByKey(key);
            if (v != null) out.add(v);
        }
        return out;
    }

    static List<Video> favoriteVideos() {
        List<Video> out = new ArrayList<>();
        for (Video v : allVideos) if (favorites.contains(v.key())) out.add(v);
        for (Video v : streams) if (favorites.contains(v.key())) out.add(v);
        return out;
    }

    static long getResume(String key) {
        Long v = resumePos.get(key);
        return v == null ? 0L : v;
    }

    static void recordPlay(Video v, long posMs) {
        if (v == null) return;
        String k = v.key();
        if (posMs > 0) resumePos.put(k, posMs);
        history.remove(k);
        history.add(0, k);
        while (history.size() > 80) history.remove(history.size() - 1);
        saveState();
    }

    static void toggleFavorite(Video v) {
        if (v == null) return;
        String k = v.key();
        if (!favorites.remove(k)) favorites.add(k);
        saveState();
    }

    static boolean isFavorite(Video v) { return v != null && favorites.contains(v.key()); }

    static Video addStream(String url, String title) {
        Video v = new Video();
        v.uri = url.trim();
        v.title = (title == null || title.trim().isEmpty()) ? guessTitle(v.uri) : title.trim();
        v.folder = "네트워크 스트림";
        v.isStream = true;
        v.dateAddedSec = System.currentTimeMillis() / 1000L;
        for (int i = streams.size() - 1; i >= 0; i--) {
            if (streams.get(i).uri.equals(v.uri)) streams.remove(i);
        }
        streams.add(0, v);
        saveState();
        return v;
    }

    static void removeStream(Video v) {
        if (v == null) return;
        streams.remove(v);
        saveState();
    }

    static Playlist createPlaylist(String name) {
        Playlist p = new Playlist();
        p.id = "pl_" + System.currentTimeMillis();
        p.name = name.trim();
        playlists.add(0, p);
        saveState();
        return p;
    }

    static void addToPlaylist(Playlist pl, Video v) {
        if (pl == null || v == null) return;
        String k = v.key();
        if (!pl.videoKeys.contains(k)) {
            pl.videoKeys.add(k);
            saveState();
        }
    }

    static void addBookmark(Video v, long posMs, String label) {
        if (v == null) return;
        List<Bookmark> list = bookmarks.get(v.key());
        if (list == null) {
            list = new ArrayList<>();
            bookmarks.put(v.key(), list);
        }
        String lbl = (label == null || label.trim().isEmpty()) ? Ui.time(posMs) : label.trim();
        list.add(new Bookmark(posMs, lbl));
        Collections.sort(list, Comparator.comparingLong(b -> b.posMs));
        saveState();
    }

    static List<Bookmark> getBookmarks(Video v) {
        if (v == null) return Collections.emptyList();
        List<Bookmark> l = bookmarks.get(v.key());
        return l == null ? Collections.emptyList() : l;
    }

    static void removeBookmark(Video v, int index) {
        if (v == null) return;
        List<Bookmark> l = bookmarks.get(v.key());
        if (l != null && index >= 0 && index < l.size()) {
            l.remove(index);
            saveState();
        }
    }

    static Video fromUri(Context c, Uri uri) {
        Video v = new Video();
        v.uri = uri.toString();
        String s = uri.getScheme();
        v.isStream = "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s) || "rtsp".equalsIgnoreCase(s);
        v.title = guessTitle(v.uri);
        v.folder = v.isStream ? "네트워크 스트림" : "외부 파일";
        if (!v.isStream) {
            try (Cursor cur = c.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    String dn = cur.getString(0);
                    if (dn != null && !dn.isEmpty()) v.title = stripExt(dn);
                }
            } catch (Throwable ignored) {}
        }
        return v;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String guessTitle(String url) {
        try {
            Uri u = Uri.parse(url);
            String seg = u.getLastPathSegment();
            if (seg != null && !seg.isEmpty()) return stripExt(seg);
        } catch (Throwable ignored) {}
        return url;
    }

    // ---- Persistence ----
    private static File stateFile() { return new File(appCtx.getFilesDir(), "play_state.json"); }

    static void saveState() {
        if (appCtx == null) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject res = new JSONObject();
            for (Map.Entry<String, Long> e : resumePos.entrySet()) res.put(e.getKey(), e.getValue());
            root.put("resume", res);

            JSONArray hist = new JSONArray();
            for (String k : history) hist.put(k);
            root.put("history", hist);

            JSONArray fav = new JSONArray();
            for (String k : favorites) fav.put(k);
            root.put("favorites", fav);

            JSONArray pls = new JSONArray();
            for (Playlist p : playlists) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                JSONArray ks = new JSONArray();
                for (String k : p.videoKeys) ks.put(k);
                o.put("keys", ks);
                pls.put(o);
            }
            root.put("playlists", pls);

            JSONArray strs = new JSONArray();
            for (Video v : streams) {
                JSONObject o = new JSONObject();
                o.put("uri", v.uri);
                o.put("title", v.title);
                strs.put(o);
            }
            root.put("streams", strs);

            JSONObject bms = new JSONObject();
            for (Map.Entry<String, List<Bookmark>> e : bookmarks.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Bookmark b : e.getValue()) {
                    arr.put(new JSONObject().put("pos", b.posMs).put("label", b.label));
                }
                bms.put(e.getKey(), arr);
            }
            root.put("bookmarks", bms);

            try (FileOutputStream fos = new FileOutputStream(stateFile())) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private static void loadState() {
        File f = stateFile();
        if (!f.exists()) return;
        try (FileInputStream fis = new FileInputStream(f)) {
            JSONObject root = new JSONObject(new String(Subtitle.readAll(fis), StandardCharsets.UTF_8));
            JSONObject res = root.optJSONObject("resume");
            if (res != null) {
                java.util.Iterator<String> it = res.keys();
                while (it.hasNext()) { String k = it.next(); resumePos.put(k, res.optLong(k)); }
            }
            JSONArray hist = root.optJSONArray("history");
            if (hist != null) for (int i = 0; i < hist.length(); i++) history.add(hist.optString(i));
            JSONArray fav = root.optJSONArray("favorites");
            if (fav != null) for (int i = 0; i < fav.length(); i++) favorites.add(fav.optString(i));
            JSONArray pls = root.optJSONArray("playlists");
            if (pls != null) {
                for (int i = 0; i < pls.length(); i++) {
                    JSONObject o = pls.getJSONObject(i);
                    Playlist p = new Playlist();
                    p.id = o.optString("id");
                    p.name = o.optString("name");
                    JSONArray ks = o.optJSONArray("keys");
                    if (ks != null) for (int j = 0; j < ks.length(); j++) p.videoKeys.add(ks.optString(j));
                    playlists.add(p);
                }
            }
            JSONArray strs = root.optJSONArray("streams");
            if (strs != null) {
                for (int i = 0; i < strs.length(); i++) {
                    JSONObject o = strs.getJSONObject(i);
                    Video v = new Video();
                    v.uri = o.optString("uri");
                    v.title = o.optString("title");
                    v.folder = "네트워크 스트림";
                    v.isStream = true;
                    streams.add(v);
                }
            }
            JSONObject bms = root.optJSONObject("bookmarks");
            if (bms != null) {
                java.util.Iterator<String> it = bms.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray arr = bms.optJSONArray(k);
                    List<Bookmark> list = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            list.add(new Bookmark(o.optLong("pos"), o.optString("label")));
                        }
                    }
                    bookmarks.put(k, list);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---- Async video thumbnail loading & view ----
    interface ThumbCb { void onBitmap(Bitmap b); }

    static void loadThumb(Context c, Video v, ThumbCb cb) {
        if (v == null || v.isStream) { cb.onBitmap(null); return; }
        String key = v.key();
        Bitmap cached = THUMB_MEM.get(key);
        if (cached != null) { cb.onBitmap(cached); return; }
        Context app = c.getApplicationContext();
        THUMB_IO.execute(() -> {
            Bitmap bmp = null;
            try {
                if (Build.VERSION.SDK_INT >= 29 && v.uri != null && v.uri.startsWith("content://")) {
                    bmp = app.getContentResolver().loadThumbnail(Uri.parse(v.uri), new Size(480, 270), null);
                }
            } catch (Throwable ignored) {}
            if (bmp == null) {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                try {
                    if (v.path != null && new File(v.path).exists()) mmr.setDataSource(v.path);
                    else mmr.setDataSource(app, Uri.parse(v.uri));
                    bmp = mmr.getScaledFrameAtTime(Math.min(v.durationMs * 500L, 5_000_000L),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270);
                } catch (Throwable ignored) {
                } finally {
                    try { mmr.release(); } catch (Throwable ignored) {}
                }
            }
            if (bmp != null) THUMB_MEM.put(key, bmp);
            Bitmap res = bmp;
            MAIN.post(() -> cb.onBitmap(res));
        });
    }

    static void clearThumbCache() { THUMB_MEM.evictAll(); }

    /** Rounded video thumbnail view with smooth fade-in and placeholder icon. */
    static final class ThumbView extends View {
        private final float radiusDp;
        private Bitmap bmp;
        private BitmapShader shader;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private String boundKey;
        int placeholderIcon = Icons.VIDEO;

        ThumbView(Context c, float radiusDp) {
            super(c);
            this.radiusDp = radiusDp;
        }

        void bind(Video v) {
            String k = v == null ? null : v.key();
            if (k != null && k.equals(boundKey)) return;
            boundKey = k;
            bmp = null;
            shader = null;
            invalidate();
            if (v != null && !v.isStream) {
                loadThumb(getContext(), v, b -> {
                    if (k.equals(boundKey) && b != null) {
                        bmp = b;
                        shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        invalidate();
                    }
                });
            }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Ui.dpf(radiusDp);
            rect.set(0, 0, w, h);
            bgPaint.setColor(Ui.placeholder());
            c.drawRoundRect(rect, r, r, bgPaint);
            if (bmp != null && shader != null) {
                float sx = w / (float) bmp.getWidth(), sy = h / (float) bmp.getHeight();
                float s = Math.max(sx, sy);
                matrix.setScale(s, s);
                matrix.postTranslate((w - bmp.getWidth() * s) / 2f, (h - bmp.getHeight() * s) / 2f);
                shader.setLocalMatrix(matrix);
                paint.setShader(shader);
                c.drawRoundRect(rect, r, r, paint);
            } else {
                Icons.draw(c, placeholderIcon, w / 2f, h / 2f, Math.min(w, h) * 0.34f, Ui.text3());
            }
        }
    }
}
