package com.oneuibook.play;

import android.graphics.Paint;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A scrolling screen with a large title that collapses smoothly into the glass header. */
abstract class Page extends FrameLayout {
    final MainActivity main;
    final Scroll scroll;
    final LinearLayout content;
    TextView bigTitle;
    String title;
    int[] headerIcons = {Icons.PLUS, Icons.MORE};
    boolean back;
    boolean grouped;
    private boolean started;

    final class Scroll extends ScrollView {
        Scroll() {
            super(Page.this.getContext());
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            setClipToPadding(false);
        }

        @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            main.onPageScroll(Page.this, t, t - ot);
            Page.this.onScrolled(t);
        }
    }

    Page(MainActivity m, String title, boolean largeTitle) {
        super(m);
        main = m;
        this.title = title;
        scroll = new Scroll();
        content = new LinearLayout(m);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyInsets();
        if (largeTitle) addBigTitle();
    }

    void applyInsets() {
        int top = Ui.statusInset + Ui.dp(largeTop() ? 64 : 60);
        content.setPadding(0, top, main.sideInset(), main.bottomPadding());
    }

    boolean largeTop() { return true; }

    void addBigTitle() {
        bigTitle = Ui.text(getContext(), title, Ui.gStyle ? 34 : 36, Ui.bold, Ui.text());
        bigTitle.setLetterSpacing(Ui.gStyle ? -0.02f : -0.01f);
        int avail = Math.max(Ui.dp(120), Ui.screenW - main.sideInset() - main.contentInsetPx() - Ui.dp(Ui.gStyle ? 44 : 34));
        float size = Math.min(Ui.gStyle ? 34f : 36f, fitTitleSize(title, avail));
        bigTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        bigTitle.setSingleLine(false);
        bigTitle.setMaxLines(3);
        bigTitle.setLineSpacing(0, 1.05f);
        boolean oneLine = lines(title, avail, size) <= 1;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                oneLine ? Ui.dp(50) : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(Ui.gStyle ? 24 : 18), Ui.dp(Ui.cover() ? 4 : 14), Ui.dp(Ui.gStyle ? 20 : 16), Ui.dp(Ui.cover() ? 0 : 6));
        content.addView(bigTitle, lp);
    }

    private static int lines(String s, int width, float sp) {
        if (s == null || s.isEmpty() || width <= 0) return 1;
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Ui.bold);
        p.setTextSize(Ui.sp(sp));
        p.setLetterSpacing(-0.01f);
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, width).build().getLineCount();
    }

    static float fitTitleSize(String s, int width) {
        for (int maxLines = 1; maxLines <= 2; maxLines++)
            for (float sp = 36f; sp >= 26f; sp -= 2f) if (lines(s, width, sp) <= maxLines) return sp;
        return 24f;
    }

    void setGrouped(boolean g) {
        grouped = g;
        setBackgroundColor(g ? Ui.groupedBg() : Ui.bg());
    }

    void show() {
        if (!started) {
            started = true;
            if (getBackground() == null) setBackgroundColor(grouped ? Ui.groupedBg() : Ui.bg());
            build();
        }
    }

    void rebuild() {
        started = false;
        content.removeAllViews();
        if (bigTitle != null) addBigTitle();
        show();
    }

    abstract void build();

    void onScrolled(int y) {}

    float titleProgress() {
        int y = scroll.getScrollY();
        if (y <= Ui.dp(8)) return 0f;
        if (bigTitle == null) return Ui.clamp((y - Ui.dp(140)) / (float) Ui.dp(24), 0f, 1f);
        if (bigTitle.getHeight() <= 0) return 0f;
        float start = Math.max(Ui.dpf(20), bigTitle.getTop() - Ui.statusInset - Ui.dpf(32));
        return Ui.clamp((y - start) / (float) Ui.dp(18), 0f, 1f);
    }

    float blurProgress() { return Ui.clamp(scroll.getScrollY() / (float) Ui.dp(20), 0f, 1f); }

    void scrollToTop() { scroll.smoothScrollTo(0, 0); }
}
