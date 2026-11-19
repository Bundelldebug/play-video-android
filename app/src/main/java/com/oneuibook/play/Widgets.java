package com.oneuibook.play;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Reusable cards, video thumbnails, groups, toggles, and sliders. */
final class Widgets {
    private Widgets() {}

    static final LinearLayout.LayoutParams MW = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

    static RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(Ui.pressed()), null, Ui.round(0xFFFFFFFF, 16));
    }

    static TextView section(Context c, String title) {
        TextView t = Ui.text(c, title, 21, Ui.bold, Ui.text());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(20), Ui.dp(26), Ui.dp(18), Ui.dp(12));
        t.setLayoutParams(lp);
        return t;
    }

    static TextView caption(Context c, String text) {
        TextView t = Ui.text(c, text, 13, Ui.regular, Ui.text2());
        t.setSingleLine(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(22), Ui.dp(4), Ui.dp(22), Ui.dp(10));
        t.setLayoutParams(lp);
        return t;
    }

    static LinearLayout rail(LinearLayout parent, int spacingDp) {
        HorizontalScrollView hs = new HorizontalScrollView(parent.getContext());
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(18), 0, Ui.dp(18 - spacingDp), 0);
        hs.addView(row);
        parent.addView(hs, MW);
        row.setTag(spacingDp);
        return row;
    }

    static int contentW(MainActivity m) {
        return Math.max(Ui.dp(260), Ui.screenW - (m == null ? 0 : m.sideInset() + m.contentInsetPx()));
    }

    /** Featured 16:9 continue-watching poster card with gradient, progress bar, and duration badge. */
    static View continueCard(Context c, VideoLibrary.Video v, View.OnClickListener click) {
        int w = Math.min(Ui.dp(320), (int) (Math.min(Ui.screenW, Ui.dp(680)) * 0.72f));
        int h = (int) (w * 9f / 16f);
        float rad = Ui.gStyle ? 24f : 18f;

        FrameLayout f = new FrameLayout(c);
        VideoLibrary.ThumbView tv = new VideoLibrary.ThumbView(c, rad);
        tv.bind(v);
        f.addView(tv, new FrameLayout.LayoutParams(w, h));

        View shade = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF r = new RectF();
            @Override protected void onDraw(Canvas cv) {
                p.setShader(new LinearGradient(0, getHeight() * 0.4f, 0, getHeight(),
                        new int[]{0x00000000, 0x55000000, 0xB8000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                r.set(0, 0, getWidth(), getHeight());
                cv.drawRoundRect(r, Ui.dpf(rad), Ui.dpf(rad), p);
            }
        };
        f.addView(shade, new FrameLayout.LayoutParams(w, h));

        // Top-right badges (resolution + CC)
        LinearLayout badges = new LinearLayout(c);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        String res = v.resolutionBadge();
        if (!res.isEmpty()) badges.addView(pillBadge(c, res, 0x99000000, 0xFFFFFFFF));
        if (v.hasSubtitle) badges.addView(pillBadge(c, "CC", Ui.alpha(Ui.ACCENT, 0.88f), 0xFFFFFFFF));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        blp.setMargins(0, Ui.dp(10), Ui.dp(10), 0);
        f.addView(badges, blp);

        // Bottom title + progress bar
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(12));
        TextView t = Ui.text(c, v.title, 15, Ui.semibold, 0xFFFFFFFF);
        bottom.addView(t);

        long pos = VideoLibrary.getResume(v.key());
        String subTxt = Ui.time(pos) + (v.durationMs > 0 ? " / " + Ui.time(v.durationMs) : "");
        TextView st = Ui.text(c, subTxt, 11.5f, Ui.medium, 0xCCFFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(2);
        bottom.addView(st, slp);

        if (v.durationMs > 0 && pos > 0) {
            float prog = Ui.clamp(pos / (float) v.durationMs, 0.02f, 1f);
            View bar = new View(c) {
                final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas cv) {
                    float rh = getHeight() / 2f;
                    bp.setColor(0x4DFFFFFF);
                    cv.drawRoundRect(0, 0, getWidth(), getHeight(), rh, rh, bp);
                    bp.setColor(Ui.ACCENT);
                    cv.drawRoundRect(0, 0, getWidth() * prog, getHeight(), rh, rh, bp);
                }
            };
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(4));
            plp.topMargin = Ui.dp(8);
            bottom.addView(bar, plp);
        }

        f.addView(bottom, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(w, h);
        flp.rightMargin = Ui.dp(12);
        f.setLayoutParams(flp);
        f.setOnClickListener(click);
        Ui.press(f, Ui.gStyle ? 0.98f : 0.96f);
        return f;
    }

    /** 16:9 grid video card. */
    static View videoGridCard(Context c, VideoLibrary.Video v, int widthPx, View.OnClickListener click, View.OnLongClickListener longClick) {
        int h = (int) (widthPx * 9f / 16f);
        float rad = Ui.gStyle ? 20f : 14f;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);

        FrameLayout thumbBox = new FrameLayout(c);
        VideoLibrary.ThumbView tv = new VideoLibrary.ThumbView(c, rad);
        tv.bind(v);
        thumbBox.addView(tv, new FrameLayout.LayoutParams(widthPx, h));

        if (v.durationMs > 0) {
            TextView dur = pillBadge(c, Ui.time(v.durationMs), 0xB3000000, 0xFFFFFFFF);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            dlp.setMargins(0, 0, Ui.dp(8), Ui.dp(8));
            thumbBox.addView(dur, dlp);
        }

        long pos = VideoLibrary.getResume(v.key());
        if (v.durationMs > 0 && pos > 3000) {
            float prog = Ui.clamp(pos / (float) v.durationMs, 0.02f, 1f);
            View bar = new View(c) {
                final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas cv) {
                    bp.setColor(0x55000000);
                    cv.drawRect(0, 0, getWidth(), getHeight(), bp);
                    bp.setColor(Ui.ACCENT);
                    cv.drawRect(0, 0, getWidth() * prog, getHeight(), bp);
                }
            };
            thumbBox.addView(bar, new FrameLayout.LayoutParams(widthPx, Ui.dp(3), Gravity.BOTTOM));
        }

        box.addView(thumbBox, new LinearLayout.LayoutParams(widthPx, h));
        TextView title = Ui.text(c, v.title, 13.5f, Ui.semibold, Ui.text());
        title.setSingleLine(false);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(7);
        box.addView(title, tlp);

        String meta = v.isStream ? "네트워크 스트림" : (v.folder + " · " + Ui.size(v.sizeBytes));
        TextView sub = Ui.text(c, meta, 11.5f, Ui.regular, Ui.text2());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(2);
        box.addView(sub, slp);

        box.setOnClickListener(click);
        if (longClick != null) box.setOnLongClickListener(longClick);
        Ui.press(box, Ui.gStyle ? 0.98f : 0.96f);
        return box;
    }

    /** List row for videos with 16:9 thumbnail, title, metadata, and more button. */
    static View videoRow(Context c, VideoLibrary.Video v, View.OnClickListener click, View.OnClickListener moreClick) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        r.setBackground(ripple());

        int tw = Ui.dp(108), th = Ui.dp(62);
        FrameLayout tb = new FrameLayout(c);
        VideoLibrary.ThumbView tv = new VideoLibrary.ThumbView(c, Ui.gStyle ? 14f : 10f);
        tv.bind(v);
        tb.addView(tv, new FrameLayout.LayoutParams(tw, th));
        if (v.durationMs > 0) {
            TextView dur = pillBadge(c, Ui.time(v.durationMs), 0xB8000000, 0xFFFFFFFF);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            dlp.setMargins(0, 0, Ui.dp(4), Ui.dp(4));
            tb.addView(dur, dlp);
        }
        r.addView(tb, new LinearLayout.LayoutParams(tw, th));

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(14), 0, Ui.dp(6), 0);
        TextView t = Ui.text(c, v.title, 15, Ui.semibold, Ui.text());
        t.setSingleLine(false);
        t.setMaxLines(2);
        info.addView(t);
        String res = v.resolutionBadge();
        String subTxt = (res.isEmpty() ? "" : res + " · ") + (v.isStream ? "스트림" : Ui.size(v.sizeBytes)) + (v.hasSubtitle ? " · 자막" : "");
        TextView st = Ui.text(c, subTxt, 12.5f, Ui.regular, Ui.text2());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(3);
        info.addView(st, slp);
        r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (moreClick != null) {
            Icons.IconView more = new Icons.IconView(c, Icons.MORE, 20, Ui.text2());
            more.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
            more.setOnClickListener(moreClick);
            r.addView(more, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        }

        r.setOnClickListener(click);
        return r;
    }

    static TextView pillBadge(Context c, String text, int bg, int fg) {
        TextView t = Ui.text(c, text, 10, Ui.bold, fg);
        t.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2));
        t.setBackground(Ui.round(bg, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** Primary pill button. */
    static TextView button(Context c, String label, boolean primary, View.OnClickListener click) {
        TextView b = Ui.text(c, label, 15, Ui.semibold, primary ? 0xFFFFFFFF : Ui.text());
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(22), Ui.dp(12), Ui.dp(22), Ui.dp(12));
        b.setBackground(Ui.round(primary ? Ui.ACCENT : Ui.chip(), 100));
        b.setOnClickListener(click);
        Ui.press(b, 0.95f);
        return b;
    }

    /** Grouped inset card (26dp radius in G style). */
    static final class Group extends LinearLayout {
        private final LinearLayout card;
        private int count;

        Group(Context c, String header) {
            super(c);
            setOrientation(VERTICAL);
            setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(6));
            if (header != null && !header.isEmpty()) {
                TextView h = Ui.text(c, header, 13, Ui.semibold, Ui.text2());
                h.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(8));
                addView(h, MW);
            }
            card = new LinearLayout(c);
            card.setOrientation(VERTICAL);
            GradientDrawable bg = Ui.round(Ui.card(), Ui.cardRadius());
            if (Ui.gStyle) bg.setStroke(Ui.dp(0.8f), Ui.gStroke());
            card.setBackground(bg);
            card.setClipToOutline(true);
            addView(card, MW);
        }

        View row(int icon, int iconTint, String title, String value, boolean chevron, OnClickListener click) {
            if (count > 0) {
                View sep = new View(getContext());
                sep.setBackgroundColor(Ui.separator());
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.6f)));
                slp.leftMargin = Ui.dp(icon != 0 ? 58 : 18);
                slp.rightMargin = Ui.dp(18);
                card.addView(sep, slp);
            }
            count++;
            LinearLayout r = new LinearLayout(getContext());
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            if (click != null) {
                r.setBackground(ripple());
                r.setOnClickListener(click);
            }
            if (icon != 0) {
                if (Ui.gStyle) {
                    FrameLayout badge = new FrameLayout(getContext());
                    badge.setBackground(Ui.round(Ui.alpha(iconTint, Ui.dark ? 0.22f : 0.13f), 11));
                    badge.addView(new Icons.IconView(getContext(), icon, 18, iconTint),
                            new FrameLayout.LayoutParams(Ui.dp(32), Ui.dp(32)));
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32));
                    blp.rightMargin = Ui.dp(14);
                    r.addView(badge, blp);
                } else {
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24));
                    ilp.rightMargin = Ui.dp(14);
                    r.addView(new Icons.IconView(getContext(), icon, 21, iconTint), ilp);
                }
            }
            TextView t = Ui.text(getContext(), title, 16, Ui.medium, Ui.text());
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (value != null && !value.isEmpty()) {
                TextView v = Ui.text(getContext(), value, 14.5f, Ui.regular, Ui.text2());
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vlp.leftMargin = Ui.dp(8);
                r.addView(v, vlp);
            }
            if (chevron) {
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
                clp.leftMargin = Ui.dp(6);
                r.addView(new Icons.IconView(getContext(), Icons.CHEVRON, 15, Ui.text3()), clp);
            }
            card.addView(r, MW);
            return r;
        }

        void addCustom(View v) {
            if (count > 0) {
                View sep = new View(getContext());
                sep.setBackgroundColor(Ui.separator());
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.6f)));
                slp.leftMargin = Ui.dp(18); slp.rightMargin = Ui.dp(18);
                card.addView(sep, slp);
            }
            count++;
            card.addView(v, MW);
        }
    }

    /** Spring-animated toggle switch. */
    static final class Toggle extends View {
        interface OnChange { void onChange(boolean on); }
        boolean on;
        OnChange change;
        private final Ui.Spring p = new Ui.Spring(520f, 0.78f, 0.002f);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Toggle(Context c, boolean initial) {
            super(c);
            on = initial;
            p.snap(initial ? 1f : 0f);
            p.onUpdate = this::invalidate;
            setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(30)));
            setOnClickListener(v -> {
                on = !on;
                p.to(on ? 1f : 0f);
                if (change != null) change.onChange(on);
            });
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float k = Ui.clamp(p.value, 0f, 1f);
            int offCol = Ui.dark ? 0xFF3A3A3F : 0xFFD4D4DC;
            paint.setColor(Ui.mix(offCol, Ui.ACCENT, k));
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2f, h / 2f, paint);
            float pad = Ui.dpf(3);
            float d = h - pad * 2;
            float cx = Ui.lerp(pad + d / 2f, w - pad - d / 2f, k);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(cx, h / 2f, d / 2f, paint);
        }
    }

    /** Smooth pill slider for glass transparency, subtitle size, audio boost, EQ, etc. */
    static final class Slider extends View {
        interface OnChange { void onChange(float value, boolean fromUser); }
        float value;
        OnChange change;
        boolean darkSurface;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Slider(Context c, float initial) {
            super(c);
            value = Ui.clamp(initial, 0f, 1f);
            setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(38)));
        }

        void setValue(float v) { value = Ui.clamp(v, 0f, 1f); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float pad = Ui.dpf(12);
            float th = Ui.dpf(6);
            float cy = h / 2f;
            float usable = Math.max(1f, w - pad * 2);
            p.setColor(darkSurface ? 0x33FFFFFF : Ui.separator());
            c.drawRoundRect(pad, cy - th / 2f, w - pad, cy + th / 2f, th / 2f, th / 2f, p);
            float fillX = pad + usable * value;
            p.setColor(Ui.ACCENT);
            c.drawRoundRect(pad, cy - th / 2f, fillX, cy + th / 2f, th / 2f, th / 2f, p);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(Ui.dpf(4), 0, Ui.dpf(1.5f), 0x44000000);
            c.drawCircle(fillX, cy, Ui.dpf(10.5f), p);
            p.clearShadowLayer();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                float pad = Ui.dpf(12);
                float nv = Ui.clamp((e.getX() - pad) / Math.max(1f, getWidth() - pad * 2), 0f, 1f);
                if (Math.abs(nv - value) > 0.002f) {
                    value = nv;
                    invalidate();
                    if (change != null) change.onChange(value, true);
                }
                return true;
            }
            return super.onTouchEvent(e);
        }
    }

    /** Spacious rounded input field for bottom sheets. */
    static android.widget.EditText formField(Context c, String label, String hint, String initial, int inputType, LinearLayout parent) {
        if (label != null && !label.isEmpty()) {
            TextView lb = Ui.text(c, label, 13, Ui.semibold, Ui.text2());
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.setMargins(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(6));
            parent.addView(lb, llp);
        }
        android.widget.EditText et = new android.widget.EditText(c);
        et.setHint(hint);
        if (initial != null) et.setText(initial);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setIncludeFontPadding(false);
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15.5f);
        et.setTextColor(Ui.text());
        et.setHintTextColor(Ui.text3());
        et.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        et.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        GradientDrawable bg = Ui.round(Ui.dark ? 0xFF28282E : 0xFFF2F3F7, 16);
        bg.setStroke(Ui.dp(1), Ui.separator());
        et.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52));
        lp.bottomMargin = Ui.dp(6);
        parent.addView(et, lp);
        return et;
    }

    /** Spacious bottom sheet with primary + cancel buttons. */
    static void showFormSheet(MainActivity m, String title, LinearLayout formBody, String okLabel, Runnable onOk) {
        LinearLayout wrap = new LinearLayout(m);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(14));
        wrap.addView(formBody, MW);

        LinearLayout btns = new LinearLayout(m);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(14);

        TextView cancel = Ui.text(m, m.getString(R.string.cancel), 15, Ui.semibold, Ui.text());
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        cancel.setBackground(Ui.round(Ui.chip(), 100));
        cancel.setOnClickListener(v -> GlassMenu.dismissAny(m.root));
        Ui.press(cancel, 0.96f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = Ui.dp(10);
        btns.addView(cancel, clp);

        TextView ok = Ui.text(m, okLabel, 15, Ui.semibold, 0xFFFFFFFF);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        ok.setBackground(Ui.round(Ui.ACCENT, 100));
        ok.setOnClickListener(v -> {
            GlassMenu.dismissAny(m.root);
            if (onOk != null) onOk.run();
        });
        Ui.press(ok, 0.96f);
        btns.addView(ok, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wrap.addView(btns, blp);
        RectF anchor = new RectF(Ui.screenW / 2f - Ui.dpf(40), Ui.screenH - Ui.dpf(80), Ui.screenW / 2f + Ui.dpf(40), Ui.screenH - Ui.dpf(40));
        GlassMenu.customSheet(m, anchor, title, wrap, null);
    }

    interface PickCb { void onPicked(int index); }

    /** Unified single-choice glass sheet replacing stock AlertDialog across all settings & pickers. */
    static void pickSheet(MainActivity m, View anchorView, String title, String[] labels, int selectedIdx, PickCb cb) {
        RectF anchor;
        if (anchorView != null && anchorView.getWidth() > 0) {
            float[] o = Glass.abs(anchorView);
            anchor = new RectF(o[0], o[1], o[0] + anchorView.getWidth(), o[1] + anchorView.getHeight());
        } else {
            anchor = new RectF(Ui.screenW / 2f - Ui.dpf(40), Ui.screenH - Ui.dpf(80), Ui.screenW / 2f + Ui.dpf(40), Ui.screenH - Ui.dpf(40));
        }
        java.util.List<GlassMenu.Entry> es = new java.util.ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            GlassMenu.Entry e = new GlassMenu.Entry(labels[i], 0, i == selectedIdx, () -> {
                if (cb != null) cb.onPicked(idx);
            });
            e.checkable = true;
            es.add(e);
        }
        GlassMenu.sheet(m, anchor, title, es, null);
    }
}
