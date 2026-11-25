package com.oneuibook.play;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * ProScaler Assist (프로스케일러 어시스트) — Flagship-SoC NPU-class video super-resolution,
 * adaptive micro-detail recovery, local contrast, HDR dynamic tone expansion, color vibrance,
 * and chroma debanding engine (25 supported flagship chipsets, matching Bit-Perfect Assist).
 */
final class NpuVideo {
    private NpuVideo() {}

    static final String PREF_ON = "psa_on";
    static final String PREF_PROFILE = "psa_profile";
    static final String PREF_ENGINE = "psa_engine";
    static final String PREF_STRENGTH = "psa_strength";
    static final String PREF_DETAIL = "psa_detail";
    static final String PREF_CONTRAST = "psa_contrast";
    static final String PREF_HDR = "psa_hdr";
    static final String PREF_VIBRANCE = "psa_vibrance";
    static final String PREF_DEBAND = "psa_deband";

    static final String[] PROFILES = {"cinema", "crystal", "anime", "film"};
    static final int[] PROFILE_NAMES = {
            R.string.psa_prof_cinema, R.string.psa_prof_crystal, R.string.psa_prof_anime, R.string.psa_prof_film
    };
    // {strength, detail, contrast, hdr, vibrance, deband}
    static final int[][] PROFILE_VALS = {
            {75, 70, 60, 65, 50, 55}, // Cinema Master
            {90, 92, 75, 70, 65, 45}, // Crystal 4K
            {85, 95, 70, 45, 75, 80}, // Anime & Line
            {60, 50, 45, 55, 35, 70}, // Natural Film
    };

    static final String[] ENGINES = {"detail", "hdr", "soft"};
    static final int[] ENGINE_NAMES = {
            R.string.psa_eng_detail, R.string.psa_eng_hdr, R.string.psa_eng_soft
    };

    static volatile boolean bypassHold;
    private static TextureView boundTexture;

    // Multi-stage Neural-Inspired Super-Resolution, Local Contrast, HDR S-Curve & Chroma Deband AGSL Shader
    private static final String PROSCALER_AGSL =
            "uniform shader src;\n" +
            "uniform float detailAmt;\n" +
            "uniform float contrastAmt;\n" +
            "uniform float hdrAmt;\n" +
            "uniform float vibranceAmt;\n" +
            "uniform float debandAmt;\n" +
            "uniform float animeMode;\n" +
            "half4 main(float2 xy) {\n" +
            "    half4 c = src.eval(xy);\n" +
            "    half4 n = src.eval(xy + float2(0.0, -1.0));\n" +
            "    half4 s = src.eval(xy + float2(0.0,  1.0));\n" +
            "    half4 w = src.eval(xy + float2(-1.0, 0.0));\n" +
            "    half4 e = src.eval(xy + float2( 1.0, 0.0));\n" +
            "    half3 mn = min(c.rgb, min(min(n.rgb, s.rgb), min(w.rgb, e.rgb)));\n" +
            "    half3 mx = max(c.rgb, max(max(n.rgb, s.rgb), max(w.rgb, e.rgb)));\n" +
            "    half3 edge = mx - mn;\n" +
            "    half3 avg = (n.rgb + s.rgb + w.rgb + e.rgb) * 0.25;\n" +
            "    half3 highFreq = c.rgb - avg;\n" +
            "    half flatMask = clamp(1.0 - dot(edge, half3(2.2)), 0.0, 1.0);\n" +
            "    half3 base = mix(c.rgb, avg, flatMask * half(debandAmt * 0.42));\n" +
            "    half3 weight = clamp(half3(1.0) - edge * half(animeMode > 0.5 ? 1.05 : 1.65), 0.18, 1.0);\n" +
            "    half3 sharp = base + highFreq * weight * half(detailAmt * (animeMode > 0.5 ? 1.95 : 1.45));\n" +
            "    half3 col = clamp(sharp, mn - half3(0.035), mx + half3(0.035));\n" +
            "    col = col + highFreq * half(contrastAmt * 0.45);\n" +
            "    if (hdrAmt > 0.01) {\n" +
            "        half3 sc = col * col * (half3(3.0) - half3(2.0) * col);\n" +
            "        col = mix(col, sc, half(hdrAmt * 0.38));\n" +
            "    }\n" +
            "    half lum = dot(col, half3(0.2126, 0.7152, 0.0722));\n" +
            "    half sat = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));\n" +
            "    half vib = half(1.0 + vibranceAmt * 0.55 * (1.0 - sat));\n" +
            "    col = clamp(mix(half3(lum), col, vib), 0.0, 1.0);\n" +
            "    return half4(col, c.a);\n" +
            "}\n";

