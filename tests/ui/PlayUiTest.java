package com.oneuibook.play;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class PlayUiTest {
    private File outDir;

    @Before public void setup() {
        outDir = new File(System.getProperty("play.output", "."));
        outDir.mkdirs();
        VideoLibrary.allVideos.clear();
        VideoLibrary.folders.clear();
        VideoLibrary.history.clear();
        VideoLibrary.resumePos.clear();
        // Seed fixture videos for visual verification
        String[] titles = {"인터스텔라 4K UHD", "제주도 드론 브이로그", "시네마 샘플 영상", "클래식 콘서트 실황", "일본 여행 하이라이트", "다큐멘터리 에피소드 1"};
        String[] folders = {"영화", "카메라", "다운로드", "영화", "카메라", "다운로드"};
        long[] durs = {10140_000L, 425_000L, 184_000L, 3720_000L, 612_000L, 2850_000L};
        int[] ws = {3840, 1920, 2560, 1920, 3840, 1280};
        int[] hs = {2160, 1080, 1440, 1080, 2160, 720};
        for (int i = 0; i < titles.length; i++) {
            VideoLibrary.Video v = new VideoLibrary.Video();
            v.id = i + 1;
            v.uri = "content://media/external/video/media/" + (i + 1);
            v.title = titles[i];
            v.folder = folders[i];
            v.durationMs = durs[i];
            v.sizeBytes = (i + 1) * 185_000_000L;
            v.dateAddedSec = 1760000000L - i * 3600L;
            v.width = ws[i];
            v.height = hs[i];
            v.hasSubtitle = (i % 2 == 0);
            VideoLibrary.allVideos.add(v);
        }
        VideoLibrary.rebuildFolders();
        VideoLibrary.resumePos.put(VideoLibrary.allVideos.get(0).key(), 3600_000L);
        VideoLibrary.resumePos.put(VideoLibrary.allVideos.get(1).key(), 180_000L);
        VideoLibrary.history.add(VideoLibrary.allVideos.get(0).key());
        VideoLibrary.history.add(VideoLibrary.allVideos.get(1).key());
    }

    private void saveShot(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        v.draw(c);
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, name))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
    }

    @Test public void subtitleParsersAndSync() {
        String srt = "1\n00:00:01,000 --> 00:00:04,500\n안녕하세요 <b>플레이</b>\n\n2\n00:00:05,000 --> 00:00:08,000\n두 번째 줄\n";
        Subtitle.Track t1 = Subtitle.parse("test.srt", "", srt);
        assertEquals(2, t1.cues.size());
        assertEquals("안녕하세요 플레이", t1.textAt(2000, 0));
        assertNull(t1.textAt(2000, 1500)); // shifted by +1.5s -> t = 500ms
        assertEquals("안녕하세요 플레이", t1.textAt(2600, 1500));

        String ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:02.00,0:00:05.50,Default,,0,0,0,,{\\i1}Hello\\NWorld\n";
        Subtitle.Track t2 = Subtitle.parse("test.ass", "", ass);
        assertEquals(1, t2.cues.size());
        assertEquals("Hello\nWorld", t2.textAt(3000, 0));

        String smi = "<SAMI><BODY><SYNC Start=1000><P>첫 문장<br>둘째 줄<SYNC Start=3500><P>&nbsp;</BODY></SAMI>";
        Subtitle.Track t3 = Subtitle.parse("test.smi", "", smi);
        assertEquals(1, t3.cues.size());
        assertEquals("첫 문장\n둘째 줄", t3.textAt(2000, 0));

        String fenced = "```srt\n1\n00:00:00,500 --> 00:00:02,000\nAI 자막 테스트\n```";
        Subtitle.Track t4 = Subtitle.parse("ai.srt", "", AiSubtitle.normalizeSrt(fenced));
        assertEquals(1, t4.cues.size());
        assertEquals("AI 자막 테스트", t4.textAt(1000, 0));

        byte[] wav = AiSubtitle.wrapWav(new byte[320], 16000, 1, 16);
        assertEquals(44 + 320, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test public void launcherIconRenders() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        Drawable icon = ctx.getDrawable(R.mipmap.ic_launcher);
        assertNotNull(icon);
        Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        icon.setBounds(0, 0, 256, 256);
        icon.draw(c);
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, "icon.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
    }

    @Test @Config(qualifiers = "ko-rKR-w412dp-h915dp-xxhdpi")
    public void phoneHomeAndPlayerOverlay() throws Exception {
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        assertEquals("플레이", act.getString(R.string.app_name));
        assertEquals("9.5.9", MainActivity.VERSION);
        assertNotNull(YouTubeHub.buildSapisidHash("SAPISID=test_token_123", "https://www.youtube.com"));
        assertTrue(YouTubeHub.buildSapisidHash("SAPISID=test_token_123", "https://www.youtube.com").startsWith("SAPISIDHASH "));
        assertEquals("Interstellar 2014", OnlineSubtitle.cleanQuery("[Sub] Interstellar.2014.2160p.4K.UHD.BluRay.x265.mkv"));
        assertEquals(25, NpuAudio.SUPPORTED_CHIPS.length);
        assertNotNull(NpuAudio.displaySoc(act));
        assertEquals(12, NpuAudio.computeBandGains(act).length);
        assertEquals(12, NpuVideo.computeResponseCurve(act).length);
        assertEquals("dQw4w9WgXcQ", YouTubeHub.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTubeHub.extractVideoId("https://youtu.be/dQw4w9WgXcQ?t=42"));
        saveShot(act.root, 1236, 2745, "phone-home-light.png");

        // Open Bit-Perfect Assist & ProScaler Assist pages and capture screenshots
        NpuAudio.testSupportedOverride = true;
        NpuAudio.setEnabled(act, true);
        NpuVideo.setEnabled(act, true);
        Settings.BitPerfectAssistPage bpaPage = new Settings.BitPerfectAssistPage(act);
        bpaPage.show();
        saveShot(bpaPage, 1236, 2745, "phone-bitperfect-assist.png");

        Settings.ProScalerAssistPage psaPage = new Settings.ProScalerAssistPage(act);
        psaPage.show();
        saveShot(psaPage, 1236, 2745, "phone-proscaler-assist.png");

        YouTubeHub.YouTubePage ytPage = new YouTubeHub.YouTubePage(act);
        ytPage.show();
        saveShot(ytPage, 1236, 2745, "phone-youtube-hub.png");
        NpuAudio.testSupportedOverride = null;

        // Collapsed dock with mini video player tucked between tab circle and search circle
        VideoPlayer.current = VideoLibrary.allVideos.get(0);
        VideoPlayer.durationMs = VideoPlayer.current.durationMs;
        act.dock.onPlayer();
        act.dock.miniShow = 1f;
        act.dock.collapse = 1f;
        saveShot(act.root, 1236, 2745, "phone-home-collapsed.png");
        act.dock.collapse = 0f;

        // Search mode with filter card + aligned typing gradient hint
        act.onSearch();
        act.dock.search = 1f;
        saveShot(act.root, 1236, 2745, "phone-search.png");
        act.onExitSearch();
        act.dock.search = 0f;

        // Switch to Settings tab
        act.dock.select(3, false);
        act.onTab(3, false);
        saveShot(act.root, 1236, 2745, "phone-settings.png");

        // Open full-screen video player overlay (A style = floating glass capsules) in bar-phone portrait
        VideoPlayer.primarySub = Subtitle.parse("demo.srt", "", "1\n00:00:00,000 --> 00:10:00,000\n플레이 9.5.2 글래스 시네마 자막\n");
        act.playerOverlay.open(false);
        saveShot(act.root, 1236, 2745, "phone-player.png");
    }

    @Test @Config(qualifiers = "ko-rKR-w412dp-h915dp-xxhdpi")
    public void phoneGStylePlayerOverlay() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        MainActivity.prefs(ctx).edit().putString("theme", "light").putString("ui_style", "oneui").apply();
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        VideoPlayer.current = VideoLibrary.allVideos.get(0);
        VideoPlayer.durationMs = VideoPlayer.current.durationMs;
        VideoPlayer.primarySub = Subtitle.parse("demo.srt", "", "1\n00:00:00,000 --> 00:10:00,000\nG 스타일 플레이어 자막\n");
        act.playerOverlay.open(false);
        saveShot(act.root, 1236, 2745, "phone-player-g.png");
    }

    @Test @Config(qualifiers = "ko-rKR-w720dp-h760dp-xxhdpi")
    public void foldableUnfoldedRail() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        MainActivity.prefs(ctx).edit().putString("theme", "dark").putString("ui_style", "oneui").putString("unfold_ui", "fold").apply();
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        assertTrue(Dock.railFor(2160, 2280));
        saveShot(act.root, 2160, 2280, "foldable-dark-gstyle.png");
    }

    @Test @Config(qualifiers = "ko-rKR-w1100dp-h720dp-xhdpi")
    public void tabletSidebarLayout() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        MainActivity.prefs(ctx).edit().putString("theme", "light").putString("ui_style", "prism").apply();
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        assertTrue(act.tabletUi());
        act.openSidebar(false);
        saveShot(act.root, 2200, 1440, "tablet-sidebar.png");
    }
}
