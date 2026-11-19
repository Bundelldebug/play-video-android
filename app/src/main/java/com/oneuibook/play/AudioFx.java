package com.oneuibook.play;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;

/**
 * 10-band Equalizer (DynamicsProcessing) + Volume Boost (LoudnessEnhancer, up to +18 dB).
 */
final class AudioFx {
    private AudioFx() {}

    static final float[] FREQS = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final String[] LABELS = {"31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"};
    static final String[] PRESET_NAMES = {"기본", "영화", "대화 선명", "베이스 강조", "야간 모드", "사용자 지정"};
    static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {3.5f, 3f, 2f, 0.5f, -0.5f, 0.5f, 2f, 3f, 3.5f, 2.5f},
            {-2f, -1.5f, -0.5f, 1.5f, 3.5f, 4.5f, 4f, 2.5f, 1f, 0f},
            {5.5f, 5f, 3.5f, 1.5f, 0f, 0f, 0.5f, 1f, 1.5f, 1f},
            {-3f, -2f, 0f, 2f, 3f, 3f, 2f, 0f, -2f, -3f}
    };

    static boolean eqEnabled;
    static int presetIndex;
    static final float[] bands = new float[10];
    /** 0..100 representing 0..+18 dB loudness boost (volume 100%..200%). */
    static int boostPct;

    private static DynamicsProcessing dp;
    private static LoudnessEnhancer booster;
    private static int attachedSession = -1;

    static void init(Context c) {
        SharedPreferences p = MainActivity.prefs(c);
        eqEnabled = p.getBoolean("eq_on", false);
        presetIndex = p.getInt("eq_preset", 0);
        boostPct = p.getInt("audio_boost", 0);
        for (int i = 0; i < 10; i++) bands[i] = Ui.clamp(p.getFloat("eq_b" + i, 0f), -12f, 12f);
    }

    static void save(Context c) {
        SharedPreferences.Editor e = MainActivity.prefs(c).edit()
                .putBoolean("eq_on", eqEnabled)
                .putInt("eq_preset", presetIndex)
                .putInt("audio_boost", boostPct);
        for (int i = 0; i < 10; i++) e.putFloat("eq_b" + i, bands[i]);
        e.apply();
    }

    static void attach(int sessionId) {
        if (sessionId <= 0) return;
        if (sessionId == attachedSession && (dp != null || booster != null)) {
            apply();
            return;
        }
        release();
        attachedSession = sessionId;
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, 10, false, 0, false, 0, false).build();
            dp = new DynamicsProcessing(0, sessionId, cfg);
            for (int i = 0; i < 10; i++) {
                DynamicsProcessing.EqBand b = new DynamicsProcessing.EqBand(true, FREQS[i], bands[i]);
                dp.setPreEqBandAllChannelsTo(i, b);
            }
            dp.setEnabled(eqEnabled);
        } catch (Throwable ignored) {
            dp = null;
        }
        try {
            booster = new LoudnessEnhancer(sessionId);
            applyBoost();
        } catch (Throwable ignored) {
            booster = null;
        }
    }

    static void applyPreset(Context c, int idx) {
        presetIndex = idx;
        if (idx >= 0 && idx < PRESETS.length) {
            System.arraycopy(PRESETS[idx], 0, bands, 0, 10);
            eqEnabled = idx != 0;
        }
        save(c);
        apply();
    }

    static void setBand(Context c, int idx, float db) {
        bands[idx] = Ui.clamp(db, -12f, 12f);
        presetIndex = PRESET_NAMES.length - 1;
        eqEnabled = true;
        save(c);
        apply();
    }

    static void setBoost(Context c, int pct) {
        boostPct = Math.max(0, Math.min(100, pct));
        save(c);
        applyBoost();
    }

    static void apply() {
        if (dp != null) {
            try {
                for (int i = 0; i < 10; i++) {
                    dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, FREQS[i], bands[i]));
                }
                dp.setEnabled(eqEnabled);
            } catch (Throwable ignored) {}
        }
        applyBoost();
    }

    private static void applyBoost() {
        if (booster != null) {
            try {
                int gainMb = Math.round(boostPct * 18f); // up to +1800 mB (+18 dB)
                booster.setTargetGain(gainMb);
                booster.setEnabled(boostPct > 0);
            } catch (Throwable ignored) {}
        }
    }

    static void release() {
        attachedSession = -1;
        if (dp != null) { try { dp.release(); } catch (Throwable ignored) {} dp = null; }
        if (booster != null) { try { booster.release(); } catch (Throwable ignored) {} booster = null; }
    }
}
