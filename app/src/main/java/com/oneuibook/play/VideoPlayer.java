package com.oneuibook.play;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Core video playback controller:
 * MediaPlayer + Surface binding, speed (0.25x..4.0x), aspect ratio, A-B repeat,
 * embedded audio/subtitle tracks, dual external subtitles with sync offset,
 * sleep timer, background audio playback, and MediaSession foreground notification.
 */
final class VideoPlayer {
    private VideoPlayer() {}

    static final int ASPECT_FIT = 0, ASPECT_FILL = 1, ASPECT_STRETCH = 2,
            ASPECT_16_9 = 3, ASPECT_4_3 = 4, ASPECT_21_9 = 5, ASPECT_1_1 = 6;
    static final int REPEAT_OFF = 0, REPEAT_ONE = 1, REPEAT_ALL = 2;

    static final List<Runnable> listeners = new ArrayList<>();
    static Context app;

    static VideoLibrary.Video current;
    static final List<VideoLibrary.Video> queue = new ArrayList<>();
    static int queueIndex = -1;

    static MediaPlayer mp;
    static Surface surface;
    static boolean prepared;
    static boolean playing;
    static boolean backgroundAudio;
    static int videoW, videoH;
    static long durationMs;
    static float speed = 1.0f;
    static int aspectMode = ASPECT_FIT;
    static int repeatMode = REPEAT_OFF;

    // A-B repeat
    static long repeatA = -1, repeatB = -1;

    // Subtitles
    static Subtitle.Track primarySub;
    static Subtitle.Track secondarySub;
    static boolean subEnabled = true;
    static long subOffsetMs = 0;

    // Sleep timer
    static long sleepUntilMs = -1;
    static boolean sleepAfterCurrent;

    // Track info
    static final List<TrackItem> audioTracks = new ArrayList<>();
    static final List<TrackItem> embeddedSubTracks = new ArrayList<>();
    static int selectedAudioTrack = -1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean tickRunning;

    static final class TrackItem {
        final int index;
        final String label;
        TrackItem(int index, String label) { this.index = index; this.label = label; }
    }

