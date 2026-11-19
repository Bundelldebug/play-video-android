package com.oneuibook.play;

import android.app.AlertDialog;
import android.graphics.RectF;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** All library browsing pages: Home, Folders, FolderDetail, Library, VideoList, Playlists, Streams, AiSubtitles, Search. */
final class Pages {
    private Pages() {}

    static int gridColumns(MainActivity m) {
        int cw = Widgets.contentW(m);
        float wd = cw / Ui.density;
        if (wd >= 840) return 4;
        if (wd >= 560) return 3;
        return 2;
    }

    static void addVideoGrid(MainActivity m, LinearLayout host, List<VideoLibrary.Video> videos) {
        int cols = gridColumns(m);
        int gap = Ui.dp(12);
        int pad = Ui.dp(18);
        int avail = Widgets.contentW(m) - pad * 2 - gap * (cols - 1);
        int cardW = Math.max(Ui.dp(110), avail / cols);

        for (int i = 0; i < videos.size(); i += cols) {
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(pad, 0, pad, Ui.dp(16));
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < videos.size()) {
                    VideoLibrary.Video v = videos.get(idx);
                    View card = Widgets.videoGridCard(m, v, cardW,
                            vv -> m.playVideo(v, videos),
                            vv -> { showVideoMenu(m, v, vv); return true; });
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

    static void addVideoList(MainActivity m, LinearLayout host, List<VideoLibrary.Video> videos) {
        for (VideoLibrary.Video v : videos) {
            host.addView(Widgets.videoRow(m, v,
                    vv -> m.playVideo(v, videos),
                    vv -> showVideoMenu(m, v, vv)), Widgets.MW);
        }
    }

    static void showVideoMenu(MainActivity m, VideoLibrary.Video v, View anchorView) {
        float[] o = Glass.abs(anchorView);
        RectF r = new RectF(o[0], o[1], o[0] + anchorView.getWidth(), o[1] + anchorView.getHeight());
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.play), Icons.PLAY, false, () -> m.playVideo(v, null)));
        boolean fav = VideoLibrary.isFavorite(v);
        es.add(new GlassMenu.Entry(m.getString(fav ? R.string.remove_favorite : R.string.add_favorite),
                fav ? Icons.HEART_FILL : Icons.HEART, false, () -> {
            VideoLibrary.toggleFavorite(v);
            m.reloadAll();
        }));
        es.add(new GlassMenu.Entry(m.getString(R.string.add_to_playlist), Icons.PLAYLIST, false, () -> pickPlaylistDialog(m, v)));
        es.add(new GlassMenu.Entry(m.getString(R.string.sub_web_search), Icons.GLOBE, false, () -> {
            m.playVideo(v, null);
            m.root.postDelayed(() -> OnlineSubtitle.showSearchSheet(m, v, false), 320);
        }));
        if (!v.isStream) {
            es.add(new GlassMenu.Entry(m.getString(R.string.sub_ai_generate), Icons.AI, false, () -> {
                m.playVideo(v, null);
                m.root.postDelayed(() -> m.playerOverlay.startAiSubtitle(), 350);
            }));
        }
        es.add(new GlassMenu.Entry(m.getString(R.string.info), Icons.INFO, false, () -> showVideoInfo(m, v)));
        GlassMenu.sheet(m, r, v.title, es, null);
    }

    static void showVideoInfo(MainActivity m, VideoLibrary.Video v) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(14));
        int fg = m.playerOverlay.isOpen() ? 0xFFFFFFFF : Ui.text();
        int fg2 = m.playerOverlay.isOpen() ? 0xB3FFFFFF : Ui.text2();
        addInfoRow(m, box, "제목", v.title, fg, fg2);
        addInfoRow(m, box, "폴더", v.folder, fg, fg2);
        if (v.durationMs > 0) addInfoRow(m, box, "길이", Ui.time(v.durationMs), fg, fg2);
        if (v.width > 0 && v.height > 0) addInfoRow(m, box, "해상도", v.width + " × " + v.height, fg, fg2);
        if (v.sizeBytes > 0) addInfoRow(m, box, "크기", Ui.size(v.sizeBytes), fg, fg2);
        addInfoRow(m, box, "경로", v.path != null ? v.path : v.uri, fg, fg2);
        RectF anchor = new RectF(Ui.screenW / 2f - Ui.dpf(40), Ui.screenH - Ui.dpf(80), Ui.screenW / 2f + Ui.dpf(40), Ui.screenH - Ui.dpf(40));
        GlassMenu.customSheet(m, anchor, m.getString(R.string.info), box, null);
    }

    private static void addInfoRow(MainActivity m, LinearLayout parent, String k, String val, int fg, int fg2) {
        LinearLayout row = new LinearLayout(m);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(7), 0, Ui.dp(7));
        row.addView(Ui.text(m, k, 12, Ui.semibold, fg2));
        TextView tv = Ui.text(m, val == null ? "" : val, 14.5f, Ui.regular, fg);
        tv.setSingleLine(false);
        tv.setPadding(0, Ui.dp(2), 0, 0);
        row.addView(tv);
        parent.addView(row, Widgets.MW);
    }

    static void pickPlaylistDialog(MainActivity m, VideoLibrary.Video v) {
        if (VideoLibrary.playlists.isEmpty()) {
            createPlaylistDialog(m, pl -> {
                VideoLibrary.addToPlaylist(pl, v);
                Toast.makeText(m, pl.name + "에 추가했습니다", Toast.LENGTH_SHORT).show();
            });
            return;
        }
        String[] names = new String[VideoLibrary.playlists.size() + 1];
        for (int i = 0; i < VideoLibrary.playlists.size(); i++) names[i] = VideoLibrary.playlists.get(i).name;
        names[VideoLibrary.playlists.size()] = "+ " + m.getString(R.string.new_playlist);
        Widgets.pickSheet(m, null, m.getString(R.string.add_to_playlist), names, -1, which -> {
            if (which == VideoLibrary.playlists.size()) {
                createPlaylistDialog(m, pl -> VideoLibrary.addToPlaylist(pl, v));
            } else {
                VideoLibrary.Playlist pl = VideoLibrary.playlists.get(which);
                VideoLibrary.addToPlaylist(pl, v);
                Toast.makeText(m, pl.name + "에 추가했습니다", Toast.LENGTH_SHORT).show();
            }
        });
    }

    interface PlaylistCb { void onCreated(VideoLibrary.Playlist pl); }

    static void createPlaylistDialog(MainActivity m, PlaylistCb cb) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText et = Widgets.formField(m, "이름", "재생목록 이름을 입력하세요", "", InputType.TYPE_CLASS_TEXT, box);
        Widgets.showFormSheet(m, m.getString(R.string.new_playlist), box, m.getString(R.string.ok), () -> {
            String n = et.getText().toString().trim();
            if (!n.isEmpty()) {
                VideoLibrary.Playlist pl = VideoLibrary.createPlaylist(n);
                if (cb != null) cb.onCreated(pl);
                m.reloadAll();
            }
        });
    }

    static void openStreamDialog(MainActivity m) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText url = Widgets.formField(m, "스트림 주소 (URL)", m.getString(R.string.open_url_hint), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, box);

        // Quick clipboard paste chip
        TextView paste = Ui.text(m, m.getString(R.string.paste), 12.5f, Ui.semibold, Ui.ACCENT);
        paste.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        paste.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.12f), 100));
        paste.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) m.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(m);
                if (cs != null) url.setText(cs.toString().trim());
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(Ui.dp(4), Ui.dp(2), 0, Ui.dp(4));
        box.addView(paste, plp);

        EditText title = Widgets.formField(m, "이름 (선택)", "표시할 제목을 입력하세요", "", InputType.TYPE_CLASS_TEXT, box);
        Widgets.showFormSheet(m, m.getString(R.string.open_url), box, m.getString(R.string.play), () -> {
            String u = url.getText().toString().trim();
            if (!u.isEmpty()) {
                VideoLibrary.Video v = VideoLibrary.addStream(u, title.getText().toString());
                m.reloadAll();
                m.playVideo(v, VideoLibrary.streams);
            }
        });
    }

    static View emptyBox(MainActivity m, boolean needPerm) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(32), Ui.dp(56), Ui.dp(32), Ui.dp(40));
        box.addView(new Icons.IconView(m, Icons.VIDEO, 54, Ui.text3()), new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64)));
        TextView msg = Ui.text(m, m.getString(R.string.empty_videos), 15.5f, Ui.medium, Ui.text2());
        msg.setSingleLine(false);
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(14);
        mlp.bottomMargin = Ui.dp(20);
        box.addView(msg, mlp);

        LinearLayout btns = new LinearLayout(m);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        if (needPerm) {
            btns.addView(Widgets.button(m, m.getString(R.string.grant_permission), true, v -> m.requestVideoPermission()));
        } else {
            btns.addView(Widgets.button(m, m.getString(R.string.open_file), true, v -> m.pickVideoFile()));
            View sp = new View(m);
            btns.addView(sp, new LinearLayout.LayoutParams(Ui.dp(10), 1));
            btns.addView(Widgets.button(m, m.getString(R.string.open_url), false, v -> openStreamDialog(m)));
        }
        box.addView(btns);
        return box;
    }

    // ------------------------------------------------------------------ Home
    static final class HomePage extends Page {
        HomePage(MainActivity m) {
            super(m, m.getString(R.string.tab_home), true);
            headerIcons = new int[]{Icons.PLUS, Icons.MORE};
        }

        @Override void build() {
            MainActivity m = main;
            if (!VideoLibrary.hasPermission(m) && VideoLibrary.allVideos.isEmpty() && VideoLibrary.streams.isEmpty()) {
                content.addView(emptyBox(m, true), Widgets.MW);
                return;
            }

            // Continue watching rail
            List<VideoLibrary.Video> cw = VideoLibrary.continueWatching();
            if (!cw.isEmpty()) {
                content.addView(Widgets.section(m, m.getString(R.string.continue_watching)));
                LinearLayout rail = Widgets.rail(content, 12);
                for (VideoLibrary.Video v : cw) {
                    rail.addView(Widgets.continueCard(m, v, vv -> m.playVideo(v, cw)));
                }
            }

            // Recently added videos
            if (!VideoLibrary.allVideos.isEmpty()) {
                content.addView(Widgets.section(m, m.getString(R.string.recently_added)));
                List<VideoLibrary.Video> recent = VideoLibrary.allVideos.subList(0, Math.min(8, VideoLibrary.allVideos.size()));
                addVideoGrid(m, content, recent);
            } else if (VideoLibrary.streams.isEmpty()) {
                content.addView(emptyBox(m, false), Widgets.MW);
            }

            // Folders quick group
            if (!VideoLibrary.folders.isEmpty()) {
                content.addView(Widgets.section(m, m.getString(R.string.quick_folders)));
                Widgets.Group g = new Widgets.Group(m, null);
                int max = Math.min(5, VideoLibrary.folders.size());
                for (int i = 0; i < max; i++) {
                    VideoLibrary.Folder f = VideoLibrary.folders.get(i);
                    g.row(Icons.FOLDER, Ui.ACCENT, f.name, m.getString(R.string.n_videos, f.videos.size()),
                            true, v -> m.push(new FolderDetailPage(m, f)));
                }
                content.addView(g, Widgets.MW);
            }
        }
    }

    // ------------------------------------------------------------------ Folders
    static final class FoldersPage extends Page {
        FoldersPage(MainActivity m) {
            super(m, m.getString(R.string.tab_folders), true);
            headerIcons = new int[]{Icons.REFRESH, Icons.MORE};
            setGrouped(true);
        }

        @Override void build() {
            MainActivity m = main;
            if (VideoLibrary.folders.isEmpty()) {
                content.addView(emptyBox(m, !VideoLibrary.hasPermission(m)), Widgets.MW);
                return;
            }
            Widgets.Group g = new Widgets.Group(m, null);
            for (VideoLibrary.Folder f : VideoLibrary.folders) {
                String val = m.getString(R.string.n_videos, f.videos.size()) + " · " + Ui.size(f.totalSize);
                g.row(Icons.FOLDER, Ui.ACCENT, f.name, val, true, v -> m.push(new FolderDetailPage(m, f)));
            }
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Folder detail
    static final class FolderDetailPage extends Page {
        private final VideoLibrary.Folder folder;

        FolderDetailPage(MainActivity m, VideoLibrary.Folder folder) {
            super(m, folder.name, true);
            this.folder = folder;
            back = true;
            headerIcons = new int[]{Icons.SORT, VideoLibrary.gridMode ? Icons.LIST : Icons.GRID};
        }

        @Override void build() {
            List<VideoLibrary.Video> sorted = VideoLibrary.sorted(folder.videos);
            if (VideoLibrary.gridMode) addVideoGrid(main, content, sorted);
            else addVideoList(main, content, sorted);
        }
    }

    // ------------------------------------------------------------------ Library
    static final class LibraryPage extends Page {
        LibraryPage(MainActivity m) {
            super(m, m.getString(R.string.tab_library), true);
            headerIcons = new int[]{Icons.PLUS};
            setGrouped(true);
        }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, null);
            g.row(Icons.VIDEO, Ui.ACCENT, m.getString(R.string.all_videos),
                    String.valueOf(VideoLibrary.allVideos.size()), true, v -> m.openLibrarySection("all"));
            g.row(Icons.CLOCK, Ui.ACCENT, m.getString(R.string.recent_history),
                    String.valueOf(VideoLibrary.historyVideos().size()), true, v -> m.openLibrarySection("recent"));
            g.row(Icons.HEART, Ui.ACCENT, m.getString(R.string.favorites),
                    String.valueOf(VideoLibrary.favoriteVideos().size()), true, v -> m.openLibrarySection("favorites"));
            g.row(Icons.PLAYLIST, Ui.ACCENT, m.getString(R.string.playlists),
                    String.valueOf(VideoLibrary.playlists.size()), true, v -> m.openLibrarySection("playlists"));
            g.row(Icons.LINK, Ui.ACCENT, m.getString(R.string.network_streams),
                    String.valueOf(VideoLibrary.streams.size()), true, v -> m.openLibrarySection("streams"));
            g.row(Icons.AI, Ui.ACCENT, m.getString(R.string.ai_subtitles),
                    String.valueOf(AiSubtitle.listGenerated(m).size()), true, v -> m.openLibrarySection("ai_subs"));
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Video list sub-page
    static final class VideoListPage extends Page {
        private final String section;

        VideoListPage(MainActivity m, String title, String section) {
            super(m, title, true);
            this.section = section;
            back = true;
            headerIcons = "streams".equals(section) ? new int[]{Icons.PLUS}
                    : new int[]{Icons.SORT, VideoLibrary.gridMode ? Icons.LIST : Icons.GRID};
        }

        @Override void build() {
            List<VideoLibrary.Video> list;
            switch (section) {
                case "recent": list = VideoLibrary.historyVideos(); break;
                case "favorites": list = VideoLibrary.favoriteVideos(); break;
                case "streams": list = VideoLibrary.streams; break;
                default: list = VideoLibrary.sorted(VideoLibrary.allVideos); break;
            }
            if (list.isEmpty()) {
                content.addView(emptyBox(main, !VideoLibrary.hasPermission(main) && "all".equals(section)), Widgets.MW);
                return;
            }
            if (VideoLibrary.gridMode && !"streams".equals(section)) addVideoGrid(main, content, list);
            else addVideoList(main, content, list);
        }
    }

    // ------------------------------------------------------------------ Playlists
    static final class PlaylistsPage extends Page {
        PlaylistsPage(MainActivity m) {
            super(m, m.getString(R.string.playlists), true);
            back = true;
            headerIcons = new int[]{Icons.PLUS};
            setGrouped(true);
        }

        @Override void build() {
            MainActivity m = main;
            if (VideoLibrary.playlists.isEmpty()) {
                LinearLayout b = new LinearLayout(m);
                b.setOrientation(LinearLayout.VERTICAL);
                b.setGravity(Gravity.CENTER_HORIZONTAL);
                b.setPadding(Ui.dp(24), Ui.dp(48), Ui.dp(24), Ui.dp(24));
                b.addView(Widgets.button(m, "+ " + m.getString(R.string.new_playlist), true, v -> createPlaylistDialog(m, pl -> rebuild())));
                content.addView(b, Widgets.MW);
                return;
            }
            Widgets.Group g = new Widgets.Group(m, null);
            for (VideoLibrary.Playlist pl : VideoLibrary.playlists) {
                g.row(Icons.PLAYLIST, Ui.ACCENT, pl.name, m.getString(R.string.n_videos, pl.videoKeys.size()), true, v -> {
                    List<VideoLibrary.Video> items = new ArrayList<>();
                    for (String k : pl.videoKeys) {
                        VideoLibrary.Video vv = VideoLibrary.findByKey(k);
                        if (vv != null) items.add(vv);
                    }
                    if (!items.isEmpty()) m.playVideo(items.get(0), items);
                    else Toast.makeText(m, "재생목록이 비어 있습니다", Toast.LENGTH_SHORT).show();
                });
            }
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ AI Subtitles manager
    static final class AiSubtitlesPage extends Page {
        AiSubtitlesPage(MainActivity m) {
            super(m, m.getString(R.string.ai_subtitles), true);
            back = true;
            headerIcons = new int[0];
            setGrouped(true);
        }

        @Override void build() {
            MainActivity m = main;
            List<File> files = AiSubtitle.listGenerated(m);
            if (files.isEmpty()) {
                content.addView(Widgets.caption(m, "아직 생성된 AI 자막이 없습니다. 동영상 재생 중 자막 → AI 자막 자동 생성을 눌러보세요."));
                return;
            }
            Widgets.Group g = new Widgets.Group(m, "저장된 자막 (" + files.size() + ")");
            for (File f : files) {
                g.row(Icons.SUBTITLE, Ui.ACCENT, f.getName(), Ui.size(f.length()), false, v -> {
                    Subtitle.Track t = Subtitle.loadFile(f);
                    int count = t == null ? 0 : t.cues.size();
                    List<GlassMenu.Entry> es = new ArrayList<>();
                    es.add(new GlassMenu.Entry("대사 " + count + "줄 · " + Ui.size(f.length()), Icons.INFO, false, null));
                    es.add(new GlassMenu.Entry("자막 파일 삭제", Icons.CLOSE, false, () -> { f.delete(); rebuild(); }));
                    float[] o = Glass.abs(v);
                    GlassMenu.sheet(m, new RectF(o[0], o[1], o[0] + v.getWidth(), o[1] + v.getHeight()), f.getName(), es, null);
                });
            }
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Search (filters + recent searches)
    static final class SearchPage extends Page {
        private String query = "";
        private int filterMode = 0; // 0=all, 1=7days, 2=hd, 3=sub, 4=fav
        private static final String[] FILTERS = {"전체", "지난 7일", "HD · 4K", "자막 있음", "즐겨찾기"};

        SearchPage(MainActivity m) {
            super(m, m.getString(R.string.search), false);
            headerIcons = new int[0];
            setGrouped(true);
        }

        void setQuery(String q) {
            query = q == null ? "" : q.trim().toLowerCase(Locale.US);
            rebuild();
        }

        void commitQuery(String q) {
            String s = q == null ? "" : q.trim();
            if (s.isEmpty()) return;
            List<String> rec = recentSearches(main);
            rec.remove(s);
            rec.add(0, s);
            while (rec.size() > 8) rec.remove(rec.size() - 1);
            saveRecentSearches(main, rec);
        }

        private static List<String> recentSearches(MainActivity m) {
            String raw = MainActivity.prefs(m).getString("recent_searches", "");
            List<String> out = new ArrayList<>();
            if (!raw.isEmpty()) {
                for (String s : raw.split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
            }
            return out;
        }

        private static void saveRecentSearches(MainActivity m, List<String> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(list.get(i));
            }
            MainActivity.prefs(m).edit().putString("recent_searches", sb.toString()).apply();
        }

        @Override void build() {
            MainActivity m = main;

            // Filter card
            Widgets.Group filterCard = new Widgets.Group(m, m.getString(R.string.filter));
            LinearLayout fWrap = new LinearLayout(m);
            fWrap.setOrientation(LinearLayout.VERTICAL);
            fWrap.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            LinearLayout chipRow = Widgets.rail(fWrap, 8);
            chipRow.setPadding(0, 0, 0, 0);
            for (int i = 0; i < FILTERS.length; i++) {
                final int idx = i;
                boolean on = filterMode == i;
                TextView ch = Ui.text(m, FILTERS[i], 13.5f, on ? Ui.semibold : Ui.medium, on ? 0xFFFFFFFF : Ui.text());
                ch.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
                android.graphics.drawable.GradientDrawable bg = Ui.round(on ? Ui.ACCENT : Ui.card(), 100);
                if (!on) bg.setStroke(Ui.dp(1), Ui.separator());
                ch.setBackground(bg);
                ch.setOnClickListener(v -> { filterMode = idx; rebuild(); });
                Ui.press(ch, 0.94f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(8);
                chipRow.addView(ch, lp);
            }
            filterCard.addCustom(fWrap);
            content.addView(filterCard, Widgets.MW);

            if (query.isEmpty() && filterMode == 0) {
                List<String> rec = recentSearches(m);
                if (!rec.isEmpty()) {
                    Widgets.Group recCard = new Widgets.Group(m, null);
                    LinearLayout rWrap = new LinearLayout(m);
                    rWrap.setOrientation(LinearLayout.VERTICAL);
                    rWrap.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));

                    LinearLayout head = new LinearLayout(m);
                    head.setGravity(Gravity.CENTER_VERTICAL);
                    head.addView(Ui.text(m, m.getString(R.string.recent_searches), 13.5f, Ui.semibold, Ui.text2()),
                            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    TextView clear = Ui.text(m, m.getString(R.string.clear_all), 13, Ui.semibold, Ui.ACCENT);
                    clear.setOnClickListener(v -> { saveRecentSearches(m, new ArrayList<>()); rebuild(); });
                    head.addView(clear);
                    rWrap.addView(head, Widgets.MW);

                    LinearLayout rRow = Widgets.rail(rWrap, 8);
                    rRow.setPadding(0, Ui.dp(10), 0, 0);
                    for (String s : rec) {
                        LinearLayout pill = new LinearLayout(m);
                        pill.setGravity(Gravity.CENTER_VERTICAL);
                        pill.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(10), Ui.dp(7));
                        pill.setBackground(Ui.round(Ui.chip(), 100));
                        TextView tv = Ui.text(m, s, 13, Ui.medium, Ui.text());
                        tv.setOnClickListener(v -> { m.dock.field.setText(s); m.dock.field.setSelection(s.length()); });
                        pill.addView(tv);
                        Icons.IconView rm = new Icons.IconView(m, Icons.CLOSE, 13, Ui.text2());
                        rm.setOnClickListener(v -> {
                            List<String> cur = recentSearches(m);
                            cur.remove(s);
                            saveRecentSearches(m, cur);
                            rebuild();
                        });
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
                        rlp.leftMargin = Ui.dp(4);
                        pill.addView(rm, rlp);
                        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        plp.rightMargin = Ui.dp(8);
                        rRow.addView(pill, plp);
                    }
                    recCard.addCustom(rWrap);
                    content.addView(recCard, Widgets.MW);
                }
                return;
            }

            long weekAgo = System.currentTimeMillis() / 1000L - 7L * 86400L;
            List<VideoLibrary.Video> matches = new ArrayList<>();
            for (VideoLibrary.Video v : VideoLibrary.allVideos) {
                if (!query.isEmpty() && !v.title.toLowerCase(Locale.US).contains(query) && !v.folder.toLowerCase(Locale.US).contains(query)) continue;
                if (filterMode == 1 && v.dateAddedSec < weekAgo) continue;
                if (filterMode == 2 && Math.max(v.width, v.height) < 1280) continue;
                if (filterMode == 3 && !v.hasSubtitle) continue;
                if (filterMode == 4 && !VideoLibrary.isFavorite(v)) continue;
                matches.add(v);
            }
            if (filterMode == 0) {
                for (VideoLibrary.Video v : VideoLibrary.streams) {
                    if (query.isEmpty() || v.title.toLowerCase(Locale.US).contains(query) || v.uri.toLowerCase(Locale.US).contains(query)) {
                        matches.add(v);
                    }
                }
            }
            if (matches.isEmpty()) {
                content.addView(Widgets.caption(m, "검색 결과가 없습니다."));
                return;
            }
            addVideoList(m, content, matches);
        }
    }
}
