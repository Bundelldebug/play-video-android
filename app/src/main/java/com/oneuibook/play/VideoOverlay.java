package com.oneuibook.play;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen cinema video player overlay with floating glass chrome:
 * Gestures (brightness, volume, seek, double-tap, long-press 2x, pinch zoom),
 * 7 aspect ratios, dual subtitles + sync + style + dialogue explorer,
 * AI subtitle generation HUD, speed 0.25x..4.0x, 10-band EQ + volume boost,
 * A-B repeat, bookmarks, sleep timer, screenshot, PiP, background audio, screen lock.
 */
final class VideoOverlay extends FrameLayout implements TextureView.SurfaceTextureListener {
    private final MainActivity m;
    private final TextureView texture;
    private final Subtitle.SubtitleView subView;
    private final FrameLayout chrome;
    private final LinearLayout topBar, bottomBar, toolsRow;
    private final View aiTopBtn, pipTopBtn;
    private final TextView titleView, timeCur, timeDur, speedBadge, hudText, temp2xBadge;
    private final Icons.IconView playBtn, repeatBtn;
    private final View unlockBtn;
    private final SeekView seekBar;
    private final FrameLayout aiHud;
    private final TextView aiStage;
    private final View aiBarFill;
    private AtomicBoolean aiCancel;
    private final boolean glassDeck;
    private int orientLockMode = 0; // 0=auto(sensor), 1=landscape, 2=portrait

    private final Ui.Spring openP = new Ui.Spring(360f, 0.84f, 0.002f);
    private boolean open;
    private boolean chromeVisible = true;
    private boolean locked;
    private boolean inPip;

    // Video transform (aspect + pinch zoom)
    private float zoom = 1f;
    private float panX = 0f, panY = 0f;
    private final ScaleGestureDetector scaleDet;

    // Gesture state
    private static final int G_NONE = 0, G_BRIGHT = 1, G_VOL = 2, G_SEEK = 3;
    private int gesture = G_NONE;
    private float downX, downY, lastTapX;
    private long lastTapTime;
    private float startBright, startVol;
    private long seekStartPos, seekTargetPos;
    private boolean longPress2x;
    private boolean moved;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable hideChromeRun = () -> setChromeVisible(false);
    private final Runnable hideHudRun = this::hideHud;
    private final Runnable longPressRun = this::triggerLongPress2x;

