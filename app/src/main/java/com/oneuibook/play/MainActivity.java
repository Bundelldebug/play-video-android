package com.oneuibook.play;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Play Video Player (v9.5.4) — MainActivity orchestrating glass chrome,
 * mobile / foldable / tablet responsive layouts, video library, full-screen cinema player,
 * PiP, SAF file/subtitle pickers, online subtitle search, on-device NPU acceleration,
 * in-app updater, and AI subtitle generation.
 */
public final class MainActivity extends Activity implements Dock.Listener, Header.Listener {
    static final String VERSION = "9.5.10";

    private static final int REQ_PERM = 1001;
    private static final int REQ_PICK_VIDEO = 1002;
    private static final int REQ_PICK_SUB = 1003;
    private static final int REQ_PICK_SUB2 = 1004;

    FrameLayout root;
    Glass.Backdrop backdrop;
    private FrameLayout pageHost;
    Header header;
    Dock dock;
    VideoOverlay playerOverlay;

    private final NavStack[] tabs = new NavStack[4];
    private int tab;
    private Pages.SearchPage searchPage;

    // Tablet sidebar
    private Sidebar sidebar;
    private float sidebarP;
    private static boolean sidebarWanted;

    private static Bitmap lastFrame;

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("play_video", MODE_PRIVATE);
    }

    @Override protected void attachBaseContext(Context base) {
        String lang = prefs(base).getString("lang", "system");
        if (!"system".equals(lang)) {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.setLocale(new Locale(lang));
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = prefs(this);
        Ui.init(this, p);
        Subtitle.loadStyle(p);
        AudioFx.init(this);
        AudioInfo.init(this);
        NpuAudio.apply(this);
        VideoLibrary.init(this);
        VideoPlayer.app = getApplicationContext();

        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarContrastEnforced(false);
        android.view.WindowManager.LayoutParams wlp = w.getAttributes();
        wlp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(wlp);
        w.getDecorView().setBackgroundColor(Ui.bg());

        root = new FrameLayout(this);
        backdrop = new Glass.Backdrop(this);
        backdrop.setBackgroundColor(Ui.bg());
        root.addView(backdrop, match());

        pageHost = new FrameLayout(this);
        backdrop.addView(pageHost, match());

        header = new Header(this, backdrop);
        header.setListener(this);
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight()));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitHeader());

        dock = new Dock(this, backdrop, new String[]{
                getString(R.string.tab_home),
                getString(R.string.tab_folders),
                getString(R.string.tab_library),
                getString(R.string.tab_settings)
        });
        dock.setListener(this);
        root.addView(dock, match());

        playerOverlay = new VideoOverlay(this);
        root.addView(playerOverlay, match());

        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sys = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets cut = insets.getInsets(WindowInsets.Type.displayCutout());
            int st = Math.max(sys.top, cut.top);
            int nav = Math.max(sys.bottom, cut.bottom);
            int cl = Math.max(sys.left, cut.left);
            int cr = Math.max(sys.right, cut.right);
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            boolean changed = st != Ui.statusInset || nav != Ui.navInset || cl != Ui.cutoutLeft || cr != Ui.cutoutRight;
            Ui.statusInset = st;
            Ui.navInset = nav;
            Ui.cutoutLeft = cl;
            Ui.cutoutRight = cr;
            Ui.imeInset = ime;
            dock.setImeOffset(ime);
            fitHeader();
            if (changed) {
                for (NavStack s : tabs) if (s != null) s.applyInsets();
                if (searchPage != null) searchPage.applyInsets();
                playerOverlay.onInsetsChanged();
            }
            return WindowInsets.CONSUMED;
        });

        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                updateScrollChrome(currentPage());
                boolean dirty = backdrop.isDirty();
                if (dirty || header.animating()) header.invalidate();
                if (dock.getVisibility() == View.VISIBLE && (dirty || dock.animating())) dock.invalidate();
                for (int i = 0; i < root.getChildCount(); i++) {
                    View c = root.getChildAt(i);
                    if (c instanceof GlassMenu) c.invalidate();
                }
                return true;
            }
        });

        tabs[0] = new NavStack(new Pages.HomePage(this));
        tabs[1] = new NavStack(new Pages.FoldersPage(this));
        tabs[2] = new NavStack(new Pages.LibraryPage(this));
        tabs[3] = new NavStack(new Settings.SettingsPage(this));
        for (NavStack s : tabs) { s.setVisibility(View.GONE); pageHost.addView(s, match()); }

        tab = state == null ? 0 : state.getInt("tab", 0);
        tabs[tab].setVisibility(View.VISIBLE);
        tabs[tab].top().show();
        dock.select(tab, false);
        syncHeader();
        setLightStatus(!Ui.dark);

        if (VideoLibrary.hasPermission(this)) {
            VideoLibrary.scan(this, this::reloadAll);
        } else if (state == null) {
            requestVideoPermission();
        }

        if (sidebarWanted) root.post(() -> { if (tabletUi()) openSidebar(false); });
        if (state != null) {
            final Bundle st0 = state;
            root.post(() -> screenChangeTransition(st0));
        }

        handleIntent(getIntent());
        Updater.autoCheck(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            String u = intent.getData().toString();
            String ytId = YouTubeHub.extractVideoId(u);
            if (ytId != null) {
                YouTubeHub.openVideo(this, new YouTubeHub.YtVideo(ytId, "YouTube (" + ytId + ")", "YouTube"));
                return;
            }
            VideoLibrary.Video v = VideoLibrary.fromUri(this, intent.getData());
            playVideo(v, null);
        } else if (Intent.ACTION_SEND.equals(action)) {
            String txt = intent.getStringExtra(Intent.EXTRA_TEXT);
            String ytId = YouTubeHub.extractVideoId(txt);
            if (ytId != null) {
                YouTubeHub.openVideo(this, new YouTubeHub.YtVideo(ytId, "YouTube (" + ytId + ")", "YouTube"));
            }
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.init(this, prefs(this));
        if (playerOverlay.isOpen()) {
            playerOverlay.onInsetsChanged();
            playerOverlay.applyVideoTransform();
            return;
        }
        // Smooth foldable fold/unfold or rotation handoff when browsing library
        lastFrame = captureFrame();
        fitHeader();
        dock.requestLayout();
        for (NavStack s : tabs) if (s != null) { s.applyInsets(); s.rebuildAll(); }
        if (tabletUi() && sidebarWanted && sidebar == null) openSidebar(false);
        else if (!tabletUi() && sidebar != null) closeSidebar();
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (playerOverlay.isOpen() && VideoPlayer.playing && prefs(this).getBoolean("auto_pip", true)) {
            enterPip();
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        playerOverlay.setPipMode(isInPictureInPictureMode);
    }

    void enterPip() {
        try {
            int w = VideoPlayer.videoW > 0 ? VideoPlayer.videoW : 16;
            int h = VideoPlayer.videoH > 0 ? VideoPlayer.videoH : 9;
            float r = Ui.clamp(w / (float) h, 0.45f, 2.35f);
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(Math.round(r * 100), 100))
                    .build();
            enterPictureInPictureMode(params);
        } catch (Throwable t) {
            Toast.makeText(this, "이 기기에서 PiP를 지원하지 않습니다", Toast.LENGTH_SHORT).show();
        }
    }

    void setFullscreenImmersive(boolean on) {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl == null) return;
        if (on) {
            ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            ctrl.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            setLightStatus(!Ui.dark);
        }
    }

    void setLightStatus(boolean light) {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl == null) return;
        int flag = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        ctrl.setSystemBarsAppearance(light ? flag : 0, flag);
    }

    // ---- Responsive layout helpers ----
    boolean tabletUi() {
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : Ui.screenH;
        return Dock.tabletFor(W, H);
    }

    int sideInset() {
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : Ui.screenH;
        return Dock.railFor(W, H) ? Ui.dp(92) : 0;
    }

    int contentInsetPx() { return sidebar != null ? Math.round(Sidebar.widthFor(Ui.screenW) * sidebarP) : 0; }

    int bottomPadding() {
        return Ui.navInset + Ui.dp(tabletUi() ? 92 : 148);
    }

    private int headerHeight() {
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : Ui.screenH;
        return Ui.statusInset + Ui.dp(Dock.railFor(W, H) ? 172 : 88);
    }

    private void fitHeader() {
        if (header == null) return;
        int h = headerHeight();
        if (header.getLayoutParams().height == h) return;
        header.getLayoutParams().height = h;
        header.post(header::requestLayout);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---- Tablet sidebar ----
    void openSidebar(boolean animate) {
        if (!tabletUi()) return;
        sidebarWanted = true;
        if (sidebar == null) {
            sidebar = new Sidebar(this);
            sidebar.setSelected(tab);
            int w = Math.round(Sidebar.widthFor(root.getWidth() > 0 ? root.getWidth() : Ui.screenW));
            root.addView(sidebar, root.indexOfChild(dock), new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        float w = Sidebar.widthFor(root.getWidth() > 0 ? root.getWidth() : Ui.screenW);
        if (animate) {
            Ui.animate(sidebarP, 1f, 320, Ui.EMPHASIZED, a -> applySidebar((float) a.getAnimatedValue(), w));
        } else {
            applySidebar(1f, w);
        }
    }

    void closeSidebar() {
        sidebarWanted = false;
        if (sidebar == null) return;
        float w = Sidebar.widthFor(root.getWidth() > 0 ? root.getWidth() : Ui.screenW);
        Ui.animate(sidebarP, 0f, 260, Ui.EMPHASIZED, a -> {
            float v = (float) a.getAnimatedValue();
            applySidebar(v, w);
            if (v <= 0.01f && sidebar != null) {
                root.removeView(sidebar);
                sidebar = null;
            }
        });
    }

    private void applySidebar(float p, float w) {
        sidebarP = p;
        if (sidebar != null) sidebar.setTranslationX(-w * (1f - p));
        pageHost.setPadding(Math.round(w * p), 0, 0, 0);
        header.setPadding(Math.round(w * p), 0, 0, 0);
        dock.setSidebar(w, p);
    }

    void sidebarTab(int idx) { onTab(idx, idx == tab); }
    void sidebarSearch() { onSearch(); }

    // ---- Playback & Navigation ----
    void playVideo(VideoLibrary.Video v, List<VideoLibrary.Video> list) {
        VideoPlayer.play(this, v, list);
        playerOverlay.open();
    }

    void openLibrarySection(String section) {
        switch (section) {
            case "all": push(new Pages.VideoListPage(this, getString(R.string.all_videos), "all")); break;
            case "recent": push(new Pages.VideoListPage(this, getString(R.string.recent_history), "recent")); break;
            case "favorites": push(new Pages.VideoListPage(this, getString(R.string.favorites), "favorites")); break;
            case "playlists": push(new Pages.PlaylistsPage(this)); break;
            case "streams": push(new Pages.VideoListPage(this, getString(R.string.network_streams), "streams")); break;
            case "youtube": push(new YouTubeHub.YouTubePage(this)); break;
            case "ai_subs": push(new Pages.AiSubtitlesPage(this)); break;
        }
    }

    void refreshAppearance() {
        getWindow().getDecorView().setBackgroundColor(Ui.bg());
        backdrop.setBackgroundColor(Ui.bg());
        setLightStatus(!Ui.dark);
        reloadAll();
        invalidateChrome();
    }

    void invalidateChrome() {
        if (header != null) header.invalidate();
        if (dock != null) { dock.requestLayout(); dock.invalidate(); }
        if (sidebar != null) sidebar.invalidate();
    }

    void push(Page page) {
        tabs[tab].push(page);
        syncHeader();
    }

    void reloadAll() {
        for (NavStack s : tabs) if (s != null) s.rebuildAll();
    }

    void rescanLibrary() {
        VideoLibrary.scan(this, () -> {
            reloadAll();
            Toast.makeText(this, VideoLibrary.allVideos.size() + "개 동영상 스캔 완료", Toast.LENGTH_SHORT).show();
        });
    }

    private Page currentPage() {
        return searchPage != null && searchPage.getVisibility() == View.VISIBLE ? searchPage : tabs[tab].top();
    }

    private void syncHeader() {
        Page p = currentPage();
        header.configure(p.title, p.headerIcons, p.back);
        updateScrollChrome(p);
    }

    void onPageScroll(Page p, int y, int dy) {
        if (p != currentPage()) return;
        updateScrollChrome(p);
        if (y <= Ui.dp(8)) dock.setCollapsed(false);
        else if (dy > Ui.dp(10) && y > Ui.dp(36)) dock.setCollapsed(true);
        else if (dy < -Ui.dp(10)) dock.setCollapsed(false);
    }

    private void updateScrollChrome(Page p) {
        if (p == null) return;
        header.setScroll(p.titleProgress(), p.blurProgress());
    }

    // ---- Dock.Listener ----
    @Override public void onTab(int index, boolean reselect) {
        if (searchPage != null && searchPage.getVisibility() == View.VISIBLE) onExitSearch();
        if (reselect) {
            if (tabs[tab].canPop()) tabs[tab].popToRoot();
            else tabs[tab].top().scrollToTop();
            syncHeader();
            return;
        }
        tabs[tab].setVisibility(View.GONE);
        tab = index;
        tabs[tab].setVisibility(View.VISIBLE);
        tabs[tab].top().show();
        if (sidebar != null) sidebar.setSelected(tab);
        syncHeader();
    }

    @Override public void onMini() {
        if (VideoPlayer.current != null) playerOverlay.open();
    }

    @Override public void onSearch() {
        if (searchPage == null) {
            searchPage = new Pages.SearchPage(this);
            pageHost.addView(searchPage, match());
        }
        searchPage.setVisibility(View.VISIBLE);
        searchPage.show();
        dock.setSearchMode(true);
        syncHeader();
    }

    @Override public void onExitSearch() {
        if (searchPage != null) searchPage.setVisibility(View.GONE);
        dock.setSearchMode(false);
        syncHeader();
    }

    @Override public void onQuery(String q, boolean submit) {
        if (searchPage != null) {
            if (submit) searchPage.commitQuery(q);
            searchPage.setQuery(q);
        }
    }

    @Override public void onSidebar() {
        if (sidebar == null) openSidebar(true); else closeSidebar();
    }

    // ---- Header.Listener ----
    @Override public void onHeaderButton(int icon, RectF anchor) {
        if (icon == Icons.PLUS) {
            Page cur = currentPage();
            if (cur instanceof Pages.PlaylistsPage) {
                Pages.createPlaylistDialog(this, pl -> cur.rebuild());
                return;
            }
            List<GlassMenu.Entry> es = new ArrayList<>();
            es.add(new GlassMenu.Entry(getString(R.string.open_file), Icons.FOLDER, false, this::pickVideoFile));
            es.add(new GlassMenu.Entry(getString(R.string.open_url), Icons.LINK, false, () -> Pages.openStreamDialog(this)));
            es.add(new GlassMenu.Entry(getString(R.string.yt_title), Icons.YOUTUBE, false, () -> openLibrarySection("youtube")));
            es.add(new GlassMenu.Entry(getString(R.string.new_playlist), Icons.PLAYLIST, false, () -> Pages.createPlaylistDialog(this, null)));
            GlassMenu.show(this, anchor, es);
        } else if (icon == Icons.REFRESH) {
            rescanLibrary();
        } else if (icon == Icons.SORT) {
            List<GlassMenu.Entry> es = new ArrayList<>();
            int[] labels = {R.string.sort_date, R.string.sort_name, R.string.sort_size, R.string.sort_duration};
            for (int i = 0; i < labels.length; i++) {
                final int mode = i;
                GlassMenu.Entry e = new GlassMenu.Entry(getString(labels[i]), 0, VideoLibrary.sortMode == mode, () -> {
                    VideoLibrary.sortMode = mode;
                    prefs(this).edit().putInt("sort_mode", mode).apply();
                    reloadAll();
                });
                e.checkable = true;
                es.add(e);
            }
            GlassMenu.show(this, anchor, es);
        } else if (icon == Icons.GRID || icon == Icons.LIST) {
            VideoLibrary.gridMode = !VideoLibrary.gridMode;
            prefs(this).edit().putBoolean("grid_mode", VideoLibrary.gridMode).apply();
            Page cur = currentPage();
            cur.headerIcons = new int[]{Icons.SORT, VideoLibrary.gridMode ? Icons.LIST : Icons.GRID};
            cur.rebuild();
            syncHeader();
        } else if (icon == Icons.SETTINGS) {
            Page cur = currentPage();
            if (cur instanceof YouTubeHub.YouTubePage) {
                YouTubeHub.showSourceSettingsSheet(this);
                return;
            }
            dock.select(3, true);
            onTab(3, false);
        } else if (icon == Icons.MORE) {
            List<GlassMenu.Entry> es = new ArrayList<>();
            es.add(new GlassMenu.Entry(getString(R.string.rescan), Icons.REFRESH, false, this::rescanLibrary));
            es.add(new GlassMenu.Entry(getString(R.string.open_url), Icons.LINK, false, () -> Pages.openStreamDialog(this)));
            es.add(new GlassMenu.Entry(getString(R.string.tab_settings), Icons.SETTINGS, false, () -> {
                dock.select(3, true);
                onTab(3, false);
            }));
            GlassMenu.show(this, anchor, es);
        }
    }

    @Override public void onBack() { onBackPressed(); }

    @Override public void onBackPressed() {
        if (GlassMenu.dismissAny(root)) return;
        if (YouTubeHub.dismissOverlay(root)) return;
        if (playerOverlay.isOpen()) { playerOverlay.close(); return; }
        if (searchPage != null && searchPage.getVisibility() == View.VISIBLE) { onExitSearch(); return; }
        if (tabs[tab].canPop()) { tabs[tab].pop(); syncHeader(); return; }
        super.onBackPressed();
    }

    // ---- Permissions & SAF File Pickers ----
    void requestVideoPermission() {
        requestPermissions(new String[]{VideoLibrary.permissionName()}, REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            VideoLibrary.scan(this, this::reloadAll);
        }
    }

    void pickVideoFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(i, REQ_PICK_VIDEO);
    }

    void pickSubtitleFile(boolean secondary) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, secondary ? REQ_PICK_SUB2 : REQ_PICK_SUB);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_PICK_VIDEO) {
            VideoLibrary.Video v = VideoLibrary.fromUri(this, uri);
            playVideo(v, null);
        } else if (req == REQ_PICK_SUB || req == REQ_PICK_SUB2) {
            Subtitle.Track t = Subtitle.loadUri(this, uri, null);
            if (t != null && !t.cues.isEmpty()) {
                if (req == REQ_PICK_SUB2) VideoPlayer.secondarySub = t;
                else VideoPlayer.primarySub = t;
                VideoPlayer.subEnabled = true;
                Toast.makeText(this, getString(R.string.sub_loaded) + " (" + t.cues.size() + ")", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.sub_not_found, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---- Screen change transition ----
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        if (dock != null && dock.getWidth() > 0) out.putFloatArray("dock_geo", dock.snapshot());
        if (header != null) { float[] hg = header.snapshot(); if (hg != null) out.putFloatArray("hdr_geo", hg); }
        lastFrame = isChangingConfigurations() ? captureFrame() : null;
    }

    private Bitmap captureFrame() {
        try {
            int w = root.getWidth(), h = root.getHeight();
            if (w <= 0 || h <= 0) return null;
            Bitmap b = Bitmap.createBitmap((int) (w * 0.5f), (int) (h * 0.5f), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(0.5f, 0.5f);
            root.draw(c);
            return b;
        } catch (Throwable t) { return null; }
    }

    private void screenChangeTransition(Bundle state) {
        Bitmap frame = lastFrame;
        lastFrame = null;
        if (frame != null) {
            ImageView old = new ImageView(this);
            old.setImageBitmap(frame);
            old.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(old, match());
            old.animate().alpha(0f).setDuration(260).setInterpolator(Ui.DECEL)
                    .withEndAction(() -> { root.removeView(old); frame.recycle(); }).start();
        }
        float[] hg = state.getFloatArray("hdr_geo");
        if (hg != null) header.morphFrom(hg);
    }

    @Override protected void onDestroy() {
        if (!isChangingConfigurations()) VideoPlayer.saveCurrentResume();
        super.onDestroy();
    }

    // ---- Navigation stack per tab ----
    final class NavStack extends FrameLayout {
        private final List<Page> stack = new ArrayList<>();

        NavStack(Page rootPage) {
            super(MainActivity.this);
            stack.add(rootPage);
            addView(rootPage, match());
        }

        Page top() { return stack.get(stack.size() - 1); }
        boolean canPop() { return stack.size() > 1; }

        void push(Page p) {
            Page prev = top();
            stack.add(p);
            addView(p, match());
            p.show();
            p.setAlpha(0f);
            p.setTranslationX(Ui.dpf(42));
            p.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(Ui.EMPHASIZED).start();
            prev.animate().alpha(0.3f).translationX(-Ui.dpf(24)).setDuration(300).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> prev.setVisibility(View.GONE)).start();
        }

        void pop() {
            if (stack.size() <= 1) return;
            Page cur = stack.remove(stack.size() - 1);
            Page prev = top();
            prev.setVisibility(View.VISIBLE);
            prev.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(Ui.EMPHASIZED).start();
            cur.animate().alpha(0f).translationX(Ui.dpf(42)).setDuration(240).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> removeView(cur)).start();
        }

        void popToRoot() {
            while (stack.size() > 1) {
                Page cur = stack.remove(stack.size() - 1);
                removeView(cur);
            }
            Page rootPage = top();
            rootPage.setVisibility(View.VISIBLE);
            rootPage.setAlpha(1f);
            rootPage.setTranslationX(0f);
        }

        void applyInsets() { for (Page p : stack) p.applyInsets(); }
        void rebuildAll() { for (Page p : stack) p.rebuild(); }
    }
}