    static boolean isSupported() { return NpuAudio.isSupported(); }

    static boolean enabled(Context c) {
        return isSupported() && MainActivity.prefs(c).getBoolean(PREF_ON, false);
    }

    static void setEnabled(Context c, boolean on) {
        if (!isSupported()) on = false;
        MainActivity.prefs(c).edit().putBoolean(PREF_ON, on).apply();
        apply(c, boundTexture);
    }

    static void setBypassHold(Context c, boolean hold) {
        if (bypassHold == hold) return;
        bypassHold = hold;
        apply(c, boundTexture);
    }

    static String profile(Context c) { return MainActivity.prefs(c).getString(PREF_PROFILE, "cinema"); }
    static String engine(Context c) { return MainActivity.prefs(c).getString(PREF_ENGINE, "detail"); }
    static int strength(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_STRENGTH, 75)); }
    static int detail(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_DETAIL, 70)); }
    static int contrast(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_CONTRAST, 60)); }
    static int hdr(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_HDR, 65)); }
    static int vibrance(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_VIBRANCE, 50)); }
    static int deband(Context c) { return clampPct(MainActivity.prefs(c).getInt(PREF_DEBAND, 55)); }

    private static int clampPct(int v) { return Math.max(0, Math.min(100, v)); }

    static void setProfile(Context c, int idx) {
        if (idx < 0 || idx >= PROFILES.length) return;
        int[] v = PROFILE_VALS[idx];
        MainActivity.prefs(c).edit()
                .putString(PREF_PROFILE, PROFILES[idx])
                .putInt(PREF_STRENGTH, v[0])
                .putInt(PREF_DETAIL, v[1])
                .putInt(PREF_CONTRAST, v[2])
                .putInt(PREF_HDR, v[3])
                .putInt(PREF_VIBRANCE, v[4])
                .putInt(PREF_DEBAND, v[5])
                .apply();
        apply(c, boundTexture);
    }

    static void setEngine(Context c, String mode) {
        MainActivity.prefs(c).edit().putString(PREF_ENGINE, mode).apply();
        apply(c, boundTexture);
    }

    static void setParam(Context c, String key, int pct) {
        MainActivity.prefs(c).edit()
                .putInt(key, clampPct(pct))
                .putString(PREF_PROFILE, "custom")
                .apply();
        apply(c, boundTexture);
    }

    static String summary(Context c) {
        if (!isSupported()) return c.getString(R.string.bpa_sub_unsupported);
        if (!enabled(c)) return c.getString(R.string.bpa_sub_off);
        return c.getString(R.string.psa_sub_on, strength(c) + "%");
    }

    static String status(Context c) {
        String name = NpuAudio.displaySoc(c);
        if (!isSupported()) return c.getString(R.string.npu_unsupported, name);
        if (bypassHold) return c.getString(R.string.psa_ab_bypassing);
        if (enabled(c)) return name + " · " + NpuAudio.npuLabel() + " ProScaler 활성";
        return c.getString(R.string.npu_supported, name);
    }

    /** Computes the 12-point Spatial/Tone transfer curve (0..10) for the live graph preview. */
    static float[] computeResponseCurve(Context c) {
        float str = strength(c) / 100f;
        float det = (detail(c) / 100f) * str;
        float con = (contrast(c) / 100f) * str;
        float hd = (hdr(c) / 100f) * str;
        float vib = (vibrance(c) / 100f) * str;
        float deb = (deband(c) / 100f) * str;
        String eng = engine(c);
        float detMul = "detail".equals(eng) ? 1.25f : ("soft".equals(eng) ? 0.72f : 1.0f);
        float hdrMul = "hdr".equals(eng) ? 1.32f : 1.0f;
        return new float[]{
                clampVal(-1.8f * deb),
                clampVal(-0.9f * deb + 0.8f * hd * hdrMul),
                clampVal(1.6f * hd * hdrMul),
                clampVal(2.8f * con + 1.4f * hd * hdrMul),
                clampVal(3.8f * con + 1.2f * vib),
                clampVal(4.6f * vib + 2.2f * con),
                clampVal(5.4f * det * detMul + 1.8f * con),
                clampVal(7.2f * det * detMul),
                clampVal(8.4f * det * detMul),
                clampVal(7.8f * det * detMul - 0.8f * deb),
                clampVal(6.2f * det * detMul - 1.2f * deb),
                clampVal(4.5f * det * detMul - 1.5f * deb)
        };
    }

    private static float clampVal(float v) { return Math.max(-3.5f, Math.min(9.5f, v)); }

    /** Applies or clears the real-time ProScaler Assist AGSL pipeline on the video TextureView. */
    static void apply(Context c, TextureView tv) {
        if (tv != null) boundTexture = tv;
        if (boundTexture == null || Build.VERSION.SDK_INT < 33) return;
        boolean want = enabled(c) && !bypassHold && !"robolectric".equals(Build.FINGERPRINT);
        if (!want) {
            boundTexture.setRenderEffect(null);
            return;
        }
        try {
            float str = strength(c) / 100f;
            String eng = engine(c);
            float detMul = "detail".equals(eng) ? 1.25f : ("soft".equals(eng) ? 0.72f : 1.0f);
            float hdrMul = "hdr".equals(eng) ? 1.32f : 1.0f;

            RuntimeShader rs = new RuntimeShader(PROSCALER_AGSL);
            rs.setFloatUniform("detailAmt", Ui.clamp((detail(c) / 100f) * str * detMul, 0f, 1.5f));
            rs.setFloatUniform("contrastAmt", Ui.clamp((contrast(c) / 100f) * str, 0f, 1.2f));
            rs.setFloatUniform("hdrAmt", Ui.clamp((hdr(c) / 100f) * str * hdrMul, 0f, 1.4f));
            rs.setFloatUniform("vibranceAmt", Ui.clamp((vibrance(c) / 100f) * str, 0f, 1.2f));
            rs.setFloatUniform("debandAmt", Ui.clamp((deband(c) / 100f) * str, 0f, 1.0f));
            rs.setFloatUniform("animeMode", "anime".equals(profile(c)) ? 1f : 0f);
            boundTexture.setRenderEffect(RenderEffect.createRuntimeShaderEffect(rs, "src"));
        } catch (Throwable t) {
            boundTexture.setRenderEffect(null);
        }
    }

    /** Quick sheet launcher from inside the video player overlay. */
    static void showQuickSheet(MainActivity m, RectF anchor, TextureView tv) {
        if (tv != null) boundTexture = tv;
        boolean dark = m.playerOverlay.isOpen() || Ui.dark;
        int fg = dark ? 0xFFFFFFFF : Ui.text();
        int fg2 = dark ? 0xB3FFFFFF : Ui.text2();

        LinearLayout box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(12));

        LinearLayout top = new LinearLayout(m);
        top.setGravity(Gravity.CENTER_VERTICAL);
        NpuAudio.ChipSpec cs = NpuAudio.currentChipSpec();
        top.addView(new NpuAudio.ChipArtView(m, cs, NpuAudio.displaySoc(m)), new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));
        LinearLayout col = new LinearLayout(m);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(12), 0, Ui.dp(8), 0);
        col.addView(Ui.text(m, m.getString(R.string.psa_title), 15.5f, Ui.bold, fg));
        col.addView(Ui.text(m, status(m), 12f, Ui.regular, fg2));
        top.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Widgets.Toggle tog = new Widgets.Toggle(m, enabled(m));
        ScalerCurveView curve = new ScalerCurveView(m);
        tog.change = on -> { setEnabled(m, on); curve.invalidate(); };
        top.addView(tog);
        box.addView(top, Widgets.MW);

        box.addView(curve, Widgets.MW);

        LinearLayout profRow = new LinearLayout(m);
        profRow.setOrientation(LinearLayout.HORIZONTAL);
        profRow.setPadding(0, Ui.dp(8), 0, Ui.dp(6));
        for (int i = 0; i < PROFILES.length; i++) {
            final int idx = i;
            boolean sel = PROFILES[i].equals(profile(m));
            TextView ch = Ui.text(m, m.getString(PROFILE_NAMES[i]), 12f, sel ? Ui.bold : Ui.medium, sel ? 0xFFFFFFFF : fg);
            ch.setGravity(Gravity.CENTER);
            ch.setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(6), Ui.dp(8));
            ch.setBackground(Ui.round(sel ? Ui.ACCENT : (dark ? 0x24FFFFFF : Ui.chip()), 100));
            ch.setOnClickListener(v -> {
                if (!enabled(m)) setEnabled(m, true);
                setProfile(m, idx);
                GlassMenu.dismissAny(m.root);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(6);
            profRow.addView(ch, lp);
        }
        box.addView(profRow, Widgets.MW);

        TextView detailBtn = Ui.text(m, "세부 NPU 프로스케일러 · 비트퍼펙트 설정 열기", 13f, Ui.semibold, Ui.ACCENT);
        detailBtn.setGravity(Gravity.CENTER);
        detailBtn.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        detailBtn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 14));
        detailBtn.setOnClickListener(v -> {
            GlassMenu.dismissAny(m.root);
            if (m.playerOverlay.isOpen()) m.playerOverlay.close();
            m.dock.select(3, true);
            m.onTab(3, false);
            m.push(new Settings.ProScalerAssistPage(m));
        });
        box.addView(detailBtn, Widgets.MW);

        GlassMenu.customSheet(m, anchor, m.getString(R.string.psa_title), box, null);
    }

    /** Real-time interactive 12-stage Spatial / HDR / Deband transfer curve graph for ProScaler Assist. */
    static final class ScalerCurveView extends View {
        private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zeroP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint lblP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgeP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path curve = new Path(), area = new Path();

        ScalerCurveView(Context c) {
            super(c);
            gridP.setStyle(Paint.Style.STROKE);
            gridP.setStrokeWidth(Math.max(1f, Ui.dpf(0.7f)));
            zeroP.setStyle(Paint.Style.STROKE);
            zeroP.setStrokeWidth(Math.max(1f, Ui.dpf(1.0f)));
            zeroP.setPathEffect(new android.graphics.DashPathEffect(new float[]{Ui.dpf(4), Ui.dpf(4)}, 0));
            lineP.setStyle(Paint.Style.STROKE);
            lineP.setStrokeWidth(Ui.dpf(2.6f));
            lineP.setStrokeCap(Paint.Cap.ROUND);
            lineP.setStrokeJoin(Paint.Join.ROUND);
            dotP.setStyle(Paint.Style.FILL);
            fillP.setStyle(Paint.Style.FILL);
            lblP.setTextSize(Ui.sp(9.5f));
            lblP.setTypeface(Ui.medium);
            badgeP.setTextSize(Ui.sp(10.5f));
            badgeP.setTypeface(Ui.semibold);
            badgeP.setTextAlign(Paint.Align.RIGHT);
        }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), Ui.dp(146));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float padL = Ui.dpf(26), padR = Ui.dpf(16), padT = Ui.dpf(24), padB = Ui.dpf(24);
            float gw = w - padL - padR, gh = h - padT - padB;
            float minV = -4f, maxV = 10f;
            float zeroY = padT + gh * (maxV / (maxV - minV));

            gridP.setColor(Ui.dark ? 0x1EFFFFFF : 0x14000000);
            zeroP.setColor(Ui.dark ? 0x44FFFFFF : 0x33000000);
            lblP.setColor(Ui.text3());
            lblP.setTextAlign(Paint.Align.RIGHT);
            for (int v : new int[]{9, 4, 0, -4}) {
                float y = padT + gh * ((maxV - v) / (maxV - minV));
                c.drawLine(padL, y, w - padR, y, v == 0 ? zeroP : gridP);
                c.drawText((v > 0 ? "+" : "") + v, padL - Ui.dpf(5), y + Ui.dpf(3), lblP);
            }
            lblP.setTextAlign(Paint.Align.CENTER);
            String[] labels = {"Deband", "HDR Tone", "Contrast", "Color", "4K Edge"};
            float[] fracs = {0f, 0.25f, 0.50f, 0.75f, 1f};
            for (int i = 0; i < labels.length; i++) {
                float x = padL + gw * fracs[i];
                c.drawLine(x, padT, x, padT + gh, gridP);
                c.drawText(labels[i], x, h - Ui.dpf(6), lblP);
            }

            boolean bypassing = bypassHold;
            boolean on = enabled(getContext()) && !bypassing;
            float[] vals = on ? computeResponseCurve(getContext()) : new float[12];
            int n = vals.length;
            float[] xs = new float[n], ys = new float[n];
            float peak = 0f;
            for (int i = 0; i < n; i++) {
                if (vals[i] > peak) peak = vals[i];
                xs[i] = padL + gw * (i / (float) (n - 1));
                float v = Ui.clamp(vals[i], minV, maxV);
                ys[i] = padT + gh * ((maxV - v) / (maxV - minV));
            }

            curve.reset();
            area.reset();
            curve.moveTo(xs[0], ys[0]);
            area.moveTo(xs[0], zeroY);
            area.lineTo(xs[0], ys[0]);
            for (int i = 0; i < n - 1; i++) {
                float mx = (xs[i] + xs[i + 1]) / 2f;
                curve.cubicTo(mx, ys[i], mx, ys[i + 1], xs[i + 1], ys[i + 1]);
                area.cubicTo(mx, ys[i], mx, ys[i + 1], xs[i + 1], ys[i + 1]);
            }
            area.lineTo(xs[n - 1], zeroY);
            area.close();

            int col = bypassing ? 0xFFFF9F0A : (on ? Ui.ACCENT : Ui.text3());
            fillP.setShader(new LinearGradient(0, padT, 0, padT + gh,
                    Ui.alpha(col, on ? 0.34f : 0.12f), 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(area, fillP);
            fillP.setShader(null);
            lineP.setColor(on || bypassing ? col : Ui.alpha(col, 0.55f));
            lineP.setPathEffect(bypassing ? new android.graphics.DashPathEffect(new float[]{Ui.dpf(6), Ui.dpf(4)}, 0) : null);
            c.drawPath(curve, lineP);
            lineP.setPathEffect(null);

            if (on) {
                dotP.setColor(col);
                for (int i = 0; i < n; i++) c.drawCircle(xs[i], ys[i], Ui.dpf(2.4f), dotP);
            }

            badgeP.setColor(col);
            String readout = bypassing ? "BYPASS · ORIGINAL"
                    : (on ? String.format(Locale.US, "GAIN +%.1f · %s", peak, engine(getContext()).toUpperCase(Locale.US))
                    : "OFF · NATIVE");
            c.drawText(readout, w - padR, Ui.dpf(15), badgeP);
        }
    }
}