    static void notifyAll0() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
        if (app != null) PlaybackService.update(app);
    }

    static void play(Context c, VideoLibrary.Video v, List<VideoLibrary.Video> list) {
        if (v == null) return;
        app = c.getApplicationContext();
        saveCurrentResume();
        queue.clear();
        if (list != null && !list.isEmpty()) {
            queue.addAll(list);
            queueIndex = queue.indexOf(v);
            if (queueIndex < 0) { queue.add(0, v); queueIndex = 0; }
        } else {
            queue.add(v);
            queueIndex = 0;
        }
        startCurrent();
    }

    private static void startCurrent() {
        if (queueIndex < 0 || queueIndex >= queue.size()) return;
        current = queue.get(queueIndex);
        repeatA = repeatB = -1;
        subOffsetMs = 0;
        primarySub = null;
        secondarySub = null;
        audioTracks.clear();
        embeddedSubTracks.clear();
        selectedAudioTrack = -1;
        prepared = false;
        playing = false;
        videoW = current.width;
        videoH = current.height;
        durationMs = current.durationMs;

        // Auto-load AI cached subtitle or sidecar subtitle
        if (MainActivity.prefs(app).getBoolean("auto_sub", true)) {
            File aiSrt = AiSubtitle.cachedSrtFor(app, current.key());
            if (aiSrt.exists()) {
                primarySub = Subtitle.loadFile(aiSrt);
            } else {
                File side = Subtitle.findSidecar(current.path);
                if (side != null) primarySub = Subtitle.loadFile(side);
            }
        }

        releasePlayer();
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        if (surface != null && surface.isValid()) {
            try { mp.setSurface(surface); } catch (Throwable ignored) {}
        }
        mp.setOnVideoSizeChangedListener((p, w, h) -> {
            if (w > 0 && h > 0) {
                videoW = w;
                videoH = h;
                notifyAll0();
            }
        });
        mp.setOnPreparedListener(p -> {
            prepared = true;
            durationMs = Math.max(0, p.getDuration());
            if (p.getVideoWidth() > 0) videoW = p.getVideoWidth();
            if (p.getVideoHeight() > 0) videoH = p.getVideoHeight();
            loadTracks(p);
            AudioFx.attach(p.getAudioSessionId());
            NpuAudio.attachLocalSession(app, p.getAudioSessionId());
            if (MainActivity.prefs(app).getBoolean("auto_resume", true)) {
                long resume = VideoLibrary.getResume(current.key());
                if (resume > 3000 && (durationMs <= 0 || resume < durationMs - 5000)) {
                    try { p.seekTo((int) resume); } catch (Throwable ignored) {}
                }
            }
            applySpeed(speed);
            p.start();
            playing = true;
            VideoLibrary.recordPlay(current, positionMs());
            startTick();
            notifyAll0();
        });
        mp.setOnCompletionListener(p -> {
            playing = false;
            if (sleepAfterCurrent) {
                sleepAfterCurrent = false;
                notifyAll0();
                return;
            }
            if (repeatMode == REPEAT_ONE) {
                seekTo(0);
                resume();
            } else if (MainActivity.prefs(app).getBoolean("auto_next", true) && hasNext()) {
                next();
            } else {
                notifyAll0();
            }
        });
        mp.setOnErrorListener((p, what, extra) -> {
            playing = false;
            prepared = false;
            notifyAll0();
            return true;
        });

        try {
            if (current.path != null && new File(current.path).exists()) {
                mp.setDataSource(current.path);
            } else {
                mp.setDataSource(app, Uri.parse(current.uri));
            }
            mp.prepareAsync();
        } catch (Throwable t) {
            playing = false;
        }
        notifyAll0();
    }

    private static void loadTracks(MediaPlayer p) {
        audioTracks.clear();
        embeddedSubTracks.clear();
        try {
            MediaPlayer.TrackInfo[] infos = p.getTrackInfo();
            int aIdx = 1, sIdx = 1;
            for (int i = 0; i < infos.length; i++) {
                int type = infos[i].getTrackType();
                String lang = infos[i].getLanguage();
                if (lang == null || lang.isEmpty() || "und".equals(lang)) lang = "";
                if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    String lbl = "오디오 " + (aIdx++) + (lang.isEmpty() ? "" : " (" + lang + ")");
                    audioTracks.add(new TrackItem(i, lbl));
                    if (selectedAudioTrack < 0) selectedAudioTrack = i;
                } else if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                        || type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                    String lbl = "내장 자막 " + (sIdx++) + (lang.isEmpty() ? "" : " (" + lang + ")");
                    embeddedSubTracks.add(new TrackItem(i, lbl));
                }
            }
        } catch (Throwable ignored) {}
    }

    static void selectAudioTrack(int trackIndex) {
        if (mp != null && prepared) {
            try {
                mp.selectTrack(trackIndex);
                selectedAudioTrack = trackIndex;
                notifyAll0();
            } catch (Throwable ignored) {}
        }
    }

    static void setSurface(Surface s) {
        surface = s;
        if (mp != null) {
            try { mp.setSurface(s); } catch (Throwable ignored) {}
        }
    }

    static long positionMs() {
        if (mp != null && prepared) {
            try { return Math.max(0, mp.getCurrentPosition()); } catch (Throwable ignored) {}
        }
        return 0;
    }

    static void toggle() {
        if (playing) pause(); else resume();
    }

    static void pause() {
        if (mp != null && prepared && playing) {
            try { mp.pause(); } catch (Throwable ignored) {}
            playing = false;
            saveCurrentResume();
            notifyAll0();
        }
    }

    static void resume() {
        if (mp != null && prepared && !playing) {
            try { mp.start(); } catch (Throwable ignored) {}
            playing = true;
            startTick();
            notifyAll0();
        }
    }

    static void seekTo(long ms) {
        if (mp != null && prepared) {
            long clamped = Math.max(0, durationMs > 0 ? Math.min(durationMs, ms) : ms);
            try { mp.seekTo(clamped, MediaPlayer.SEEK_CLOSEST); } catch (Throwable t) {
                try { mp.seekTo((int) clamped); } catch (Throwable ignored) {}
            }
            notifyAll0();
        }
    }

    static void seekBy(long deltaMs) { seekTo(positionMs() + deltaMs); }

    static void setSpeed(float s) {
        speed = Ui.clamp(s, 0.25f, 4.0f);
        applySpeed(speed);
        notifyAll0();
    }

    static void applyTempSpeed(float s) { applySpeed(Ui.clamp(s, 0.25f, 4.0f)); }

    private static void applySpeed(float s) {
        if (mp != null && prepared) {
            try {
                PlaybackParams params = mp.getPlaybackParams();
                params.setSpeed(s);
                params.setPitch(1.0f);
                boolean wasPlaying = playing;
                mp.setPlaybackParams(params);
                if (!wasPlaying) mp.pause();
            } catch (Throwable ignored) {}
        }
    }

    static boolean hasNext() {
        if (queue.isEmpty()) return false;
        return repeatMode == REPEAT_ALL || queueIndex + 1 < queue.size();
    }

    static boolean hasPrev() {
        if (queue.isEmpty()) return false;
        return repeatMode == REPEAT_ALL || queueIndex > 0;
    }

    static void next() {
        if (queue.isEmpty()) return;
        saveCurrentResume();
        if (queueIndex + 1 < queue.size()) queueIndex++;
        else if (repeatMode == REPEAT_ALL) queueIndex = 0;
        else return;
        startCurrent();
    }

    static void prev() {
        if (positionMs() > 3500) { seekTo(0); return; }
        if (queue.isEmpty()) return;
        saveCurrentResume();
        if (queueIndex > 0) queueIndex--;
        else if (repeatMode == REPEAT_ALL) queueIndex = queue.size() - 1;
        else { seekTo(0); return; }
        startCurrent();
    }

    static void setSleepTimerMinutes(int min) {
        sleepAfterCurrent = false;
        sleepUntilMs = min <= 0 ? -1 : System.currentTimeMillis() + min * 60_000L;
        notifyAll0();
    }

    static void saveCurrentResume() {
        if (current != null && prepared) {
            VideoLibrary.recordPlay(current, positionMs());
        }
    }

    static void stopAndClose() {
        saveCurrentResume();
        releasePlayer();
        current = null;
        playing = false;
        prepared = false;
        backgroundAudio = false;
        notifyAll0();
    }

    private static void releasePlayer() {
        AudioFx.release();
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }

    private static void startTick() {
        if (tickRunning) return;
        tickRunning = true;
        MAIN.postDelayed(tickRunnable, 200);
    }

    private static final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            if (!playing || mp == null || !prepared) {
                tickRunning = false;
                return;
            }
            long pos = positionMs();
            if (repeatA >= 0 && repeatB > repeatA && pos >= repeatB) {
                seekTo(repeatA);
            }
            if (sleepUntilMs > 0 && System.currentTimeMillis() >= sleepUntilMs) {
                sleepUntilMs = -1;
                pause();
                tickRunning = false;
                return;
            }
            MAIN.postDelayed(this, 180);
        }
    };

    // ---- Foreground MediaSession playback service (for background audio & notification controls) ----
    public static final class PlaybackService extends Service {
        private static final String CH = "play_playback";
        private static final int NOTIF_ID = 9501;
        private static MediaSession session;
        private static PlaybackService instance;

        static void update(Context c) {
            if (current == null || (!playing && !backgroundAudio)) {
                if (instance != null) {
                    instance.stopForeground(STOP_FOREGROUND_REMOVE);
                    instance.stopSelf();
                }
                return;
            }
            if (backgroundAudio || playing) {
                if (instance != null) instance.pushNotification();
                else if (backgroundAudio) {
                    try {
                        c.startForegroundService(new Intent(c, PlaybackService.class));
                    } catch (Throwable ignored) {}
                }
            }
        }

        @Override public void onCreate() {
            super.onCreate();
            instance = this;
            NotificationChannel ch = new NotificationChannel(CH, "동영상 재생", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
            session = new MediaSession(this, "PlayVideoSession");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { resume(); }
                @Override public void onPause() { pause(); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onSeekTo(long pos) { seekTo(pos); }
            });
            session.setActive(true);
        }

        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent != null && intent.getAction() != null) {
                switch (intent.getAction()) {
                    case "PLAY_PAUSE": toggle(); break;
                    case "NEXT": next(); break;
                    case "PREV": prev(); break;
                    case "CLOSE": stopAndClose(); return START_NOT_STICKY;
                }
            }
            pushNotification();
            return START_STICKY;
        }

        private void pushNotification() {
            if (current == null) return;
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, positionMs(), speed)
                    .build();
            session.setPlaybackState(state);
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, current.folder)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                    .build());

            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(this, CH)
                    .setSmallIcon(R.drawable.ic_stat_play_logo)
                    .setContentTitle(current.title)
                    .setContentText(current.folder)
                    .setContentIntent(open)
                    .setOngoing(playing)
                    .addAction(action("PREV", R.drawable.ic_stat_prev, "이전"))
                    .addAction(action("PLAY_PAUSE", playing ? R.drawable.ic_stat_pause : R.drawable.ic_stat_play, playing ? "일시정지" : "재생"))
                    .addAction(action("NEXT", R.drawable.ic_stat_next, "다음"))
                    .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                    .build();
            startForeground(NOTIF_ID, n);
        }

        private Notification.Action action(String act, int icon, String title) {
            Intent i = new Intent(this, PlaybackService.class).setAction(act);
            PendingIntent pi = PendingIntent.getService(this, act.hashCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            return new Notification.Action.Builder(icon, title, pi).build();
        }

        @Override public void onDestroy() {
            if (session != null) { session.release(); session = null; }
            instance = null;
            super.onDestroy();
        }

        @Override public IBinder onBind(Intent intent) { return null; }
    }
}
