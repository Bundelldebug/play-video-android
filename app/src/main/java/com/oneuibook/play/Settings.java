package com.oneuibook.play;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Settings screens: General, Appearance, Playback, Subtitles, AI Subtitles, Storage, About. */
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
            Widgets.Group g = new Widgets.Group(m, null);
            String lang = p.getString("lang", "system");
            String langLbl = "ko".equals(lang) ? "한국어" : ("en".equals(lang) ? "English" : ("ja".equals(lang) ? "日本語" : m.getString(R.string.lang_system)));
            g.row(Icons.GLOBE, Ui.ACCENT, m.getString(R.string.language), langLbl, true, v -> {
                String[] keys = {"system", "ko", "en", "ja"};
                String[] labels = {m.getString(R.string.lang_system), "한국어", "English", "日本語"};
                new AlertDialog.Builder(m).setTitle(R.string.language).setItems(labels, (d, i) -> {
                    p.edit().putString("lang", keys[i]).apply();
                    m.recreate();
                }).show();
            });

            String uf = p.getString("unfold_ui", "fold");
            g.row(Icons.SIDEBAR, Ui.ACCENT, m.getString(R.string.unfold_ui),
                    m.getString("tablet".equals(uf) ? R.string.unfold_tablet : R.string.unfold_fold), true, v -> {
                String[] keys = {"fold", "tablet"};
                String[] labels = {m.getString(R.string.unfold_fold), m.getString(R.string.unfold_tablet)};
                new AlertDialog.Builder(m).setTitle(R.string.unfold_ui).setItems(labels, (d, i) -> {
                    p.edit().putString("unfold_ui", keys[i]).apply();
                    m.recreate();
                }).show();
            });
            content.addView(g, Widgets.MW);
        }
    }

    // ------------------------------------------------------------------ Appearance
    static final class AppearancePage extends Sub {
        AppearancePage(MainActivity m) { super(m, R.string.set_appearance); }

        @Override void build() {
            MainActivity m = main;
            SharedPreferences p = MainActivity.prefs(m);
            Widgets.Group g = new Widgets.Group(m, null);

            String theme = p.getString("theme", "system");
            String tLbl = "dark".equals(theme) ? m.getString(R.string.theme_dark)
                    : ("light".equals(theme) ? m.getString(R.string.theme_light) : m.getString(R.string.theme_system));
            g.row(Icons.APPEARANCE, Ui.ACCENT, m.getString(R.string.theme), tLbl, true, v -> {
                String[] keys = {"system", "light", "dark"};
                String[] labels = {m.getString(R.string.theme_system), m.getString(R.string.theme_light), m.getString(R.string.theme_dark)};
                new AlertDialog.Builder(m).setTitle(R.string.theme).setItems(labels, (d, i) -> {
                    p.edit().putString("theme", keys[i]).apply();
                    m.recreate();
                }).show();
            });

            String style = p.getString("ui_style", "prism");
            g.row(Icons.APPEARANCE, Ui.ACCENT, m.getString(R.string.style_title),
                    m.getString("oneui".equals(style) ? R.string.style_g : R.string.style_a), true, v -> {
                String[] keys = {"prism", "oneui"};
                String[] labels = {m.getString(R.string.style_a), m.getString(R.string.style_g)};
                new AlertDialog.Builder(m).setTitle(R.string.style_title).setItems(labels, (d, i) -> {
                    p.edit().putString("ui_style", keys[i]).apply();
                    m.recreate();
                }).show();
            });

            // Accent color picker swatches
            LinearLayout swBox = new LinearLayout(m);
            swBox.setOrientation(LinearLayout.VERTICAL);
            swBox.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
            swBox.addView(Ui.text(m, m.getString(R.string.accent_color), 16, Ui.medium, Ui.text()));
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, Ui.dp(12), 0, Ui.dp(4));
            String curAcc = Ui.accentKey(p);
            for (int i = 0; i < Ui.ACCENT_KEYS.length; i++) {
                final String key = Ui.ACCENT_KEYS[i];
                int col = Ui.resolveAccent(m, key);
                View dot = new View(m);
                GradientDrawable gd = Ui.round(col, 100);
                if (key.equals(curAcc)) gd.setStroke(Ui.dp(3), Ui.text());
                dot.setBackground(gd);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30));
                dlp.rightMargin = Ui.dp(10);
                dot.setOnClickListener(vv -> {
                    p.edit().putString("accent_color", key).apply();
                    m.recreate();
                });
                row.addView(dot, dlp);
            }
            swBox.addView(row);
            g.addCustom(swBox);

            // Glass transparency slider
            LinearLayout glBox = new LinearLayout(m);
            glBox.setOrientation(LinearLayout.VERTICAL);
            glBox.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(10));
            TextView glVal = Ui.text(m, m.getString(R.string.glass_transparency) + " · " + Math.round(Ui.glassT * 100) + "%", 15, Ui.medium, Ui.text());
            glBox.addView(glVal);
            Widgets.Slider glSlider = new Widgets.Slider(m, Ui.glassT);
            glSlider.change = (val, u) -> {
                Ui.glassT = val;
                p.edit().putFloat("glass_t", val).apply();
                glVal.setText(m.getString(R.string.glass_transparency) + " · " + Math.round(val * 100) + "%");
                m.dock.invalidate();
                m.header.invalidate();
            };
            glBox.addView(glSlider);
            g.addCustom(glBox);

            content.addView(g, Widgets.MW);
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
            toggleRow(g, Icons.FORWARD, m.getString(R.string.sponsorblock), "sb_enabled", true, null);
            String rot = p.getString("auto_rotate", "sensor");
            String rotLbl = "video".equals(rot) ? m.getString(R.string.auto_rotate_video)
                    : ("portrait".equals(rot) ? m.getString(R.string.auto_rotate_portrait)
                    : ("landscape".equals(rot) ? m.getString(R.string.auto_rotate_landscape)
                    : m.getString(R.string.auto_rotate_sensor)));
            g.row(Icons.ROTATE, Ui.ACCENT, m.getString(R.string.auto_rotate), rotLbl, true, v -> {
                String[] keys = {"sensor", "video", "portrait", "landscape"};
                String[] labels = {m.getString(R.string.auto_rotate_sensor), m.getString(R.string.auto_rotate_video),
                        m.getString(R.string.auto_rotate_portrait), m.getString(R.string.auto_rotate_landscape)};
                new AlertDialog.Builder(m).setTitle(R.string.auto_rotate).setItems(labels, (d, i) -> {
                    p.edit().putString("auto_rotate", keys[i]).apply();
                    rebuild();
                }).show();
            });
            int dt = p.getInt("double_tap_sec", 10);
            g.row(Icons.FORWARD, Ui.ACCENT, m.getString(R.string.double_tap_sec), dt + "초", true, v -> {
                int[] secs = {5, 10, 15, 30};
                String[] labels = {"5초", "10초", "15초", "30초"};
                new AlertDialog.Builder(m).setTitle(R.string.double_tap_sec).setItems(labels, (d, i) -> {
                    p.edit().putInt("double_tap_sec", secs[i]).apply();
                    rebuild();
                }).show();
            });
            g.row(Icons.EQ, Ui.ACCENT, m.getString(R.string.equalizer),
                    AudioFx.PRESET_NAMES[Math.max(0, Math.min(AudioFx.PRESET_NAMES.length - 1, AudioFx.presetIndex))],
                    true, v -> m.playerOverlay.showEqSheet(m.header.pillInRoot()));
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
            Widgets.Slider szSlider = new Widgets.Slider(m, (Subtitle.textSizeSp - 12f) / 28f);
            szSlider.change = (val, u) -> {
                Subtitle.textSizeSp = 12f + val * 28f;
                Subtitle.saveStyle(p);
                szVal.setText("글자 크기 · " + Math.round(Subtitle.textSizeSp) + "sp");
            };
            szBox.addView(szSlider);
            g.addCustom(szBox);

            LinearLayout bgBox = new LinearLayout(m);
            bgBox.setOrientation(LinearLayout.VERTICAL);
            bgBox.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(10));
            TextView bgVal = Ui.text(m, "자막 배경 불투명도 · " + Math.round(Subtitle.bgAlpha * 100) + "%", 15, Ui.medium, Ui.text());
            bgBox.addView(bgVal);
            Widgets.Slider bgSlider = new Widgets.Slider(m, Subtitle.bgAlpha);
            bgSlider.change = (val, u) -> {
                Subtitle.bgAlpha = val;
                Subtitle.saveStyle(p);
                bgVal.setText("자막 배경 불투명도 · " + Math.round(val * 100) + "%");
            };
            bgBox.addView(bgSlider);
            g.addCustom(bgBox);

            content.addView(g, Widgets.MW);
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
            for (int i = 0; i < AiSubtitle.ENGINE_KEYS.length; i++) {
                if (AiSubtitle.ENGINE_KEYS[i].equals(curEngine)) curEngineName = AiSubtitle.ENGINE_NAMES[i];
            }

            Widgets.Group g = new Widgets.Group(m, "AI 자막 엔진 및 API");
            g.row(Icons.AI, Ui.ACCENT, m.getString(R.string.ai_engine), curEngineName, true, v -> {
                new AlertDialog.Builder(m).setTitle(R.string.ai_engine).setItems(AiSubtitle.ENGINE_NAMES, (d, i) -> {
                    p.edit().putString("ai_engine", AiSubtitle.ENGINE_KEYS[i]).apply();
                    rebuild();
                }).show();
            });

            String key = p.getString("ai_api_key", "");
            String masked = key.isEmpty() ? "미설정" : ("••••••••" + (key.length() > 4 ? key.substring(key.length() - 4) : ""));
            g.row(Icons.LOCK, Ui.ACCENT, m.getString(R.string.ai_api_key), masked, true, v -> {
                LinearLayout form = new LinearLayout(m);
                form.setOrientation(LinearLayout.VERTICAL);
                EditText et = Widgets.formField(m, m.getString(R.string.ai_api_key), m.getString(R.string.ai_api_key_hint),
                        key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, form);
                Widgets.showFormSheet(m, m.getString(R.string.ai_api_key), form, m.getString(R.string.save), () -> {
                    p.edit().putString("ai_api_key", et.getText().toString().trim()).apply();
                    rebuild();
                });
            });

            if (AiSubtitle.ENGINE_CUSTOM.equals(curEngine)) {
                String url = p.getString("ai_custom_url", "");
                g.row(Icons.LINK, Ui.ACCENT, m.getString(R.string.ai_custom_url), url.isEmpty() ? "미설정" : url, true, v -> {
                    LinearLayout form = new LinearLayout(m);
                    form.setOrientation(LinearLayout.VERTICAL);
                    EditText et = Widgets.formField(m, m.getString(R.string.ai_custom_url),
                            "http://192.168.0.10:8000/v1/audio/transcriptions", url,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, form);
                    Widgets.showFormSheet(m, m.getString(R.string.ai_custom_url), form, m.getString(R.string.save), () -> {
                        p.edit().putString("ai_custom_url", et.getText().toString().trim()).apply();
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
            String[] labels = new String[AiSubtitle.LANG_LABELS.length];
            for (int i = 0; i < labels.length; i++) labels[i] = m.getString(AiSubtitle.LANG_LABELS[i]);
            new AlertDialog.Builder(m).setTitle(titleRes).setItems(labels, (d, i) -> {
                MainActivity.prefs(m).edit().putString(prefKey, AiSubtitle.LANG_KEYS[i]).apply();
                rebuild();
            }).show();
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
            g.row(Icons.REFRESH, Ui.ACCENT, m.getString(R.string.upd_check), null, true, v -> {
                Toast.makeText(m, R.string.upd_checking, Toast.LENGTH_SHORT).show();
                Updater.check((rel, err) -> {
                    if (m.isFinishing() || m.isDestroyed()) return;
                    if (err != null) Toast.makeText(m, m.getString(R.string.upd_failed) + " (" + err + ")", Toast.LENGTH_LONG).show();
                    else if (rel == null) Toast.makeText(m, R.string.upd_latest, Toast.LENGTH_SHORT).show();
                    else Updater.offer(m, rel);
                });
            });
            toggleRow(g, Icons.CLOCK, m.getString(R.string.upd_auto), Updater.PREF_AUTO, true, null);
            g.row(Icons.SUBTITLE, Ui.ACCENT, "지원 자막 포맷", "SRT · VTT · ASS · SSA · SMI", false, null);
            g.row(Icons.AI, Ui.ACCENT, "AI 자막 엔진", "Gemini · OpenAI · Groq · Custom", false, null);
            content.addView(g, Widgets.MW);
        }
    }
}
