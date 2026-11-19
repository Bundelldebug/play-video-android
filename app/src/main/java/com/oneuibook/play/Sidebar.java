package com.oneuibook.play;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Tablet & unfolded sidebar (matching Prism): app name, search pill, 4 tabs, collapsible library shortcuts. */
final class Sidebar extends FrameLayout {
    private final MainActivity m;
    private final List<LinearLayout> tabRows = new ArrayList<>();
    private final LinearLayout libBox;
    private final Icons.IconView libChevron;
    private boolean libOpen = true;

    static float widthFor(int W) { return Ui.clamp(W * 0.27f, Ui.dpf(240), Ui.dpf(320)); }

    Sidebar(MainActivity m) {
        super(m);
        this.m = m;
        setBackgroundColor(Ui.gStyle ? Ui.groupedBg() : (Ui.dark ? 0xFF161618 : 0xFFF2F2F5));
        setClickable(true);

        ScrollView sv = new ScrollView(m);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(m);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(12), Ui.statusInset + Ui.dp(12), Ui.dp(12), m.bottomPadding());
        sv.addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout top = new LinearLayout(m);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(8), 0, 0, Ui.dp(14));
        top.addView(Ui.text(m, m.getString(R.string.app_name), 21, Ui.bold, Ui.text()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Icons.IconView close = new Icons.IconView(m, Icons.SIDEBAR, 22, Ui.text());
        GradientDrawable cb = Ui.round(Ui.card(), 100);
        cb.setStroke(Ui.dp(1), Ui.dark ? 0x33FFFFFF : 0x1F000000);
        close.setBackground(cb);
        close.setElevation(Ui.dpf(2));
        close.setOnClickListener(v -> m.closeSidebar());
        Ui.press(close, 0.9f);
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(46), Ui.dp(46)));
        col.addView(top, Widgets.MW);

        LinearLayout search = new LinearLayout(m);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        search.setBackground(Ui.round(Ui.dark ? 0xFF2C2C2E : 0xFFE3E3E8, 100));
        search.addView(new Icons.IconView(m, Icons.SEARCH, 18, Ui.text2()), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        TextView hint = Ui.text(m, m.getString(R.string.search), 16, Ui.regular, Ui.text2());
        hint.setPadding(Ui.dp(8), 0, 0, 0);
        search.addView(hint);
        search.setOnClickListener(v -> m.sidebarSearch());
        Ui.press(search, 0.97f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(44));
        slp.bottomMargin = Ui.dp(16);
        col.addView(search, slp);

        int[] titles = {R.string.tab_home, R.string.tab_folders, R.string.tab_library, R.string.tab_settings};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout r = row(Dock.TAB_ICONS[i], m.getString(titles[i]), v -> m.sidebarTab(idx));
            tabRows.add(r);
            col.addView(r, rowLp());
        }

        LinearLayout head = new LinearLayout(m);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(10), Ui.dp(22), Ui.dp(10), Ui.dp(6));
        head.addView(Ui.text(m, m.getString(R.string.tab_library), 13, Ui.medium, Ui.text2()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        libChevron = new Icons.IconView(m, Icons.CHEVRON, 14, Ui.text2());
        libChevron.setRotation(90);
        head.addView(libChevron, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        head.setOnClickListener(v -> toggleLibrary());
        col.addView(head, Widgets.MW);

        libBox = new LinearLayout(m);
        libBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(libBox, Widgets.MW);
        libBox.addView(row(Icons.VIDEO, m.getString(R.string.all_videos), v -> m.openLibrarySection("all")), rowLp());
        libBox.addView(row(Icons.CLOCK, m.getString(R.string.recent_history), v -> m.openLibrarySection("recent")), rowLp());
        libBox.addView(row(Icons.HEART, m.getString(R.string.favorites), v -> m.openLibrarySection("favorites")), rowLp());
        libBox.addView(row(Icons.PLAYLIST, m.getString(R.string.playlists), v -> m.openLibrarySection("playlists")), rowLp());
        libBox.addView(row(Icons.LINK, m.getString(R.string.network_streams), v -> m.openLibrarySection("streams")), rowLp());
        libBox.addView(row(Icons.AI, m.getString(R.string.ai_subtitles), v -> m.openLibrarySection("ai_subs")), rowLp());
    }

    private void toggleLibrary() {
        libOpen = !libOpen;
        libChevron.animate().rotation(libOpen ? 90 : 0).setDuration(240).setInterpolator(Ui.EMPHASIZED).start();
        if (libOpen) {
            libBox.setVisibility(VISIBLE);
            libBox.setAlpha(0f);
            libBox.setTranslationY(-Ui.dpf(8));
            libBox.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Ui.EMPHASIZED).withEndAction(null).start();
        } else {
            libBox.animate().alpha(0f).translationY(-Ui.dpf(8)).setDuration(180).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> libBox.setVisibility(GONE)).start();
        }
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(46));
        lp.bottomMargin = Ui.dp(2);
        return lp;
    }

    private LinearLayout row(int icon, String title, OnClickListener click) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        r.addView(new Icons.IconView(getContext(), icon, 21, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26)));
        TextView t = Ui.text(getContext(), title, 17, Ui.regular, Ui.text());
        t.setPadding(Ui.dp(14), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(click);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.pressed()), null, Ui.round(0xFFFFFFFF, 100)));
        return r;
    }

    void setSelected(int tab) {
        for (int i = 0; i < tabRows.size(); i++) {
            LinearLayout r = tabRows.get(i);
            boolean on = i == tab;
            int selCol = Ui.gStyle ? Ui.alpha(Ui.ACCENT, Ui.dark ? 0.22f : 0.13f) : (Ui.dark ? 0x33FFFFFF : 0x17000000);
            Drawable base = on ? Ui.round(selCol, 100) : null;
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.pressed()), base, Ui.round(0xFFFFFFFF, 100)));
            TextView t = (TextView) r.getChildAt(1);
            t.setTypeface(on ? Ui.semibold : Ui.regular);
            t.setTextColor(on && Ui.gStyle ? Ui.ACCENT : Ui.text());
        }
    }
}
