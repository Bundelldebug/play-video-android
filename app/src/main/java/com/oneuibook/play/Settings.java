package com.oneuibook.play;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Settings screens (matching Prism 9.5.16 UI language):
 * General, Appearance, Playback, Bit-Perfect Assist (NPU Audio), ProScaler Assist (NPU Video),
 * Supported Chipsets (25 Flagship SoCs), Subtitles, AI Subtitles, Storage, About.
 */
final class Settings {
    private Settings() {}

    static boolean pref(Context c, String k, boolean def) { return MainActivity.prefs(c).getBoolean(k, def); }

    static View toggleRow(Widgets.Group g, int icon, String title, String key, boolean def, Runnable after) {
        View r = g.row(icon, Ui.ACCENT, title, null, false, null);
        Widgets.Toggle t = new Widgets.Toggle(g.getContext(), pref(g.getContext(), key, def));
        t.change = on -> {
            MainActivity.prefs(g.getContext()).edit().putBoolean(key, on).apply();
            if (after != null) after.run();
        };
        ((LinearLayout) r).addView(t);
        return r;
    }

    static final class SettingsPage extends Page {
        SettingsPage(MainActivity m) {
            super(m, m.getString(R.string.tab_settings), true);
            headerIcons = new int[0];
            setGrouped(true);
        }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group app = new Widgets.Group(m, m.getString(R.string.set_application));
            app.row(Icons.SETTINGS, Ui.ACCENT, m.getString(R.string.set_general), null, true, v -> m.push(new GeneralPage(m)));
            app.row(Icons.APPEARANCE, Ui.ACCENT, m.getString(R.string.set_appearance), null, true, v -> m.push(new AppearancePage(m)));
            app.row(Icons.PLAY, Ui.ACCENT, m.getString(R.string.set_playback), null, true, v -> m.push(new PlaybackPage(m)));
            app.row(Icons.PROSCALER, Ui.ACCENT, m.getString(R.string.psa_title), NpuVideo.summary(m), true, v -> m.push(new ProScalerAssistPage(m)));
            app.row(Icons.HEADPHONES, Ui.ACCENT, m.getString(R.string.bpa_title), NpuAudio.summary(m), true, v -> m.push(new BitPerfectAssistPage(m)));
            app.row(Icons.YOUTUBE, 0xFFFF0033, m.getString(R.string.yt_title), m.getString(R.string.yt_source_settings), true, v -> YouTubeHub.showSourceSettingsSheet(m));
            app.row(Icons.SUBTITLE, Ui.ACCENT, m.getString(R.string.set_subtitles), null, true, v -> m.push(new SubtitleSettingsPage(m)));
            app.row(Icons.AI, Ui.ACCENT, m.getString(R.string.set_ai), engineLabel(m), true, v -> m.push(new AiSettingsPage(m)));
            app.row(Icons.STORAGE, Ui.ACCENT, m.getString(R.string.set_storage), null, true, v -> m.push(new StoragePage(m)));
            content.addView(app, Widgets.MW);