    private void hideHud() { hudText.animate().alpha(0f).setDuration(240).start(); }
    private void triggerLongPress2x() {
        if (!moved && !locked && VideoPlayer.playing) {
            longPress2x = true;
            VideoPlayer.applyTempSpeed(2.0f);
            temp2xBadge.setVisibility(VISIBLE);
            temp2xBadge.setAlpha(0f);
            temp2xBadge.animate().alpha(1f).setDuration(150).start();
        }
    }
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            updatePlaybackUi();
            h.postDelayed(this, 100);
        }
    };

    VideoOverlay(MainActivity activity) {
        super(activity);
        this.m = activity;
        setBackgroundColor(0xFF000000);
        setVisibility(GONE);
        setClickable(true);

        // 1. Video TextureView
        texture = new TextureView(m);
        texture.setSurfaceTextureListener(this);
        addView(texture, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // 2. Subtitle overlay
        subView = new Subtitle.SubtitleView(m);
        addView(subView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Controls chrome (A style = floating glass capsules; G style = edge-to-edge modern deck)
        glassDeck = !Ui.gStyle;
        chrome = new FrameLayout(m);
        addView(chrome, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Subtle edge scrims for G style so video stays crisp without boxy slabs
        if (!glassDeck) {
            View topScrim = new View(m);
            topScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xB8000000, 0x00000000}));
            chrome.addView(topScrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(120), Gravity.TOP));

            View botScrim = new View(m);
            botScrim.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xD9000000, 0x66000000, 0x00000000}));
            chrome.addView(botScrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(230), Gravity.BOTTOM));
        }

        // Top controls bar
        topBar = new LinearLayout(m);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        if (glassDeck) {
            // A style: detached left capsule + floating title + detached right capsule
            LinearLayout leftCap = new LinearLayout(m);
            leftCap.setOrientation(LinearLayout.HORIZONTAL);
            leftCap.setGravity(Gravity.CENTER_VERTICAL);
            leftCap.setBackground(glassCapsule(100));
            leftCap.setPadding(Ui.dp(4), Ui.dp(3), Ui.dp(4), Ui.dp(3));
            leftCap.addView(iconBtn(Icons.BACK, v -> close()));
            pipTopBtn = iconBtn(Icons.PIP, v -> m.enterPip());
            leftCap.addView(pipTopBtn);
            topBar.addView(leftCap);

            titleView = Ui.text(m, "", 15.5f, Ui.semibold, 0xFFFFFFFF);
            titleView.setShadowLayer(Ui.dpf(6), 0, Ui.dpf(1.5f), 0xAA000000);
            titleView.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
            topBar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout rightCap = new LinearLayout(m);
            rightCap.setOrientation(LinearLayout.HORIZONTAL);
            rightCap.setGravity(Gravity.CENTER_VERTICAL);
            rightCap.setBackground(glassCapsule(100));
            rightCap.setPadding(Ui.dp(4), Ui.dp(3), Ui.dp(4), Ui.dp(3));
            aiTopBtn = iconBtn(Icons.AI, v -> startAiSubtitle());
            rightCap.addView(aiTopBtn);
            rightCap.addView(iconBtn(Icons.ROTATE, v -> toggleOrientation()));
            rightCap.addView(iconBtn(Icons.MORE, this::showMoreMenu));
            topBar.addView(rightCap);
        } else {
            topBar.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            topBar.addView(iconBtn(Icons.BACK, v -> close()));
            titleView = Ui.text(m, "", 16.5f, Ui.semibold, 0xFFFFFFFF);
            titleView.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
            topBar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            aiTopBtn = iconBtn(Icons.AI, v -> startAiSubtitle());
            pipTopBtn = iconBtn(Icons.PIP, v -> m.enterPip());
            topBar.addView(aiTopBtn);
            topBar.addView(pipTopBtn);
            topBar.addView(iconBtn(Icons.ROTATE, v -> toggleOrientation()));
            topBar.addView(iconBtn(Icons.MORE, this::showMoreMenu));
        }
        LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tlp.setMargins(Ui.dp(14), Ui.statusInset + Ui.dp(12), Ui.dp(14), 0);
        chrome.addView(topBar, tlp);

        // Center floating transport orbs for A style
        if (glassDeck) {
            LinearLayout centerOrbs = new LinearLayout(m);
            centerOrbs.setOrientation(LinearLayout.HORIZONTAL);
            centerOrbs.setGravity(Gravity.CENTER);

            Icons.IconView rwOrb = new Icons.IconView(m, Icons.REWIND, 24, 0xFFFFFFFF);
            rwOrb.setBackground(glassCapsule(100));
            rwOrb.setOnClickListener(v -> { seekDelta(-doubleTapMs()); pokeChrome(); });
            Ui.press(rwOrb, 0.88f);
            centerOrbs.addView(rwOrb, new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(50)));

            playBtn = new Icons.IconView(m, Icons.PAUSE, 30, 0xFFFFFFFF);
            playBtn.setBackground(glassCapsule(100));
            playBtn.setOnClickListener(v -> { VideoPlayer.toggle(); pokeChrome(); });
            Ui.press(playBtn, 0.88f);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(68), Ui.dp(68));
            plp.setMargins(Ui.dp(26), 0, Ui.dp(26), 0);
            centerOrbs.addView(playBtn, plp);

            Icons.IconView fwOrb = new Icons.IconView(m, Icons.FORWARD, 24, 0xFFFFFFFF);
            fwOrb.setBackground(glassCapsule(100));
            fwOrb.setOnClickListener(v -> { seekDelta(doubleTapMs()); pokeChrome(); });
            Ui.press(fwOrb, 0.88f);
            centerOrbs.addView(fwOrb, new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(50)));

            chrome.addView(centerOrbs, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        } else {
            playBtn = new Icons.IconView(m, Icons.PAUSE, 28, 0xFFFFFFFF);
        }

        // Bottom control deck
        bottomBar = new LinearLayout(m);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        if (glassDeck) {
            bottomBar.setBackground(glassCapsule(26));
            bottomBar.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        } else {
            bottomBar.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        }

        // Seek row
        LinearLayout seekRow = new LinearLayout(m);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        timeCur = Ui.text(m, "0:00", 12, Ui.semibold, 0xEEFFFFFF);
        timeDur = Ui.text(m, "0:00", 12, Ui.medium, 0xB3FFFFFF);
        seekBar = new SeekView(m);
        seekRow.addView(timeCur, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, Ui.dp(32), 1f);
        sblp.setMargins(Ui.dp(10), 0, Ui.dp(10), 0);
        seekRow.addView(seekBar, sblp);
        seekRow.addView(timeDur, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomBar.addView(seekRow, Widgets.MW);

        // Transport row inside bottom deck for G style
        if (!glassDeck) {
            LinearLayout transport = new LinearLayout(m);
            transport.setOrientation(LinearLayout.HORIZONTAL);
            transport.setGravity(Gravity.CENTER);
            transport.setPadding(0, Ui.dp(4), 0, Ui.dp(6));
            transport.addView(iconBtn(Icons.PREV, v -> { VideoPlayer.prev(); pokeChrome(); }));
            transport.addView(iconBtn(Icons.REWIND, v -> { seekDelta(-doubleTapMs()); pokeChrome(); }));
            playBtn.setBackground(Ui.round(Ui.ACCENT, 100));
            playBtn.setOnClickListener(v -> { VideoPlayer.toggle(); pokeChrome(); });
            Ui.press(playBtn, 0.9f);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(54), Ui.dp(54));
            plp.setMargins(Ui.dp(14), 0, Ui.dp(14), 0);
            transport.addView(playBtn, plp);
            transport.addView(iconBtn(Icons.FORWARD, v -> { seekDelta(doubleTapMs()); pokeChrome(); }));
            transport.addView(iconBtn(Icons.NEXT, v -> { VideoPlayer.next(); pokeChrome(); }));
            bottomBar.addView(transport, Widgets.MW);
        }

        // Evenly distributed feature row: Lock, Speed, Subtitle, Audio/EQ, Aspect, Repeat (never clips on narrow bar phones)
        toolsRow = new LinearLayout(m);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        toolsRow.setGravity(Gravity.CENTER_VERTICAL);
        toolsRow.setPadding(0, Ui.dp(glassDeck ? 6 : 4), 0, 0);
        View lockBtn = toolSlot(Icons.LOCK, null, v -> setLocked(true));
        lockBtn.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(36)));
        toolsRow.addView(lockBtn);
        LinearLayout spSlot = new LinearLayout(m);
        spSlot.setGravity(Gravity.CENTER);
        spSlot.setBackground(Widgets.ripple());
        speedBadge = Ui.text(m, "1.0x", 12.5f, Ui.bold, 0xFFFFFFFF);
        speedBadge.setIncludeFontPadding(false);
        spSlot.addView(speedBadge);
        spSlot.setOnClickListener(this::showSpeedSheet);
        Ui.press(spSlot, 0.90f);
        toolsRow.addView(spSlot, new LinearLayout.LayoutParams(0, Ui.dp(36), 1f));
        toolsRow.addView(toolSlot(Icons.SUBTITLE, "자막", this::showSubtitleSheet));
        toolsRow.addView(toolSlot(Icons.EQ, "오디오", this::showAudioMenu));
        toolsRow.addView(toolSlot(Icons.ASPECT, "비율", this::showAspectSheet));
        repeatBtn = new Icons.IconView(m, Icons.REPEAT, 20, 0xCCFFFFFF);
        repeatBtn.setBackground(Widgets.ripple());
        repeatBtn.setOnClickListener(v -> cycleRepeat());
        Ui.press(repeatBtn, 0.88f);
        toolsRow.addView(repeatBtn, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(36)));
        bottomBar.addView(toolsRow, Widgets.MW);

        bottomBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (chromeVisible) {
                subView.extraBottomPx = (b - t) + Ui.navInset + Ui.dpf(20);
                subView.invalidate();
            }
        });

        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.setMargins(Ui.dp(14), 0, Ui.dp(14), Ui.navInset + Ui.dp(14));
        chrome.addView(bottomBar, blp);

        // Unlock pill (visible only when locked)
        Icons.IconView ul = new Icons.IconView(m, Icons.LOCK, 22, 0xFFFFFFFF);
        ul.setBackground(Ui.round(0xAA141418, 100));
        ul.setOnClickListener(v -> setLocked(false));
        Ui.press(ul, 0.92f);
        unlockBtn = ul;
        unlockBtn.setVisibility(GONE);
        LayoutParams ulp = new LayoutParams(Ui.dp(48), Ui.dp(48), Gravity.TOP | Gravity.START);
        ulp.setMargins(Ui.dp(18), Ui.statusInset + Ui.dp(16), 0, 0);
        addView(unlockBtn, ulp);

        // Center gesture HUD
        hudText = Ui.text(m, "", 17, Ui.bold, 0xFFFFFFFF);
        hudText.setGravity(Gravity.CENTER);
        hudText.setPadding(Ui.dp(22), Ui.dp(12), Ui.dp(22), Ui.dp(12));
        hudText.setBackground(Ui.round(0xBF141418, 100));
        hudText.setAlpha(0f);
        addView(hudText, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // 2x long-press pill
        temp2xBadge = Ui.text(m, m.getString(R.string.temp_2x), 13.5f, Ui.bold, 0xFFFFFFFF);
        temp2xBadge.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
        temp2xBadge.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.92f), 100));
        temp2xBadge.setVisibility(GONE);
        LayoutParams t2lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        t2lp.topMargin = Ui.statusInset + Ui.dp(68);
        addView(temp2xBadge, t2lp);

        // AI subtitle progress HUD
        aiHud = new FrameLayout(m);
        aiHud.setBackground(Ui.round(0xD916161C, 22));
        aiHud.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        LinearLayout aiCol = new LinearLayout(m);
        aiCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout aiTop = new LinearLayout(m);
        aiTop.setGravity(Gravity.CENTER_VERTICAL);
        aiTop.addView(new Icons.IconView(m, Icons.AI, 18, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        aiStage = Ui.text(m, "", 14, Ui.semibold, 0xFFFFFFFF);
        aiStage.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        aiTop.addView(aiStage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView aiCancelBtn = Ui.text(m, m.getString(R.string.cancel), 13, Ui.bold, 0xFFFF6B6B);
        aiCancelBtn.setOnClickListener(v -> cancelAiSubtitle());
        aiTop.addView(aiCancelBtn);
        aiCol.addView(aiTop, Widgets.MW);
        FrameLayout barBg = new FrameLayout(m);
        barBg.setBackground(Ui.round(0x33FFFFFF, 4));
        aiBarFill = new View(m);
        aiBarFill.setBackground(Ui.round(Ui.ACCENT, 4));
        barBg.addView(aiBarFill, new FrameLayout.LayoutParams(1, Ui.dp(5)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(5));
        barLp.topMargin = Ui.dp(10);
        aiCol.addView(barBg, barLp);
        aiHud.addView(aiCol, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        aiHud.setVisibility(GONE);
        LayoutParams ahlp = new LayoutParams(Math.min(Ui.dp(360), Ui.screenW - Ui.dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ahlp.topMargin = Ui.statusInset + Ui.dp(72);
        addView(aiHud, ahlp);

        scaleDet = new ScaleGestureDetector(m, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (locked) return false;
                zoom = Ui.clamp(zoom * d.getScaleFactor(), 0.5f, 4.0f);
                applyVideoTransform();
                showHud(Math.round(zoom * 100) + "%");
                return true;
            }
        });

        openP.onUpdate = () -> {
            float v = Ui.clamp(openP.value, 0f, 1f);
            setAlpha(v);
            setTranslationY((1f - v) * Ui.dpf(36));
            setScaleX(Ui.lerp(0.95f, 1f, v));
            setScaleY(Ui.lerp(0.95f, 1f, v));
        };
        openP.onEnd = () -> {
            if (!open) setVisibility(GONE);
        };

        VideoPlayer.listeners.add(() -> {
            if (open) updatePlaybackUi();
            applyVideoTransform();
        });
    }

    /** Dark translucent glass capsule with luminous rim and subtle top sheen. */
    private android.graphics.drawable.GradientDrawable glassCapsule(int radiusDp) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8A222630, 0x7A14161D});
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

    private View toolSlot(int icon, String label, OnClickListener click) {
        LinearLayout r = new LinearLayout(m);
        r.setGravity(Gravity.CENTER);
        r.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(6));
        r.setBackground(Widgets.ripple());
        r.addView(new Icons.IconView(m, icon, 17, 0xF0FFFFFF), new LinearLayout.LayoutParams(Ui.dp(19), Ui.dp(19)));
        if (label != null) {
            TextView t = Ui.text(m, label, 11.5f, Ui.semibold, 0xF0FFFFFF);
            t.setPadding(Ui.dp(3), 0, 0, 0);
            r.addView(t);
        }
        r.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(36), 1f));
        r.setOnClickListener(click);
        Ui.press(r, 0.90f);
        return r;
    }

    private TextView toolTextSlot(String text, OnClickListener click) {
        TextView t = Ui.text(m, text, 12.5f, Ui.bold, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(6));
        t.setBackground(Widgets.ripple());
        t.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(36), 1f));
        t.setOnClickListener(click);
        Ui.press(t, 0.90f);
        return t;
    }

    private void adaptLayoutForSize(int w, int h0) {
        if (w <= 0 || h0 <= 0) return;
        boolean narrow = w < Ui.dp(480);
        int side = narrow ? Ui.dp(12) : Ui.dp(24);
        aiTopBtn.setVisibility(narrow ? GONE : VISIBLE);
        pipTopBtn.setVisibility(narrow ? GONE : VISIBLE);

        LayoutParams tlp = (LayoutParams) topBar.getLayoutParams();
        tlp.setMargins(side, Ui.statusInset + Ui.dp(10), side, 0);
        topBar.setLayoutParams(tlp);

        LayoutParams blp = (LayoutParams) bottomBar.getLayoutParams();
        blp.setMargins(side, 0, side, Ui.navInset + Ui.dp(12));
        bottomBar.setLayoutParams(blp);
    }

    boolean isOpen() { return open; }

    void open() { open(true); }

    void open(boolean animate) {
        open = true;
        zoom = 1f; panX = 0f; panY = 0f;
        setVisibility(VISIBLE);
        if (animate) openP.to(1f); else openP.snap(1f);
        m.setFullscreenImmersive(true);
        applyInitialOrientation();
        adaptLayoutForSize(getWidth() > 0 ? getWidth() : Ui.screenW, getHeight() > 0 ? getHeight() : Ui.screenH);
        setChromeVisible(true);
        updatePlaybackUi();
        applyVideoTransform();
        h.removeCallbacks(uiTick);
        h.post(uiTick);
    }

    private void applyInitialOrientation() {
        String mode = MainActivity.prefs(m).getString("auto_rotate", "sensor");
        if ("portrait".equals(mode)) {
            orientLockMode = 2;
            m.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else if ("landscape".equals(mode)) {
            orientLockMode = 1;
            m.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else if ("video".equals(mode)) {
            boolean wide = VideoPlayer.videoW >= VideoPlayer.videoH;
            orientLockMode = wide ? 1 : 2;
            m.setRequestedOrientation(wide ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else {
            orientLockMode = 0;
            m.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

    void close() {
        if (!open) return;
        open = false;
        h.removeCallbacks(uiTick);
        h.removeCallbacks(hideChromeRun);
        if (!VideoPlayer.backgroundAudio) VideoPlayer.pause();
        m.setFullscreenImmersive(false);
        m.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        openP.to(0f);
    }

    void setPipMode(boolean pip) {
        inPip = pip;
        chrome.setVisibility(pip ? GONE : VISIBLE);
        unlockBtn.setVisibility(GONE);
        aiHud.setVisibility(GONE);
        applyVideoTransform();
    }

    void onInsetsChanged() {
        adaptLayoutForSize(getWidth() > 0 ? getWidth() : Ui.screenW, getHeight() > 0 ? getHeight() : Ui.screenH);
        applyVideoTransform();
    }

    private void setChromeVisible(boolean vis) {
        if (locked || inPip) vis = false;
        chromeVisible = vis;
        h.removeCallbacks(hideChromeRun);
        int bh = bottomBar.getHeight() > 0 ? bottomBar.getHeight() : Ui.dp(glassDeck ? 110 : 156);
        subView.extraBottomPx = vis ? (bh + Ui.navInset + Ui.dpf(20)) : 0f;
        subView.invalidate();
        chrome.animate().alpha(vis ? 1f : 0f).setDuration(220).withEndAction(() -> {
            if (!chromeVisible) chrome.setVisibility(GONE);
        }).start();
        if (vis) {
            chrome.setVisibility(VISIBLE);
            if (VideoPlayer.playing) h.postDelayed(hideChromeRun, 4000);
        }
    }

    private void pokeChrome() {
        if (chromeVisible) {
            h.removeCallbacks(hideChromeRun);
            if (VideoPlayer.playing) h.postDelayed(hideChromeRun, 4000);
        }
    }

    private void setLocked(boolean l) {
        locked = l;
        unlockBtn.setVisibility(l ? VISIBLE : GONE);
        setChromeVisible(!l);
        showHud(m.getString(l ? R.string.lock_screen : R.string.unlock_screen));
    }

    private void toggleOrientation() {
        boolean landscape = getWidth() > getHeight();
        if (orientLockMode == 0) {
            orientLockMode = landscape ? 2 : 1;
        } else if (orientLockMode == 1) {
            orientLockMode = 2;
        } else {
            orientLockMode = 0;
        }
        if (orientLockMode == 0) {
            m.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            showHud(m.getString(R.string.auto_rotate_sensor));
        } else if (orientLockMode == 1) {
            m.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            showHud(m.getString(R.string.auto_rotate_landscape));
        } else {
            m.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            showHud(m.getString(R.string.auto_rotate_portrait));
        }
        pokeChrome();
    }

    private long doubleTapMs() {
        return MainActivity.prefs(m).getInt("double_tap_sec", 10) * 1000L;
    }

    private void seekDelta(long deltaMs) {
        VideoPlayer.seekBy(deltaMs);
        String sign = deltaMs >= 0 ? "+" : "";
        showHud(sign + (deltaMs / 1000) + "초");
    }

    private void showHud(String text) {
        hudText.setText(text);
        hudText.animate().cancel();
        hudText.setAlpha(1f);
        h.removeCallbacks(hideHudRun);
        h.postDelayed(hideHudRun, 900);
    }

    private void updatePlaybackUi() {
        VideoLibrary.Video v = VideoPlayer.current;
        if (v != null) titleView.setText(v.title);
        long pos = VideoPlayer.positionMs();
        long dur = VideoPlayer.durationMs;
        timeCur.setText(Ui.time(pos));
        timeDur.setText(Ui.time(dur));
        playBtn.set(VideoPlayer.playing ? Icons.PAUSE : Icons.PLAY, 0xFFFFFFFF);
        speedBadge.setText(String.format(Locale.US, "%.2gx", VideoPlayer.speed));
        int repIcon = VideoPlayer.repeatMode == VideoPlayer.REPEAT_ONE ? Icons.REPEAT_ONE : Icons.REPEAT;
        repeatBtn.set(repIcon, VideoPlayer.repeatMode == VideoPlayer.REPEAT_OFF ? 0x99FFFFFF : Ui.ACCENT);
        seekBar.update(pos, dur);

        // Update subtitles
        if (VideoPlayer.subEnabled) {
            String p = VideoPlayer.primarySub != null ? VideoPlayer.primarySub.textAt(pos, VideoPlayer.subOffsetMs) : null;
            String s = VideoPlayer.secondarySub != null ? VideoPlayer.secondarySub.textAt(pos, VideoPlayer.subOffsetMs) : null;
            subView.setTexts(p, s);
        } else {
            subView.setTexts(null, null);
        }
    }

    // ---- Aspect ratio & Pinch zoom matrix ----
    void applyVideoTransform() {
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        int vidW = VideoPlayer.videoW > 0 ? VideoPlayer.videoW : 16;
        int vidH = VideoPlayer.videoH > 0 ? VideoPlayer.videoH : 9;
        float videoRatio = vidW / (float) vidH;
        float viewRatio = vw / (float) vh;

        float sx = 1f, sy = 1f;
        switch (VideoPlayer.aspectMode) {
            case VideoPlayer.ASPECT_FILL:
                if (videoRatio > viewRatio) sx = videoRatio / viewRatio; else sy = viewRatio / videoRatio;
                break;
            case VideoPlayer.ASPECT_STRETCH:
                sx = 1f; sy = 1f;
                break;
            case VideoPlayer.ASPECT_16_9:
                float r16 = 16f / 9f;
                if (r16 > viewRatio) sy = viewRatio / r16; else sx = r16 / viewRatio;
                break;
            case VideoPlayer.ASPECT_4_3:
                float r4 = 4f / 3f;
                if (r4 > viewRatio) sy = viewRatio / r4; else sx = r4 / viewRatio;
                break;
            case VideoPlayer.ASPECT_21_9:
                float r21 = 21f / 9f;
                if (r21 > viewRatio) sy = viewRatio / r21; else sx = r21 / viewRatio;
                break;
            case VideoPlayer.ASPECT_1_1:
                if (1f > viewRatio) sy = viewRatio; else sx = 1f / viewRatio;
                break;
            default: // ASPECT_FIT
                if (videoRatio > viewRatio) sy = viewRatio / videoRatio; else sx = videoRatio / viewRatio;
                break;
        }
        Matrix mat = new Matrix();
        mat.setScale(sx * zoom, sy * zoom, vw / 2f, vh / 2f);
        mat.postTranslate(panX, panY);
        texture.setTransform(mat);
    }

    @Override protected void onSizeChanged(int w, int h0, int ow, int oh) {
        super.onSizeChanged(w, h0, ow, oh);
        adaptLayoutForSize(w, h0);
        applyVideoTransform();
    }

    // ---- TextureView.SurfaceTextureListener ----
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        VideoPlayer.setSurface(new Surface(st));
        applyVideoTransform();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { applyVideoTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { VideoPlayer.setSurface(null); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ---- Touch gestures ----
    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDet.onTouchEvent(e);
        if (scaleDet.isInProgress()) {
            h.removeCallbacks(longPressRun);
            return true;
        }
        if (locked) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                unlockBtn.setVisibility(VISIBLE);
                unlockBtn.animate().alpha(1f).setDuration(150).start();
            }
            return true;
        }
        float x = e.getX(), y = e.getY();
        int W = Math.max(1, getWidth()), H = Math.max(1, getHeight());

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y;
                moved = false;
                gesture = G_NONE;
                longPress2x = false;
                h.postDelayed(longPressRun, 450);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (!moved && (Math.abs(dx) > Ui.dpf(14) || Math.abs(dy) > Ui.dpf(14))) {
                    moved = true;
                    h.removeCallbacks(longPressRun);
                    if (longPress2x) break;
                    if (Math.abs(dx) > Math.abs(dy)) {
                        gesture = G_SEEK;
                        seekStartPos = VideoPlayer.positionMs();
                    } else if (downX < W * 0.5f) {
                        if (MainActivity.prefs(m).getBoolean("gesture_brightness", true)) {
                            gesture = G_BRIGHT;
                            float curB = m.getWindow().getAttributes().screenBrightness;
                            startBright = curB < 0 ? 0.5f : curB;
                        }
                    } else {
                        if (MainActivity.prefs(m).getBoolean("gesture_volume", true)) {
                            gesture = G_VOL;
                            AudioManager am = (AudioManager) m.getSystemService(Context.AUDIO_SERVICE);
                            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                            startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
                        }
                    }
                }
                if (gesture == G_BRIGHT) {
                    float nb = Ui.clamp(startBright - dy / (H * 0.65f), 0.02f, 1f);
                    WindowManager.LayoutParams lp = m.getWindow().getAttributes();
                    lp.screenBrightness = nb;
                    m.getWindow().setAttributes(lp);
                    showHud(m.getString(R.string.brightness) + " " + Math.round(nb * 100) + "%");
                } else if (gesture == G_VOL) {
                    float nv = Ui.clamp(startVol - dy / (H * 0.65f), 0f, 1f);
                    AudioManager am = (AudioManager) m.getSystemService(Context.AUDIO_SERVICE);
                    int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(nv * max), 0);
                    showHud(m.getString(R.string.volume) + " " + Math.round(nv * 100) + "%");
                } else if (gesture == G_SEEK) {
                    long delta = (long) ((dx / W) * 90_000L);
                    seekTargetPos = Math.max(0, VideoPlayer.durationMs > 0
                            ? Math.min(VideoPlayer.durationMs, seekStartPos + delta) : seekStartPos + delta);
                    String sign = delta >= 0 ? "+" : "";
                    showHud(Ui.time(seekTargetPos) + " (" + sign + (delta / 1000) + "초)");
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPressRun);
                if (longPress2x) {
                    longPress2x = false;
                    VideoPlayer.applyTempSpeed(VideoPlayer.speed);
                    temp2xBadge.animate().alpha(0f).setDuration(150).withEndAction(() -> temp2xBadge.setVisibility(GONE)).start();
                    return true;
                }
                if (gesture == G_SEEK) {
                    VideoPlayer.seekTo(seekTargetPos);
                } else if (!moved) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapTime < 280 && Math.abs(x - lastTapX) < Ui.dpf(90)) {
                        lastTapTime = 0;
                        if (x < W * 0.4f) seekDelta(-doubleTapMs());
                        else if (x > W * 0.6f) seekDelta(doubleTapMs());
                        else VideoPlayer.toggle();
                    } else {
                        lastTapTime = now;
                        lastTapX = x;
                        setChromeVisible(!chromeVisible);
                    }
                }
                gesture = G_NONE;
                return true;
        }
        return true;
    }

    // ---- Menus & Sheets ----
    private RectF anchorOf(View v) {
        float[] o = Glass.abs(v);
        return new RectF(o[0], o[1], o[0] + v.getWidth(), o[1] + v.getHeight());
    }

    private void showSpeedSheet(View anchor) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(18), Ui.dp(6), Ui.dp(18), Ui.dp(12));

        TextView val = Ui.text(m, String.format(Locale.US, "%.2fx", VideoPlayer.speed), 22, Ui.bold, 0xFFFFFFFF);
        val.setGravity(Gravity.CENTER);
        box.addView(val, Widgets.MW);

        Widgets.Slider sl = new Widgets.Slider(m, (VideoPlayer.speed - 0.25f) / 3.75f);
        sl.darkSurface = true;
        sl.change = (f, u) -> {
            float s = Math.round((0.25f + f * 3.75f) * 20f) / 20f;
            VideoPlayer.setSpeed(s);
            val.setText(String.format(Locale.US, "%.2fx", s));
        };
        box.addView(sl);

        float[] presets = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
        LinearLayout row = new LinearLayout(m);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.dp(8), 0, 0);
        for (float sp : presets) {
            TextView c = Ui.text(m, sp + "x", 12.5f, Ui.semibold, 0xFFFFFFFF);
            c.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
            c.setBackground(Ui.round(Math.abs(VideoPlayer.speed - sp) < 0.02f ? Ui.ACCENT : 0x26FFFFFF, 100));
            c.setOnClickListener(v -> {
                VideoPlayer.setSpeed(sp);
                sl.setValue((sp - 0.25f) / 3.75f);
                val.setText(String.format(Locale.US, "%.2fx", sp));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
            row.addView(c, lp);
        }
        box.addView(row, Widgets.MW);
        GlassMenu.customSheet(m, anchorOf(anchor), m.getString(R.string.speed), box, anchor);
    }

    private void showAspectSheet(View anchor) {
        int[] modes = {VideoPlayer.ASPECT_FIT, VideoPlayer.ASPECT_FILL, VideoPlayer.ASPECT_STRETCH,
                VideoPlayer.ASPECT_16_9, VideoPlayer.ASPECT_4_3, VideoPlayer.ASPECT_21_9, VideoPlayer.ASPECT_1_1};
        int[] labels = {R.string.aspect_fit, R.string.aspect_fill, R.string.aspect_stretch,
                R.string.aspect_16_9, R.string.aspect_4_3, R.string.aspect_21_9, R.string.aspect_1_1};
        List<GlassMenu.Entry> es = new ArrayList<>();
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            GlassMenu.Entry e = new GlassMenu.Entry(m.getString(labels[i]), 0, VideoPlayer.aspectMode == mode, () -> {
                VideoPlayer.aspectMode = mode;
                zoom = 1f; panX = 0f; panY = 0f;
                applyVideoTransform();
                showHud(m.getString(labels[mode]));
            });
            e.checkable = true;
            es.add(e);
        }
        GlassMenu.sheet(m, anchorOf(anchor), m.getString(R.string.aspect_ratio), es, anchor);
    }

    private void showSubtitleSheet(View anchor) {
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.sub_ai_generate), Icons.AI, false, this::startAiSubtitle));
        es.add(new GlassMenu.Entry(m.getString(R.string.sub_load_file), Icons.FOLDER, false, () -> m.pickSubtitleFile(false)));
        es.add(new GlassMenu.Entry(m.getString(R.string.sub_load_secondary), Icons.SUBTITLE, false, () -> m.pickSubtitleFile(true)));
        if (VideoPlayer.secondarySub != null) {
            es.add(new GlassMenu.Entry(m.getString(R.string.sub_clear_secondary), Icons.CLOSE, false, () -> VideoPlayer.secondarySub = null));
        }
        es.add(new GlassMenu.Entry(m.getString(R.string.sub_sync) + String.format(Locale.US, " (%+.1fs)", VideoPlayer.subOffsetMs / 1000f),
                Icons.CLOCK, false, () -> showSubSyncSheet(anchorOf(anchor))));
        es.add(new GlassMenu.Entry(m.getString(R.string.sub_style), Icons.APPEARANCE, false, () -> showSubStyleSheet(anchorOf(anchor))));
        if (VideoPlayer.primarySub != null && !VideoPlayer.primarySub.cues.isEmpty()) {
            es.add(new GlassMenu.Entry(m.getString(R.string.sub_explorer), Icons.LIST, false, () -> showSubExplorerSheet(anchorOf(anchor))));
        }
        GlassMenu.Entry toggle = new GlassMenu.Entry(m.getString(R.string.sub_off), 0, !VideoPlayer.subEnabled,
                () -> VideoPlayer.subEnabled = !VideoPlayer.subEnabled);
        toggle.checkable = true;
        es.add(toggle);
        GlassMenu.sheet(m, anchorOf(anchor), m.getString(R.string.subtitles), es, anchor);
    }

    private void showSubSyncSheet(RectF anchor) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(14));
        TextView val = Ui.text(m, String.format(Locale.US, "%+.1f 초", VideoPlayer.subOffsetMs / 1000f), 22, Ui.bold, 0xFFFFFFFF);
        val.setGravity(Gravity.CENTER);
        box.addView(val, Widgets.MW);

        LinearLayout row = new LinearLayout(m);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.dp(14), 0, Ui.dp(6));
        long[] deltas = {-2000, -500, -100, 100, 500, 2000};
        String[] labels = {"-2s", "-0.5s", "-0.1s", "+0.1s", "+0.5s", "+2s"};
        for (int i = 0; i < deltas.length; i++) {
            final long d = deltas[i];
            TextView b = Ui.text(m, labels[i], 13, Ui.semibold, 0xFFFFFFFF);
            b.setPadding(Ui.dp(10), Ui.dp(9), Ui.dp(10), Ui.dp(9));
            b.setBackground(Ui.round(0x26FFFFFF, 100));
            b.setOnClickListener(v -> {
                VideoPlayer.subOffsetMs += d;
                val.setText(String.format(Locale.US, "%+.1f 초", VideoPlayer.subOffsetMs / 1000f));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
            row.addView(b, lp);
        }
        box.addView(row, Widgets.MW);

        TextView reset = Ui.text(m, m.getString(R.string.reset), 13.5f, Ui.semibold, Ui.ACCENT);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, Ui.dp(10), 0, 0);
        reset.setOnClickListener(v -> { VideoPlayer.subOffsetMs = 0; val.setText("+0.0 초"); });
        box.addView(reset, Widgets.MW);

        GlassMenu.customSheet(m, anchor, m.getString(R.string.sub_sync), box, null);
    }

    private void showSubStyleSheet(RectF anchor) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(12));

        TextView szLbl = Ui.text(m, "글자 크기 · " + Math.round(Subtitle.textSizeSp) + "sp", 14, Ui.semibold, 0xFFFFFFFF);
        box.addView(szLbl);
        Widgets.Slider szSl = new Widgets.Slider(m, (Subtitle.textSizeSp - 12f) / 28f);
        szSl.darkSurface = true;
        szSl.change = (f, u) -> {
            Subtitle.textSizeSp = 12f + f * 28f;
            Subtitle.saveStyle(MainActivity.prefs(m));
            szLbl.setText("글자 크기 · " + Math.round(Subtitle.textSizeSp) + "sp");
            subView.invalidate();
        };
        box.addView(szSl);

        TextView posLbl = Ui.text(m, "하단 위치 · " + Math.round(Subtitle.bottomMarginDp) + "dp", 14, Ui.semibold, 0xFFFFFFFF);
        box.addView(posLbl);
        Widgets.Slider posSl = new Widgets.Slider(m, (Subtitle.bottomMarginDp - 8f) / 152f);
        posSl.darkSurface = true;
        posSl.change = (f, u) -> {
            Subtitle.bottomMarginDp = 8f + f * 152f;
            Subtitle.saveStyle(MainActivity.prefs(m));
            posLbl.setText("하단 위치 · " + Math.round(Subtitle.bottomMarginDp) + "dp");
            subView.invalidate();
        };
        box.addView(posSl);

        TextView bgLbl = Ui.text(m, "배경 투명도 · " + Math.round(Subtitle.bgAlpha * 100) + "%", 14, Ui.semibold, 0xFFFFFFFF);
        box.addView(bgLbl);
        Widgets.Slider bgSl = new Widgets.Slider(m, Subtitle.bgAlpha);
        bgSl.darkSurface = true;
        bgSl.change = (f, u) -> {
            Subtitle.bgAlpha = f;
            Subtitle.saveStyle(MainActivity.prefs(m));
            bgLbl.setText("배경 투명도 · " + Math.round(f * 100) + "%");
            subView.invalidate();
        };
        box.addView(bgSl);

        int[] colors = {0xFFFFFFFF, 0xFFFFE066, 0xFF7CE8FF, 0xFF8CFF9B};
        LinearLayout crow = new LinearLayout(m);
        crow.setPadding(0, Ui.dp(8), 0, 0);
        for (int col : colors) {
            View dot = new View(m);
            dot.setBackground(Ui.round(col, 100));
            dot.setOnClickListener(v -> {
                Subtitle.textColor = col;
                Subtitle.saveStyle(MainActivity.prefs(m));
                subView.invalidate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28));
            lp.rightMargin = Ui.dp(12);
            crow.addView(dot, lp);
        }
        box.addView(crow);
        GlassMenu.customSheet(m, anchor, m.getString(R.string.sub_style), box, null);
    }

    private void showSubExplorerSheet(RectF anchor) {
        Subtitle.Track track = VideoPlayer.primarySub;
        if (track == null) return;
        LinearLayout list = new LinearLayout(m);
        list.setOrientation(LinearLayout.VERTICAL);
        int limit = Math.min(250, track.cues.size());
        for (int i = 0; i < limit; i++) {
            Subtitle.Cue cue = track.cues.get(i);
            LinearLayout r = new LinearLayout(m);
            r.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10));
            r.setBackground(Widgets.ripple());
            TextView tm = Ui.text(m, Ui.time(cue.startMs), 12.5f, Ui.bold, Ui.ACCENT);
            r.addView(tm, new LinearLayout.LayoutParams(Ui.dp(62), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView tx = Ui.text(m, cue.text.replace('\n', ' '), 14, Ui.regular, 0xFFFFFFFF);
            tx.setSingleLine(false);
            tx.setMaxLines(2);
            r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.setOnClickListener(v -> {
                VideoPlayer.seekTo(cue.startMs + VideoPlayer.subOffsetMs);
                GlassMenu.dismissAny(m.root);
            });
            list.addView(r, Widgets.MW);
        }
        GlassMenu.customSheet(m, anchor, m.getString(R.string.sub_explorer) + " (" + track.cues.size() + ")", list, null);
    }

    private void showAudioMenu(View anchor) {
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.equalizer), Icons.EQ, false, () -> showEqSheet(anchorOf(anchor))));
        for (VideoPlayer.TrackItem tr : VideoPlayer.audioTracks) {
            GlassMenu.Entry e = new GlassMenu.Entry(tr.label, Icons.AUDIO, tr.index == VideoPlayer.selectedAudioTrack,
                    () -> VideoPlayer.selectAudioTrack(tr.index));
            e.checkable = true;
            es.add(e);
        }
        GlassMenu.sheet(m, anchorOf(anchor), m.getString(R.string.audio_track), es, anchor);
    }

    void showEqSheet(RectF anchor) {
        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(18), Ui.dp(6), Ui.dp(18), Ui.dp(14));
        int fg = open ? 0xFFFFFFFF : Ui.text();

        TextView boostLbl = Ui.text(m, m.getString(R.string.volume_boost) + " · +" + AudioFx.boostPct + "%", 14.5f, Ui.semibold, fg);
        box.addView(boostLbl);
        Widgets.Slider boostSl = new Widgets.Slider(m, AudioFx.boostPct / 100f);
        boostSl.darkSurface = open;
        boostSl.change = (f, u) -> {
            int pct = Math.round(f * 100);
            AudioFx.setBoost(m, pct);
            boostLbl.setText(m.getString(R.string.volume_boost) + " · +" + pct + "%");
        };
        box.addView(boostSl);

        // Preset chips
        LinearLayout presets = Widgets.rail(box, 6);
        for (int i = 0; i < AudioFx.PRESET_NAMES.length - 1; i++) {
            final int idx = i;
            TextView ch = Ui.text(m, AudioFx.PRESET_NAMES[i], 12.5f, Ui.semibold, fg);
            ch.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
            ch.setBackground(Ui.round(AudioFx.presetIndex == i ? Ui.ACCENT : (open ? 0x26FFFFFF : Ui.chip()), 100));
            ch.setOnClickListener(v -> {
                AudioFx.applyPreset(m, idx);
                Toast.makeText(m, AudioFx.PRESET_NAMES[idx] + " 적용", Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(6);
            presets.addView(ch, lp);
        }

        // 5 key bands summary sliders (62, 250, 1K, 4K, 16K) mapped to 10-band EQ
        int[] showIdx = {1, 3, 5, 7, 9};
        for (int bIdx : showIdx) {
            LinearLayout r = new LinearLayout(m);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = Ui.text(m, AudioFx.LABELS[bIdx] + "Hz", 12, Ui.medium, fg);
            r.addView(lbl, new LinearLayout.LayoutParams(Ui.dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
            Widgets.Slider s = new Widgets.Slider(m, (AudioFx.bands[bIdx] + 12f) / 24f);
            s.darkSurface = open;
            s.change = (f, u) -> AudioFx.setBand(m, bIdx, f * 24f - 12f);
            r.addView(s, new LinearLayout.LayoutParams(0, Ui.dp(32), 1f));
            box.addView(r, Widgets.MW);
        }

        GlassMenu.customSheet(m, anchor, m.getString(R.string.equalizer), box, null);
    }

    private void showMoreMenu(View anchor) {
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.sub_ai_generate), Icons.AI, false, this::startAiSubtitle));
        es.add(new GlassMenu.Entry(m.getString(R.string.pip_mode), Icons.PIP, false, () -> m.enterPip()));
        es.add(new GlassMenu.Entry(m.getString(R.string.ab_repeat), Icons.AB, false, () -> showAbSheet(anchorOf(anchor))));
        es.add(new GlassMenu.Entry(m.getString(R.string.bookmarks), Icons.BOOKMARK, false, () -> showBookmarksSheet(anchorOf(anchor))));
        es.add(new GlassMenu.Entry(m.getString(R.string.sleep_timer), Icons.TIMER, false, () -> showSleepSheet(anchorOf(anchor))));
        es.add(new GlassMenu.Entry(m.getString(R.string.screenshot), Icons.CAMERA, false, this::captureScreenshot));
        es.add(new GlassMenu.Entry(m.getString(R.string.bg_audio), Icons.HEADPHONES, VideoPlayer.backgroundAudio, () -> {
            VideoPlayer.backgroundAudio = !VideoPlayer.backgroundAudio;
            VideoPlayer.notifyAll0();
            showHud(VideoPlayer.backgroundAudio ? "백그라운드 재생 켜짐" : "백그라운드 재생 꺼짐");
        }));
        if (VideoPlayer.current != null) {
            es.add(new GlassMenu.Entry(m.getString(R.string.info), Icons.INFO, false, () -> Pages.showVideoInfo(m, VideoPlayer.current)));
        }
        GlassMenu.sheet(m, anchorOf(anchor), "플레이어 도구", es, anchor);
    }

    private void showAbSheet(RectF anchor) {
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.ab_set_a) + (VideoPlayer.repeatA >= 0 ? " (" + Ui.time(VideoPlayer.repeatA) + ")" : ""),
                Icons.AB, VideoPlayer.repeatA >= 0, () -> {
            VideoPlayer.repeatA = VideoPlayer.positionMs();
            showHud("A 지점: " + Ui.time(VideoPlayer.repeatA));
        }));
        es.add(new GlassMenu.Entry(m.getString(R.string.ab_set_b) + (VideoPlayer.repeatB >= 0 ? " (" + Ui.time(VideoPlayer.repeatB) + ")" : ""),
                Icons.AB, VideoPlayer.repeatB >= 0, () -> {
            VideoPlayer.repeatB = VideoPlayer.positionMs();
            if (VideoPlayer.repeatA < 0) VideoPlayer.repeatA = 0;
            showHud("A-B 반복: " + Ui.time(VideoPlayer.repeatA) + " ~ " + Ui.time(VideoPlayer.repeatB));
        }));
        es.add(new GlassMenu.Entry(m.getString(R.string.ab_clear), Icons.CLOSE, false, () -> {
            VideoPlayer.repeatA = VideoPlayer.repeatB = -1;
            showHud(m.getString(R.string.ab_clear));
        }));
        GlassMenu.sheet(m, anchor, m.getString(R.string.ab_repeat), es, null);
    }

    private void showBookmarksSheet(RectF anchor) {
        VideoLibrary.Video v = VideoPlayer.current;
        if (v == null) return;
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry("+ " + m.getString(R.string.add_bookmark) + " (" + Ui.time(VideoPlayer.positionMs()) + ")",
                Icons.PLUS, false, () -> {
            VideoLibrary.addBookmark(v, VideoPlayer.positionMs(), null);
            Toast.makeText(m, R.string.bookmark_added, Toast.LENGTH_SHORT).show();
        }));
        List<VideoLibrary.Bookmark> bms = VideoLibrary.getBookmarks(v);
        for (int i = 0; i < bms.size(); i++) {
            final VideoLibrary.Bookmark b = bms.get(i);
            es.add(new GlassMenu.Entry(b.label + "  (" + Ui.time(b.posMs) + ")", Icons.BOOKMARK, false, () -> VideoPlayer.seekTo(b.posMs)));
        }
        GlassMenu.sheet(m, anchor, m.getString(R.string.bookmarks), es, null);
    }

    private void showSleepSheet(RectF anchor) {
        List<GlassMenu.Entry> es = new ArrayList<>();
        es.add(new GlassMenu.Entry(m.getString(R.string.sleep_off), 0, VideoPlayer.sleepUntilMs < 0 && !VideoPlayer.sleepAfterCurrent,
                () -> { VideoPlayer.setSleepTimerMinutes(0); showHud("취침 타이머 꺼짐"); }));
        int[] mins = {15, 30, 45, 60};
        for (int min : mins) {
            es.add(new GlassMenu.Entry(min + "분 후 정지", Icons.TIMER, false, () -> {
                VideoPlayer.setSleepTimerMinutes(min);
                showHud(min + "분 후 자동 정지");
            }));
        }
        es.add(new GlassMenu.Entry(m.getString(R.string.sleep_end), Icons.TIMER, VideoPlayer.sleepAfterCurrent, () -> {
            VideoPlayer.sleepUntilMs = -1;
            VideoPlayer.sleepAfterCurrent = true;
            showHud(m.getString(R.string.sleep_end));
        }));
        GlassMenu.sheet(m, anchor, m.getString(R.string.sleep_timer), es, null);
    }

    private void cycleRepeat() {
        VideoPlayer.repeatMode = (VideoPlayer.repeatMode + 1) % 3;
        int res = VideoPlayer.repeatMode == VideoPlayer.REPEAT_ONE ? R.string.repeat_one
                : (VideoPlayer.repeatMode == VideoPlayer.REPEAT_ALL ? R.string.repeat_all : R.string.repeat_off);
        showHud(m.getString(res));
        VideoPlayer.notifyAll0();
    }

    private void captureScreenshot() {
        Bitmap bmp = texture.getBitmap();
        if (bmp == null) { Toast.makeText(m, "화면을 캡처할 수 없습니다", Toast.LENGTH_SHORT).show(); return; }
        try {
            String name = "Play_" + System.currentTimeMillis() + ".png";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Play");
            Uri uri = m.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (OutputStream os = m.getContentResolver().openOutputStream(uri)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                Toast.makeText(m, R.string.screenshot_saved, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(m, "저장 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            bmp.recycle();
        }
    }

    // ---- AI Subtitle generation flow ----
    void startAiSubtitle() {
        if (VideoPlayer.current == null) return;
        cancelAiSubtitle();
        aiHud.setVisibility(VISIBLE);
        aiStage.setText(m.getString(R.string.ai_extracting));
        aiCancel = AiSubtitle.generate(m, VideoPlayer.current, new AiSubtitle.Callback() {
            @Override public void onProgress(String stage, int percent) {
                aiStage.setText(stage);
                int totalW = Math.max(1, ((View) aiBarFill.getParent()).getWidth());
                aiBarFill.getLayoutParams().width = Math.max(Ui.dp(6), (int) (totalW * (percent / 100f)));
                aiBarFill.requestLayout();
            }

            @Override public void onSuccess(Subtitle.Track track, java.io.File srtFile) {
                aiHud.setVisibility(GONE);
                VideoPlayer.primarySub = track;
                VideoPlayer.subEnabled = true;
                if (VideoPlayer.current != null) VideoPlayer.current.hasSubtitle = true;
                Toast.makeText(m, R.string.ai_done, Toast.LENGTH_LONG).show();
                updatePlaybackUi();
            }

            @Override public void onError(String message) {
                aiHud.setVisibility(GONE);
                new AlertDialog.Builder(m).setTitle(R.string.sub_ai_generate).setMessage(message)
                        .setPositiveButton(R.string.ok, null).show();
            }
        });
    }

    private void cancelAiSubtitle() {
        if (aiCancel != null) { aiCancel.set(true); aiCancel = null; }
        aiHud.setVisibility(GONE);
    }

    // ---- Custom SeekBar with A-B repeat range & Bookmark markers ----
    final class SeekView extends View {
        private long pos, dur;
        private boolean scrubbing;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SeekView(Context c) { super(c); }

        void update(long position, long duration) {
            if (scrubbing) return;
            pos = position;
            dur = Math.max(1, duration);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float pad = Ui.dpf(10);
            float usable = Math.max(1f, w - pad * 2);
            float cy = h / 2f;
            float th = Ui.dpf(scrubbing ? 7 : 5);

            // Base track
            p.setColor(0x44FFFFFF);
            c.drawRoundRect(pad, cy - th / 2f, w - pad, cy + th / 2f, th / 2f, th / 2f, p);

            // A-B repeat highlight
            if (VideoPlayer.repeatA >= 0 && dur > 0) {
                float ax = pad + usable * Ui.clamp(VideoPlayer.repeatA / (float) dur, 0f, 1f);
                float bx = VideoPlayer.repeatB > VideoPlayer.repeatA
                        ? pad + usable * Ui.clamp(VideoPlayer.repeatB / (float) dur, 0f, 1f) : ax + Ui.dpf(4);
                p.setColor(0xCCFFCA28);
                c.drawRoundRect(ax, cy - th / 2f, bx, cy + th / 2f, th / 2f, th / 2f, p);
            }

            // Progress fill (A-style = luminous white track; G-style = accent)
            float frac = Ui.clamp(pos / (float) Math.max(1, dur), 0f, 1f);
            float fx = pad + usable * frac;
            if (glassDeck) {
                p.setColor(0xFFFFFFFF);
                if (scrubbing) p.setShadowLayer(Ui.dpf(8), 0, 0, 0xAAFFFFFF);
                c.drawRoundRect(pad, cy - th / 2f, fx, cy + th / 2f, th / 2f, th / 2f, p);
                p.clearShadowLayer();
            } else {
                p.setColor(Ui.ACCENT);
                c.drawRoundRect(pad, cy - th / 2f, fx, cy + th / 2f, th / 2f, th / 2f, p);
            }

            // Bookmark dots
            if (VideoPlayer.current != null && dur > 0) {
                p.setColor(0xFFFFFFFF);
                for (VideoLibrary.Bookmark b : VideoLibrary.getBookmarks(VideoPlayer.current)) {
                    float bx = pad + usable * Ui.clamp(b.posMs / (float) dur, 0f, 1f);
                    c.drawCircle(bx, cy, Ui.dpf(3), p);
                }
            }

            // Thumb
            p.setColor(0xFFFFFFFF);
            c.drawCircle(fx, cy, Ui.dpf(scrubbing ? 9.5f : 7f), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float pad = Ui.dpf(10);
            float usable = Math.max(1f, getWidth() - pad * 2);
            float f = Ui.clamp((e.getX() - pad) / usable, 0f, 1f);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scrubbing = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    pos = (long) (f * dur);
                    timeCur.setText(Ui.time(pos));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    pos = (long) (f * dur);
                    timeCur.setText(Ui.time(pos));
                    showHud(Ui.time(pos));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scrubbing = false;
                    VideoPlayer.seekTo(pos);
                    pokeChrome();
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