            Widgets.Group about = new Widgets.Group(m, null);
            about.row(Icons.INFO, Ui.ACCENT, m.getString(R.string.set_about), MainActivity.VERSION, true, v -> m.push(new AboutPage(m)));
            content.addView(about, Widgets.MW);
        }
    }

    private static String engineLabel(Context c) {
        String k = MainActivity.prefs(c).getString("ai_engine", AiSubtitle.ENGINE_GEMINI);
        for (int i = 0; i < AiSubtitle.ENGINE_KEYS.length; i++) {
            if (AiSubtitle.ENGINE_KEYS[i].equals(k)) return AiSubtitle.ENGINE_NAMES[i].split(" ")[0];
        }
        return "Gemini";
    }

    abstract static class Sub extends Page {
        Sub(MainActivity m, int title) {
            super(m, m.getString(title), true);
            back = true;
            headerIcons = new int[0];
            setGrouped(true);
        }
    }

    // ------------------------------------------------------------------ General
    static final class GeneralPage extends Sub {
        GeneralPage(MainActivity m) { super(m, R.string.set_general); }

        @Override void build() {
            MainActivity m = main;
            SharedPreferences p = MainActivity.prefs(m);
            String curLang = p.getString("lang", "system");
            Widgets.Group lg = new Widgets.Group(m, m.getString(R.string.language));
            String[][] langs = {
                    {"system", m.getString(R.string.lang_system)},
                    {"ko", "한국어"},
                    {"en", "English"},
                    {"ja", "日本語"}
            };
            for (String[] o : langs) {
                lg.option(o[1], o[0].equals(curLang), v -> {
                    p.edit().putString("lang", o[0]).apply();
                    m.recreate();
                });
            }
            content.addView(lg, Widgets.MW);

            String uf = p.getString("unfold_ui", "fold");
            Widgets.Group ufg = new Widgets.Group(m, m.getString(R.string.unfold_ui));
            String[][] ufs = {
                    {"fold", m.getString(R.string.unfold_fold)},
                    {"tablet", m.getString(R.string.unfold_tablet)}
            };
            for (String[] o : ufs) {
                ufg.option(o[1], o[0].equals(uf), v -> {
                    p.edit().putString("unfold_ui", o[0]).apply();
                    m.recreate();
                });
            }
            content.addView(ufg, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Appearance
    static final class AppearancePage extends Sub {
        AppearancePage(MainActivity m) { super(m, R.string.set_appearance); }

        @Override void build() {
            MainActivity m = main;
            SharedPreferences p = MainActivity.prefs(m);

            // Theme option group
            String theme = p.getString("theme", "system");
            Widgets.Group tg = new Widgets.Group(m, m.getString(R.string.theme));
            String[][] themes = {
                    {"system", m.getString(R.string.theme_system)},
                    {"light", m.getString(R.string.theme_light)},
                    {"dark", m.getString(R.string.theme_dark)}
            };
            for (String[] o : themes) {
                tg.option(o[1], o[0].equals(theme), v -> {
                    p.edit().putString("theme", o[0]).apply();
                    m.recreate();
                });
            }
            content.addView(tg, Widgets.MW);

            // Interface style A / G option group
            String style = p.getString("ui_style", "prism");
            Widgets.Group sg = new Widgets.Group(m, m.getString(R.string.style_title));
            String[][] styles = {
                    {"prism", m.getString(R.string.style_a)},
                    {"oneui", m.getString(R.string.style_g)}
            };
            for (String[] o : styles) {
                sg.option(o[1], o[0].equals(style), v -> {
                    p.edit().putString("ui_style", o[0]).apply();
                    m.recreate();
                });
            }
            content.addView(sg, Widgets.MW);

            // 4x2 Ringed Accent Swatch Grid (matching Prism 9.5.16)
            Widgets.Group cg = new Widgets.Group(m, m.getString(R.string.accent_color));
            cg.addCustom(buildAccentPicker(m));
            content.addView(cg, Widgets.MW);

            // Glass transparency slider
            Widgets.Group glg = new Widgets.Group(m, null);
            LinearLayout glBox = new LinearLayout(m);
            glBox.setOrientation(LinearLayout.VERTICAL);
            glBox.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(10));
            TextView glVal = Ui.text(m, m.getString(R.string.glass_transparency) + " · " + Math.round(Ui.glassT * 100) + "%", 15, Ui.medium, Ui.text());
            glBox.addView(glVal);
            Widgets.GlassSlider glSlider = new Widgets.GlassSlider(m, Ui.glassT, val -> {
                float t = Math.abs(val - 0.5f) < 0.03f ? 0.5f : val;
                Ui.glassT = t;
                p.edit().putFloat("glass_t", t).apply();
                glVal.setText(m.getString(R.string.glass_transparency) + " · " + Math.round(t * 100) + "%");
                m.invalidateChrome();
            });
            glBox.addView(glSlider);
            glg.addCustom(glBox);
            content.addView(glg, Widgets.MW);
        }

        private View buildAccentPicker(MainActivity m) {
            SharedPreferences p = MainActivity.prefs(m);
            String curKey = Ui.accentKey(p);
            LinearLayout grid = new LinearLayout(m);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(Ui.dp(10), Ui.dp(14), Ui.dp(10), Ui.dp(10));
            int cols = 4;
            LinearLayout row = null;
            for (int i = 0; i < Ui.ACCENT_KEYS.length; i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(m);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) rlp.topMargin = Ui.dp(10);
                    grid.addView(row, rlp);
                }
                final String key = Ui.ACCENT_KEYS[i];
                final int color = Ui.resolveAccent(m, key);
                final boolean sel = key.equals(curKey);
                LinearLayout cell = new LinearLayout(m);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(Ui.dp(2), Ui.dp(4), Ui.dp(2), Ui.dp(4));

                FrameLayout ring = new FrameLayout(m);
                if (sel) {
                    GradientDrawable rg = Ui.round(0x00000000, 100);
                    rg.setStroke(Ui.dp(2.2f), color);
                    ring.setBackground(rg);
                }
                FrameLayout dot = new FrameLayout(m);
                dot.setBackground(Ui.round(color, 100));
                if (sel) {
                    Icons.IconView ck = new Icons.IconView(m, Icons.CHECK, 18, 0xFFFFFFFF);
                    dot.addView(ck, new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
                }
                ring.addView(dot, new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(36), Gravity.CENTER));
                cell.addView(ring, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));

                TextView lbl = Ui.text(m, m.getString(Ui.ACCENT_LABELS[i]), 11.5f, sel ? Ui.semibold : Ui.regular, sel ? Ui.text() : Ui.text2());
                lbl.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = Ui.dp(4);
                cell.addView(lbl, llp);

                cell.setOnClickListener(v -> {
                    if (key.equals(Ui.accentKey(p))) return;
                    p.edit().putString("accent_color", key).apply();
                    Ui.ACCENT = Ui.resolveAccent(m, key);
                    m.refreshAppearance();
                });
                Ui.press(cell, 0.92f);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            return grid;
        }
    }

    // ------------------------------------------------------------------ Playback
    static final class PlaybackPage extends Sub {
        PlaybackPage(MainActivity m) { super(m, R.string.set_playback); }

        @Override void build() {
            MainActivity m = main;
            SharedPreferences p = MainActivity.prefs(m);
            Widgets.Group g = new Widgets.Group(m, null);
            toggleRow(g, Icons.CLOCK, m.getString(R.string.auto_resume), "auto_resume", true, null);
            toggleRow(g, Icons.NEXT, m.getString(R.string.auto_next), "auto_next", true, null);
            toggleRow(g, Icons.PIP, m.getString(R.string.auto_pip), "auto_pip", true, null);
            toggleRow(g, Icons.BRIGHTNESS, m.getString(R.string.gesture_brightness), "gesture_brightness", true, null);
            toggleRow(g, Icons.VOLUME, m.getString(R.string.gesture_volume), "gesture_volume", true, null);
            String rot = p.getString("auto_rotate", "sensor");
            String rotLbl = "video".equals(rot) ? m.getString(R.string.auto_rotate_video)
                    : ("portrait".equals(rot) ? m.getString(R.string.auto_rotate_portrait)
                    : ("landscape".equals(rot) ? m.getString(R.string.auto_rotate_landscape)
                    : m.getString(R.string.auto_rotate_sensor)));
            g.row(Icons.ROTATE, Ui.ACCENT, m.getString(R.string.auto_rotate), rotLbl, true, v -> {
                String[] keys = {"sensor", "video", "portrait", "landscape"};
                String[] labels = {m.getString(R.string.auto_rotate_sensor), m.getString(R.string.auto_rotate_video),
                        m.getString(R.string.auto_rotate_portrait), m.getString(R.string.auto_rotate_landscape)};
                int sel = "video".equals(rot) ? 1 : ("portrait".equals(rot) ? 2 : ("landscape".equals(rot) ? 3 : 0));
                Widgets.pickSheet(m, v, m.getString(R.string.auto_rotate), labels, sel, i -> {
                    p.edit().putString("auto_rotate", keys[i]).apply();
                    rebuild();
                });
            });
            int dt = p.getInt("double_tap_sec", 10);
            g.row(Icons.FORWARD, Ui.ACCENT, m.getString(R.string.double_tap_sec), dt + "초", true, v -> {
                int[] secs = {5, 10, 15, 30};
                String[] labels = {"5초", "10초", "15초", "30초"};
                int sel = dt == 5 ? 0 : (dt == 15 ? 2 : (dt == 30 ? 3 : 1));
                Widgets.pickSheet(m, v, m.getString(R.string.double_tap_sec), labels, sel, i -> {
                    p.edit().putInt("double_tap_sec", secs[i]).apply();
                    rebuild();
                });
            });
            g.row(Icons.EQ, Ui.ACCENT, m.getString(R.string.equalizer),
                    AudioFx.PRESET_NAMES[Math.max(0, Math.min(AudioFx.PRESET_NAMES.length - 1, AudioFx.presetIndex))],
                    true, v -> m.playerOverlay.showEqSheet(m.header.pillInRoot()));
            content.addView(g, Widgets.MW);

            // Bit-Perfect USB DAC output section
            Widgets.Group bp = new Widgets.Group(m, m.getString(R.string.bp_group));
            toggleRow(bp, Icons.DAC, m.getString(R.string.bp_title), BitPerfect.PREF, false, () -> {
                if (!BitPerfect.enabled(m)) BitPerfect.clear(m);
                m.root.postDelayed(() -> { if (isAttachedToWindow()) rebuild(); }, 350);
            });
            bp.row(0, 0, BitPerfect.status(m), null, false, null);
            content.addView(bp, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.bp_note)));

            // NPU Assist shortcuts
            Widgets.Group npu = new Widgets.Group(m, m.getString(R.string.npu_group));
            npu.row(Icons.PROSCALER, Ui.ACCENT, m.getString(R.string.psa_title), NpuVideo.summary(m), true, v -> m.push(new ProScalerAssistPage(m)));
            npu.row(Icons.HEADPHONES, Ui.ACCENT, m.getString(R.string.bpa_title), NpuAudio.summary(m), true, v -> m.push(new BitPerfectAssistPage(m)));
            content.addView(npu, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Bit-Perfect Assist (NPU Audio Tuning)
    static final class BitPerfectAssistPage extends Sub {
        BitPerfectAssistPage(MainActivity m) { super(m, R.string.bpa_title); }

        @Override void build() {
            MainActivity m = main;
            boolean supported = NpuAudio.isSupported();
            boolean active = NpuAudio.enabled(m);

            // 1) Current chipset illustration hero card + master toggle
            Widgets.Group mg = new Widgets.Group(m, m.getString(R.string.npu_group));
            LinearLayout hero = new LinearLayout(m);
            hero.setOrientation(LinearLayout.HORIZONTAL);
            hero.setGravity(Gravity.CENTER_VERTICAL);
            hero.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            NpuAudio.ChipSpec curChip = NpuAudio.currentChipSpec();
            hero.addView(new NpuAudio.ChipArtView(m, curChip, NpuAudio.displaySoc(m)), new LinearLayout.LayoutParams(Ui.dp(62), Ui.dp(62)));
            LinearLayout hcol = new LinearLayout(m);
            hcol.setOrientation(LinearLayout.VERTICAL);
            hcol.setPadding(Ui.dp(14), 0, 0, 0);
            hcol.addView(Ui.text(m, NpuAudio.displaySoc(m), 16.5f, Ui.bold, Ui.text()), Widgets.MW);
            TextView stTv = Ui.text(m, NpuAudio.status(m), 12.5f, Ui.regular, supported ? Ui.ACCENT : Ui.text2());
            stTv.setSingleLine(false);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = Ui.dp(3);
            hcol.addView(stTv, stLp);
            hero.addView(hcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mg.addCustom(hero);

            if (supported) {
                toggleRow(mg, Icons.HEADPHONES, m.getString(R.string.bpa_title), NpuAudio.PREF_ON, false, () -> {
                    NpuAudio.apply(m);
                    VideoPlayer.notifyAll0();
                    m.root.postDelayed(() -> { if (isAttachedToWindow()) rebuild(); }, 350);
                });
            } else {
                View r = mg.row(Icons.HEADPHONES, Ui.text3(), m.getString(R.string.bpa_title), m.getString(R.string.eq_state_off), false, null);
                r.setAlpha(0.55f);
            }
            mg.row(Icons.INFO, Ui.ACCENT, m.getString(R.string.bpa_supported_socs), m.getString(R.string.bpa_supported_socs_sub), true, v -> m.push(new SupportedChipsetsPage(m)));
            content.addView(mg, Widgets.MW);

            // 2) Live output audio & Hi-Res tier card
            AudioInfo.OutputSpec spec = AudioInfo.current(m);
            int tier = AudioInfo.hiResTier(m, spec);
            Widgets.Group og = new Widgets.Group(m, m.getString(R.string.bpa_output));
            String outLabel = spec.deviceName.isEmpty() ? m.getString(R.string.this_device) : spec.deviceName;
            String outVal = spec.formatLine().isEmpty() ? (spec.bits + "bit / " + spec.khzText()) : spec.formatLine();
            og.row(AudioInfo.deviceIcon(spec), Ui.ACCENT, outLabel, outVal, false, null);
            int tierRes = tier == AudioInfo.TIER_MASTER ? R.string.hires_master
                    : (tier == AudioInfo.TIER_BLUE ? R.string.hires_blue
                    : (tier == AudioInfo.TIER_GOLD ? R.string.hires_active : R.string.hires_none));
            int tierColor = tier == AudioInfo.TIER_MASTER ? 0xFFA855F7
                    : (tier == AudioInfo.TIER_BLUE ? 0xFF38BDF8
                    : (tier == AudioInfo.TIER_GOLD ? 0xFFD4A017 : Ui.text2()));
            og.row(Icons.WAVE, tierColor, m.getString(R.string.bpa_hires_state), m.getString(tierRes), false, null);
            toggleRow(og, Icons.APPEARANCE, m.getString(R.string.bpa_hires_glow), NpuAudio.PREF_HIRES_GLOW, true, VideoPlayer::notifyAll0);
            content.addView(og, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.bpa_hires_glow_note)));

            // 3) Live frequency response curve + Instant A/B Bypass comparison button
            NpuAudio.CurveView curveView = new NpuAudio.CurveView(m);
            Widgets.Group cg = new Widgets.Group(m, m.getString(R.string.bpa_curve));
            cg.addCustom(curveView);
            if (supported && active) {
                TextView abBtn = Ui.text(m, m.getString(R.string.bpa_ab_hold), 13.5f, Ui.semibold, Ui.ACCENT);
                abBtn.setClickable(true);
                abBtn.setGravity(Gravity.CENTER);
                abBtn.setPadding(Ui.dp(14), Ui.dp(13), Ui.dp(14), Ui.dp(13));
                abBtn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 14));
                LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                abLp.setMargins(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(14));
                abBtn.setOnTouchListener((v, ev) -> {
                    int act = ev.getActionMasked();
                    if (act == MotionEvent.ACTION_DOWN) {
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        NpuAudio.setBypassHold(m, true);
                        abBtn.setText(R.string.bpa_ab_bypassing);
                        abBtn.setBackground(Ui.round(Ui.alpha(0xFFFF9F0A, 0.28f), 14));
                        abBtn.setTextColor(0xFFFF9F0A);
                        stTv.setText(NpuAudio.status(m));
                        curveView.invalidate();
                        return true;
                    } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                        NpuAudio.setBypassHold(m, false);
                        abBtn.setText(R.string.bpa_ab_hold);
                        abBtn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 14));
                        abBtn.setTextColor(Ui.ACCENT);
                        stTv.setText(NpuAudio.status(m));
                        curveView.invalidate();
                        return true;
                    }
                    return true;
                });
                cg.addCustom(abBtn);
                abBtn.setLayoutParams(abLp);
            }
            content.addView(cg, Widgets.MW);

            // 4) Curated profiles
            Widgets.Group pg = new Widgets.Group(m, m.getString(R.string.bpa_profile));
            String curProf = NpuAudio.profile(m);
            for (int i = 0; i < NpuAudio.PROFILES.length; i++) {
                final int idx = i;
                View row = pg.option(m.getString(NpuAudio.PROFILE_NAMES[i]), NpuAudio.PROFILES[i].equals(curProf), supported ? v -> {
                    NpuAudio.setProfile(m, idx);
                    if (!NpuAudio.enabled(m)) NpuAudio.setEnabled(m, true);
                    rebuild();
                } : null);
                if (!supported) row.setAlpha(0.5f);
            }
            if ("custom".equals(curProf)) pg.option(m.getString(R.string.bpa_prof_custom), true, null);
            content.addView(pg, Widgets.MW);

            // 5) Harmonic exciter voicing selector (Studio / Crystal / Warm Tube)
            Widgets.Group hg = new Widgets.Group(m, m.getString(R.string.bpa_harmonics));
            String curH = NpuAudio.harmonics(m);
            for (int i = 0; i < NpuAudio.HARMONICS.length; i++) {
                final String mode = NpuAudio.HARMONICS[i];
                View row = hg.option(m.getString(NpuAudio.HARMONIC_NAMES[i]), mode.equals(curH), (supported && active) ? v -> {
                    NpuAudio.setHarmonics(m, mode);
                    rebuild();
                } : null);
                if (!supported || !active) row.setAlpha(0.5f);
            }
            content.addView(hg, Widgets.MW);

            // 6) Detailed real-time tuning sliders (6 bands + limiter + per-device memory)
            Widgets.Group tg = new Widgets.Group(m, m.getString(R.string.bpa_tuning));
            addAudioSlider(tg, m, R.string.bpa_strength, NpuAudio.PREF_STRENGTH, NpuAudio.strength(m), supported && active, curveView);
            addAudioSlider(tg, m, R.string.bpa_air, NpuAudio.PREF_AIR, NpuAudio.air(m), supported && active, curveView);
            addAudioSlider(tg, m, R.string.bpa_dyn, NpuAudio.PREF_DYN, NpuAudio.dyn(m), supported && active, curveView);
            addAudioSlider(tg, m, R.string.bpa_vocal, NpuAudio.PREF_VOCAL, NpuAudio.vocal(m), supported && active, curveView);
            addAudioSlider(tg, m, R.string.bpa_bass, NpuAudio.PREF_BASS, NpuAudio.bass(m), supported && active, curveView);
            addAudioSlider(tg, m, R.string.bpa_space, NpuAudio.PREF_SPACE, NpuAudio.space(m), supported && active, curveView);
            if (supported) {
                toggleRow(tg, Icons.WAVE, m.getString(R.string.bpa_limiter), NpuAudio.PREF_LIMITER, true, () -> NpuAudio.apply(m, true));
                toggleRow(tg, Icons.DEVICE, m.getString(R.string.bpa_per_device), NpuAudio.PREF_PER_DEVICE, true, null);
            }
            content.addView(tg, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.npu_note)));
        }

        private void addAudioSlider(Widgets.Group g, MainActivity m, int titleRes, String key, int curPct, boolean interactive, NpuAudio.CurveView curve) {
            LinearLayout box = new LinearLayout(m);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(8));
            TextView tv = Ui.text(m, m.getString(titleRes) + " · " + curPct + "%", 14.5f, Ui.medium, Ui.text());
            box.addView(tv);
            Widgets.GlassSlider sl = new Widgets.GlassSlider(m, curPct / 100f, v -> {
                if (!interactive) return;
                int pct = Math.round(v * 100f);
                NpuAudio.setParam(m, key, pct);
                tv.setText(m.getString(titleRes) + " · " + pct + "%");
                if (curve != null) curve.invalidate();
            });
            if (!interactive) {
                box.setAlpha(0.5f);
                sl.setEnabled(false);
            }
            box.addView(sl);
            g.addCustom(box);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            NpuAudio.setBypassHold(main, false);
        }
    }

    // ------------------------------------------------------------------ ProScaler Assist (NPU Video Super-Resolution & HDR Tone Enhancement)
    static final class ProScalerAssistPage extends Sub {
        ProScalerAssistPage(MainActivity m) { super(m, R.string.psa_title); }

        @Override void build() {
            MainActivity m = main;
            boolean supported = NpuVideo.isSupported();
            boolean active = NpuVideo.enabled(m);

            // 1) Current chipset illustration hero card + master toggle
            Widgets.Group mg = new Widgets.Group(m, m.getString(R.string.npu_group));
            LinearLayout hero = new LinearLayout(m);
            hero.setOrientation(LinearLayout.HORIZONTAL);
            hero.setGravity(Gravity.CENTER_VERTICAL);
            hero.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            NpuAudio.ChipSpec curChip = NpuAudio.currentChipSpec();
            hero.addView(new NpuAudio.ChipArtView(m, curChip, NpuAudio.displaySoc(m)), new LinearLayout.LayoutParams(Ui.dp(62), Ui.dp(62)));
            LinearLayout hcol = new LinearLayout(m);
            hcol.setOrientation(LinearLayout.VERTICAL);
            hcol.setPadding(Ui.dp(14), 0, 0, 0);
            hcol.addView(Ui.text(m, NpuAudio.displaySoc(m), 16.5f, Ui.bold, Ui.text()), Widgets.MW);
            TextView stTv = Ui.text(m, NpuVideo.status(m), 12.5f, Ui.regular, supported ? Ui.ACCENT : Ui.text2());
            stTv.setSingleLine(false);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = Ui.dp(3);
            hcol.addView(stTv, stLp);
            hero.addView(hcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mg.addCustom(hero);

            if (supported) {
                toggleRow(mg, Icons.PROSCALER, m.getString(R.string.psa_enable), NpuVideo.PREF_ON, false, () -> {
                    NpuVideo.apply(m, m.playerOverlay.videoTexture());
                    m.root.postDelayed(() -> { if (isAttachedToWindow()) rebuild(); }, 350);
                });
            } else {
                View r = mg.row(Icons.PROSCALER, Ui.text3(), m.getString(R.string.psa_enable), m.getString(R.string.eq_state_off), false, null);
                r.setAlpha(0.55f);
            }
            mg.row(Icons.INFO, Ui.ACCENT, m.getString(R.string.bpa_supported_socs), m.getString(R.string.bpa_supported_socs_sub), true, v -> m.push(new SupportedChipsetsPage(m)));
            content.addView(mg, Widgets.MW);

            // 2) Live Luma & Chroma response curve + Instant A/B Bypass hold button
            NpuVideo.ScalerCurveView curveView = new NpuVideo.ScalerCurveView(m);
            Widgets.Group cg = new Widgets.Group(m, m.getString(R.string.psa_curve));
            cg.addCustom(curveView);
            if (supported && active) {
                TextView abBtn = Ui.text(m, m.getString(R.string.psa_ab_hold), 13.5f, Ui.semibold, Ui.ACCENT);
                abBtn.setClickable(true);
                abBtn.setGravity(Gravity.CENTER);
                abBtn.setPadding(Ui.dp(14), Ui.dp(13), Ui.dp(14), Ui.dp(13));
                abBtn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 14));
                LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                abLp.setMargins(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(14));
                abBtn.setOnTouchListener((v, ev) -> {
                    int act = ev.getActionMasked();
                    if (act == MotionEvent.ACTION_DOWN) {
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        NpuVideo.setBypassHold(m, true);
                        abBtn.setText(R.string.psa_ab_bypassing);
                        abBtn.setBackground(Ui.round(Ui.alpha(0xFFFF9F0A, 0.28f), 14));
                        abBtn.setTextColor(0xFFFF9F0A);
                        stTv.setText(NpuVideo.status(m));
                        curveView.invalidate();
                        return true;
                    } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                        NpuVideo.setBypassHold(m, false);
                        abBtn.setText(R.string.psa_ab_hold);
                        abBtn.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 14));
                        abBtn.setTextColor(Ui.ACCENT);
                        stTv.setText(NpuVideo.status(m));
                        curveView.invalidate();
                        return true;
                    }
                    return true;
                });
                cg.addCustom(abBtn);
                abBtn.setLayoutParams(abLp);
            }
            content.addView(cg, Widgets.MW);

            // 3) Target NPU Engine Mode selector (Detail 4K / HDR Dynamic / Film Soft)
            Widgets.Group rg = new Widgets.Group(m, m.getString(R.string.psa_engine));
            String curEng = NpuVideo.engine(m);
            for (int i = 0; i < NpuVideo.ENGINES.length; i++) {
                final String mode = NpuVideo.ENGINES[i];
                View row = rg.option(m.getString(NpuVideo.ENGINE_NAMES[i]), mode.equals(curEng), (supported && active) ? v -> {
                    NpuVideo.setEngine(m, mode);
                    rebuild();
                } : null);
                if (!supported || !active) row.setAlpha(0.5f);
            }
            content.addView(rg, Widgets.MW);

            // 4) Curated video profiles (Cinema Master / Crystal 4K / Anime & Line / Natural Film)
            Widgets.Group pg = new Widgets.Group(m, m.getString(R.string.psa_profile));
            String curProf = NpuVideo.profile(m);
            for (int i = 0; i < NpuVideo.PROFILES.length; i++) {
                final int idx = i;
                View row = pg.option(m.getString(NpuVideo.PROFILE_NAMES[i]), NpuVideo.PROFILES[i].equals(curProf), supported ? v -> {
                    NpuVideo.setProfile(m, idx);
                    if (!NpuVideo.enabled(m)) NpuVideo.setEnabled(m, true);
                    rebuild();
                } : null);
                if (!supported) row.setAlpha(0.5f);
            }
            if ("custom".equals(curProf)) pg.option(m.getString(R.string.psa_prof_custom), true, null);
            content.addView(pg, Widgets.MW);

            // 5) Detailed NPU video tuning sliders (Strength, Detail, Contrast, HDR, Vibrance, Deband)
            Widgets.Group tg = new Widgets.Group(m, m.getString(R.string.psa_tuning));
            addVideoSlider(tg, m, R.string.psa_strength, NpuVideo.PREF_STRENGTH, NpuVideo.strength(m), supported && active, curveView);
            addVideoSlider(tg, m, R.string.psa_detail, NpuVideo.PREF_DETAIL, NpuVideo.detail(m), supported && active, curveView);
            addVideoSlider(tg, m, R.string.psa_contrast, NpuVideo.PREF_CONTRAST, NpuVideo.contrast(m), supported && active, curveView);
            addVideoSlider(tg, m, R.string.psa_hdr, NpuVideo.PREF_HDR, NpuVideo.hdr(m), supported && active, curveView);
            addVideoSlider(tg, m, R.string.psa_vibrance, NpuVideo.PREF_VIBRANCE, NpuVideo.vibrance(m), supported && active, curveView);
            addVideoSlider(tg, m, R.string.psa_deband, NpuVideo.PREF_DEBAND, NpuVideo.deband(m), supported && active, curveView);
            content.addView(tg, Widgets.MW);
            content.addView(Widgets.caption(m, m.getString(R.string.psa_note)));
        }

        private void addVideoSlider(Widgets.Group g, MainActivity m, int titleRes, String key, int curPct, boolean interactive, NpuVideo.ScalerCurveView curve) {
            LinearLayout box = new LinearLayout(m);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(8));
            TextView tv = Ui.text(m, m.getString(titleRes) + " · " + curPct + "%", 14.5f, Ui.medium, Ui.text());
            box.addView(tv);
            Widgets.GlassSlider sl = new Widgets.GlassSlider(m, curPct / 100f, v -> {
                if (!interactive) return;
                int pct = Math.round(v * 100f);
                NpuVideo.setParam(m, key, pct);
                tv.setText(m.getString(titleRes) + " · " + pct + "%");
                if (curve != null) curve.invalidate();
            });
            if (!interactive) {
                box.setAlpha(0.5f);
                sl.setEnabled(false);
            }
            box.addView(sl);
            g.addCustom(box);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            NpuVideo.setBypassHold(main, false);
        }
    }

    // ------------------------------------------------------------------ Supported Chipsets (25 Flagship SoCs)
    static final class SupportedChipsetsPage extends Sub {
        SupportedChipsetsPage(MainActivity m) { super(m, R.string.bpa_supported_socs); }

        @Override void build() {
            MainActivity m = main;
            NpuAudio.ChipSpec current = NpuAudio.currentChipSpec();
            addVendorGroup(m, "Qualcomm Snapdragon", NpuAudio.VENDOR_QUALCOMM, current);
            addVendorGroup(m, "MediaTek Dimensity", NpuAudio.VENDOR_MEDIATEK, current);
            addVendorGroup(m, "Samsung Exynos", NpuAudio.VENDOR_SAMSUNG, current);
            addVendorGroup(m, "Google Tensor", NpuAudio.VENDOR_GOOGLE, current);
        }

        private void addVendorGroup(MainActivity m, String title, int vendor, NpuAudio.ChipSpec current) {
            Widgets.Group g = new Widgets.Group(m, title);
            for (NpuAudio.ChipSpec cs : NpuAudio.SUPPORTED_CHIPS) {
                if (cs.vendor != vendor) continue;
                LinearLayout row = new LinearLayout(m);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(16), Ui.dp(10));
                boolean showArt = cs.drawableRes != 0 || !"S5E9975".equals(cs.id);
                if (showArt) {
                    row.addView(new NpuAudio.ChipArtView(m, cs, cs.id), new LinearLayout.LayoutParams(Ui.dp(50), Ui.dp(50)));
                }
                LinearLayout col = new LinearLayout(m);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(showArt ? Ui.dp(12) : Ui.dp(2), 0, Ui.dp(8), 0);
                col.addView(Ui.text(m, cs.displayName(m), 15f, Ui.semibold, Ui.text()), Widgets.MW);
                TextView sub = Ui.text(m, cs.id + " · " + cs.npu, 12f, Ui.regular, Ui.text2());
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.topMargin = Ui.dp(2);
                col.addView(sub, slp);
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (current != null && current.id.equals(cs.id)) {
                    TextView tag = Ui.text(m, m.getString(R.string.bpa_current_device), 11.5f, Ui.semibold, Ui.ACCENT);
                    tag.setPadding(Ui.dp(9), Ui.dp(4), Ui.dp(9), Ui.dp(4));
                    tag.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, 0.14f), 100));
                    row.addView(tag, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                g.addCustom(row);
            }
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Subtitles
    static final class SubtitleSettingsPage extends Sub {
        SubtitleSettingsPage(MainActivity m) { super(m, R.string.set_subtitles); }

        @Override void build() {
            MainActivity m = main;
            SharedPreferences p = MainActivity.prefs(m);
            Widgets.Group g = new Widgets.Group(m, null);
            toggleRow(g, Icons.SUBTITLE, m.getString(R.string.auto_sub), "auto_sub", true, null);

            LinearLayout szBox = new LinearLayout(m);
            szBox.setOrientation(LinearLayout.VERTICAL);
            szBox.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(10));
            TextView szVal = Ui.text(m, "글자 크기 · " + Math.round(Subtitle.textSizeSp) + "sp", 15, Ui.medium, Ui.text());
            szBox.addView(szVal);
            Widgets.GlassSlider szSlider = new Widgets.GlassSlider(m, (Subtitle.textSizeSp - 12f) / 28f, val -> {
                Subtitle.textSizeSp = 12f + val * 28f;
                Subtitle.saveStyle(p);
                szVal.setText("글자 크기 · " + Math.round(Subtitle.textSizeSp) + "sp");
            });
            szBox.addView(szSlider);
            g.addCustom(szBox);

            LinearLayout bgBox = new LinearLayout(m);
            bgBox.setOrientation(LinearLayout.VERTICAL);
            bgBox.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(10));
            TextView bgVal = Ui.text(m, "자막 배경 불투명도 · " + Math.round(Subtitle.bgAlpha * 100) + "%", 15, Ui.medium, Ui.text());
            bgBox.addView(bgVal);
            Widgets.GlassSlider bgSlider = new Widgets.GlassSlider(m, Subtitle.bgAlpha, val -> {
                Subtitle.bgAlpha = val;
                Subtitle.saveStyle(p);
                bgVal.setText("자막 배경 불투명도 · " + Math.round(val * 100) + "%");
            });
            bgBox.addView(bgSlider);
            g.addCustom(bgBox);
            content.addView(g, Widgets.MW);

            Widgets.Group webG = new Widgets.Group(m, m.getString(R.string.sub_web_search));
            String osKey = SecureVault.get(m, p, "opensubtitles_api_key");
            webG.row(Icons.GLOBE, Ui.ACCENT, m.getString(R.string.opensubtitles_key), osKey.isEmpty() ? "미설정" : "설정됨", true, v -> {
                LinearLayout form = new LinearLayout(m);
                form.setOrientation(LinearLayout.VERTICAL);
                EditText et = Widgets.formField(m, m.getString(R.string.opensubtitles_key), "OpenSubtitles REST API 키 (선택)", osKey,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, form);
                Widgets.showFormSheet(m, m.getString(R.string.opensubtitles_key), form, m.getString(R.string.save), () -> {
                    SecureVault.put(m, p, "opensubtitles_api_key", et.getText().toString().trim());
                    rebuild();
                });
            });
            String sdKey = SecureVault.get(m, p, "subdl_api_key");
            webG.row(Icons.SEARCH, Ui.ACCENT, m.getString(R.string.subdl_key), sdKey.isEmpty() ? "미설정" : "설정됨", true, v -> {
                LinearLayout form = new LinearLayout(m);
                form.setOrientation(LinearLayout.VERTICAL);
                EditText et = Widgets.formField(m, m.getString(R.string.subdl_key), "Subdl API 키 (선택)", sdKey,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, form);
                Widgets.showFormSheet(m, m.getString(R.string.subdl_key), form, m.getString(R.string.save), () -> {
                    SecureVault.put(m, p, "subdl_api_key", et.getText().toString().trim());
                    rebuild();
                });
            });
            content.addView(webG, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ AI Subtitles
    static final class AiSettingsPage extends Sub {
        AiSettingsPage(MainActivity m) { super(m, R.string.set_ai); }

        @Override void build() {
            MainActivity m = main;
            SharedPreferences p = MainActivity.prefs(m);
            String curEngine = p.getString("ai_engine", AiSubtitle.ENGINE_GEMINI);
            String curEngineName = AiSubtitle.ENGINE_NAMES[0];
            int curIdx = 0;
            for (int i = 0; i < AiSubtitle.ENGINE_KEYS.length; i++) {
                if (AiSubtitle.ENGINE_KEYS[i].equals(curEngine)) {
                    curEngineName = AiSubtitle.ENGINE_NAMES[i];
                    curIdx = i;
                }
            }
            final int selEngine = curIdx;

            Widgets.Group g = new Widgets.Group(m, "AI 자막 엔진 및 API");
            g.row(Icons.AI, Ui.ACCENT, m.getString(R.string.ai_engine), curEngineName, true, v -> {
                Widgets.pickSheet(m, v, m.getString(R.string.ai_engine), AiSubtitle.ENGINE_NAMES, selEngine, i -> {
                    p.edit().putString("ai_engine", AiSubtitle.ENGINE_KEYS[i]).apply();
                    rebuild();
                });
            });

            String key = SecureVault.get(m, p, "ai_api_key");
            String masked = key.isEmpty() ? "미설정" : ("••••••••" + (key.length() > 4 ? key.substring(key.length() - 4) : ""));
            g.row(Icons.LOCK, Ui.ACCENT, m.getString(R.string.ai_api_key), masked, true, v -> {
                LinearLayout form = new LinearLayout(m);
                form.setOrientation(LinearLayout.VERTICAL);
                EditText et = Widgets.formField(m, m.getString(R.string.ai_api_key), m.getString(R.string.ai_api_key_hint),
                        key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, form);
                Widgets.showFormSheet(m, m.getString(R.string.ai_api_key), form, m.getString(R.string.save), () -> {
                    SecureVault.put(m, p, "ai_api_key", et.getText().toString().trim());
                    rebuild();
                });
            });

            if (AiSubtitle.ENGINE_CUSTOM.equals(curEngine)) {
                String url = p.getString("ai_custom_url", "");
                g.row(Icons.LINK, Ui.ACCENT, m.getString(R.string.ai_custom_url), url.isEmpty() ? "미설정" : url, true, v -> {
                    LinearLayout form = new LinearLayout(m);
                    form.setOrientation(LinearLayout.VERTICAL);
                    EditText et = Widgets.formField(m, m.getString(R.string.ai_custom_url),
                            "https://whisper.example.com/v1/audio/transcriptions", url,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, form);
                    Widgets.showFormSheet(m, m.getString(R.string.ai_custom_url), form, m.getString(R.string.save), () -> {
                        String u = et.getText().toString().trim();
                        if (!u.isEmpty() && !AiSubtitle.isSafeCustomEndpoint(u)) {
                            Toast.makeText(m, "HTTPS URL(또는 로컬호스트)만 사용할 수 있습니다.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        p.edit().putString("ai_custom_url", u).apply();
                        rebuild();
                    });
                });
            }

            String src = p.getString("ai_src_lang", "auto");
            String dst = p.getString("ai_dst_lang", "ko");
            g.row(Icons.GLOBE, Ui.ACCENT, m.getString(R.string.ai_source_lang), langLabel(m, src), true, v -> pickLang(m, "ai_src_lang", R.string.ai_source_lang));
            g.row(Icons.SUBTITLE, Ui.ACCENT, m.getString(R.string.ai_target_lang), langLabel(m, dst), true, v -> pickLang(m, "ai_target_lang", R.string.ai_target_lang));
            content.addView(g, Widgets.MW);

            content.addView(Widgets.caption(m, "영상의 음성을 분석해 SRT 자막을 자동으로 만듭니다."));
        }

        private void pickLang(MainActivity m, String prefKey, int titleRes) {
            String cur = MainActivity.prefs(m).getString(prefKey, "auto");
            int sel = 0;
            String[] labels = new String[AiSubtitle.LANG_LABELS.length];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = m.getString(AiSubtitle.LANG_LABELS[i]);
                if (AiSubtitle.LANG_KEYS[i].equals(cur)) sel = i;
            }
            Widgets.pickSheet(m, null, m.getString(titleRes), labels, sel, i -> {
                MainActivity.prefs(m).edit().putString(prefKey, AiSubtitle.LANG_KEYS[i]).apply();
                rebuild();
            });
        }

        private String langLabel(Context c, String key) {
            for (int i = 0; i < AiSubtitle.LANG_KEYS.length; i++) {
                if (AiSubtitle.LANG_KEYS[i].equals(key)) return c.getString(AiSubtitle.LANG_LABELS[i]);
            }
            return c.getString(R.string.ai_lang_auto);
        }
    }

    // ------------------------------------------------------------------ Storage
    static final class StoragePage extends Sub {
        StoragePage(MainActivity m) { super(m, R.string.set_storage); }

        @Override void build() {
            MainActivity m = main;
            long aiBytes = 0;
            for (File f : AiSubtitle.listGenerated(m)) aiBytes += f.length();
            Widgets.Group g = new Widgets.Group(m, null);
            g.row(Icons.AI, Ui.ACCENT, m.getString(R.string.ai_subtitles), Ui.size(aiBytes), true, v -> m.openLibrarySection("ai_subs"));
            g.row(Icons.STORAGE, Ui.ACCENT, m.getString(R.string.clear_cache), null, false, v -> {
                VideoLibrary.clearThumbCache();
                Toast.makeText(m, R.string.cache_cleared, Toast.LENGTH_SHORT).show();
            });
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ About
    static final class AboutPage extends Sub {
        AboutPage(MainActivity m) { super(m, R.string.set_about); }

        @Override void build() {
            MainActivity m = main;
            Widgets.Group g = new Widgets.Group(m, null);
            g.row(Icons.VIDEO, Ui.ACCENT, m.getString(R.string.app_name), "버전 " + MainActivity.VERSION, false, null);
            g.row(Icons.DOWNLOAD, Ui.ACCENT, m.getString(R.string.upd_check), MainActivity.VERSION, true, v -> {
                Toast.makeText(m, R.string.upd_checking, Toast.LENGTH_SHORT).show();
                Updater.check((rel, err) -> {
                    if (m.isFinishing() || m.isDestroyed()) return;
                    if (err != null) Toast.makeText(m, m.getString(R.string.upd_failed) + " (" + err + ")", Toast.LENGTH_LONG).show();
                    else if (rel == null) Toast.makeText(m, R.string.upd_latest, Toast.LENGTH_SHORT).show();
                    else Updater.offer(m, rel);
                });
            });
            toggleRow(g, Icons.CLOCK, m.getString(R.string.upd_auto), Updater.PREF_AUTO, true, null);
            g.row(Icons.PROSCALER, Ui.ACCENT, m.getString(R.string.psa_title), NpuAudio.displaySoc(m), true, v -> m.push(new ProScalerAssistPage(m)));
            g.row(Icons.HEADPHONES, Ui.ACCENT, m.getString(R.string.bpa_title), NpuAudio.displaySoc(m), true, v -> m.push(new BitPerfectAssistPage(m)));
            g.row(Icons.SUBTITLE, Ui.ACCENT, "지원 자막 포맷", "SRT · VTT · ASS · SSA · SMI", false, null);
            g.row(Icons.AI, Ui.ACCENT, "AI 자막 엔진", "Gemini · OpenAI · Groq · Custom", false, null);
            content.addView(g, Widgets.MW);
        }
    }
}
