package com.oneuibook.play;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI Subtitle generator:
 * 1. Extracts & downsamples the video's audio track to 16kHz Mono 16-bit PCM WAV using pure Android MediaExtractor + MediaCodec.
 * 2. Splits long videos into manageable chunks (~6 minutes per chunk) to stay well within API upload limits.
 * 3. Calls Google Gemini (multimodal transcription + optional translation), OpenAI Whisper, Groq Whisper, or a custom OpenAI-compatible server.
 * 4. Merges chunks with timestamp offsets, saves .srt into app cache, and returns a ready-to-play Subtitle.Track.
 */
final class AiSubtitle {
    private AiSubtitle() {}

    static final String ENGINE_GEMINI = "gemini";
    static final String ENGINE_OPENAI = "openai";
    static final String ENGINE_GROQ = "groq";
    static final String ENGINE_CUSTOM = "custom";

    static final String[] ENGINE_KEYS = {ENGINE_GEMINI, ENGINE_OPENAI, ENGINE_GROQ, ENGINE_CUSTOM};
    static final String[] ENGINE_NAMES = {"Google Gemini (번역 동시 지원)", "OpenAI Whisper", "Groq Whisper (초고속)", "사용자 지정 Whisper 서버"};

    static final String[] LANG_KEYS = {"auto", "ko", "en", "ja", "zh", "es"};
    static final int[] LANG_LABELS = {R.string.ai_lang_auto, R.string.ai_lang_ko, R.string.ai_lang_en,
            R.string.ai_lang_ja, R.string.ai_lang_zh, R.string.ai_lang_es};

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int TARGET_RATE = 16000;
    private static final long CHUNK_MS = 360_000L; // 6 minutes per chunk (~11.5 MB WAV)

    interface Callback {
        void onProgress(String stage, int percent);
        void onSuccess(Subtitle.Track track, File srtFile);
        void onError(String message);
    }

    static File cacheDir(Context c) {
        File d = new File(c.getFilesDir(), "ai_subtitles");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File cachedSrtFor(Context c, String videoKey) {
        return new File(cacheDir(c), sha1(videoKey) + ".srt");
    }

    static List<File> listGenerated(Context c) {
        List<File> out = new ArrayList<>();
        File[] files = cacheDir(c).listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().endsWith(".srt")) out.add(f);
        }
        return out;
    }

    static AtomicBoolean generate(Context c, VideoLibrary.Video video, Callback cb) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        SharedPreferences p = MainActivity.prefs(c);
        String engine = p.getString("ai_engine", ENGINE_GEMINI);
        String apiKey = p.getString("ai_api_key", "").trim();
        String customUrl = p.getString("ai_custom_url", "").trim();
        String srcLang = p.getString("ai_src_lang", "auto");
        String dstLang = p.getString("ai_dst_lang", "ko");

        if (apiKey.isEmpty() && !ENGINE_CUSTOM.equals(engine)) {
            MAIN.post(() -> cb.onError(c.getString(R.string.ai_need_key)));
            return cancelled;
        }
        if (video == null || video.isStream) {
            MAIN.post(() -> cb.onError(c.getString(R.string.ai_need_local)));
            return cancelled;
        }

        IO.execute(() -> {
            try {
                postProgress(cb, c.getString(R.string.ai_extracting), 8);
                List<AudioChunk> chunks = extractAudioChunks(c, video, cancelled, pct ->
                        postProgress(cb, c.getString(R.string.ai_extracting) + " (" + pct + "%)", 8 + (int) (pct * 0.37f)));
                if (cancelled.get()) return;
                if (chunks.isEmpty()) throw new IllegalStateException("오디오 트랙을 찾을 수 없습니다.");

                List<Subtitle.Cue> allCues = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    if (cancelled.get()) return;
                    AudioChunk ch = chunks.get(i);
                    int basePct = 45 + (int) (50f * i / chunks.size());
                    String label = chunks.size() > 1
                            ? c.getString(R.string.ai_uploading) + " (" + (i + 1) + "/" + chunks.size() + ")"
                            : c.getString(R.string.ai_uploading);
                    postProgress(cb, label, basePct);

                    String srt;
                    if (ENGINE_GEMINI.equals(engine)) {
                        srt = callGemini(apiKey, ch.wavBytes, srcLang, dstLang);
                    } else if (ENGINE_OPENAI.equals(engine)) {
                        srt = callWhisper("https://api.openai.com/v1/audio/transcriptions", apiKey, "whisper-1", ch.wavBytes, srcLang);
                    } else if (ENGINE_GROQ.equals(engine)) {
                        srt = callWhisper("https://api.groq.com/openai/v1/audio/transcriptions", apiKey, "whisper-large-v3-turbo", ch.wavBytes, srcLang);
                    } else {
                        if (customUrl.isEmpty()) throw new IllegalStateException("사용자 지정 엔드포인트 URL을 입력하세요.");
                        srt = callWhisper(customUrl, apiKey, "whisper-1", ch.wavBytes, srcLang);
                    }

                    Subtitle.Track part = Subtitle.parse("ai.srt", "", normalizeSrt(srt));
                    for (Subtitle.Cue cue : part.cues) {
                        allCues.add(new Subtitle.Cue(cue.startMs + ch.offsetMs, cue.endMs + ch.offsetMs, cue.text));
                    }
                }

                if (cancelled.get()) return;
                if (allCues.isEmpty()) throw new IllegalStateException("AI 응답에서 자막을 추출하지 못했습니다.");

                String finalSrt = buildSrt(allCues);
                File out = cachedSrtFor(c, video.key());
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(finalSrt.getBytes(StandardCharsets.UTF_8));
                }
                Subtitle.Track track = new Subtitle.Track("AI 자막 (" + video.title + ")", out.getAbsolutePath(), allCues);
                postProgress(cb, c.getString(R.string.ai_done), 100);
                MAIN.post(() -> cb.onSuccess(track, out));
            } catch (Throwable t) {
                if (!cancelled.get()) {
                    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    MAIN.post(() -> cb.onError(msg));
                }
            }
        });
        return cancelled;
    }

    private static void postProgress(Callback cb, String stage, int pct) {
        MAIN.post(() -> cb.onProgress(stage, pct));
    }

    // ---- Audio extraction via Android MediaExtractor + MediaCodec ----
    static final class AudioChunk {
        final long offsetMs;
        final byte[] wavBytes;
        AudioChunk(long offsetMs, byte[] wavBytes) { this.offsetMs = offsetMs; this.wavBytes = wavBytes; }
    }

    interface PctListener { void onPct(int pct); }

    private static List<AudioChunk> extractAudioChunks(Context c, VideoLibrary.Video video, AtomicBoolean cancelled, PctListener pl) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        if (video.path != null && new File(video.path).exists()) {
            ex.setDataSource(video.path);
        } else {
            ex.setDataSource(c, Uri.parse(video.uri), null);
        }
        int audioTrack = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                fmt = f;
                break;
            }
        }
        if (audioTrack < 0 || fmt == null) {
            ex.release();
            return new ArrayList<>();
        }
        ex.selectTrack(audioTrack);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int srcChannels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        long totalUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : Math.max(1L, video.durationMs * 1000L);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();

        List<AudioChunk> chunks = new ArrayList<>();
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(256 * 1024);
        long chunkStartMs = 0L;
        int maxChunkBytes = (int) (TARGET_RATE * 2 * (CHUNK_MS / 1000L));

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, outputDone = false;
        double ratio = (double) srcRate / TARGET_RATE;
        double sampleAcc = 0.0;
        int lastPct = -1;

        try {
            while (!outputDone && !cancelled.get()) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int sampleSize = ex.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = ex.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                            ex.advance();
                            if (totalUs > 0) {
                                int pct = (int) Ui.clamp(pts * 100f / totalUs, 0f, 100f);
                                if (pct != lastPct) { lastPct = pct; pl.onPct(pct); }
                            }
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            ByteBuffer le = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN);
                            int frameCount = info.size / (2 * Math.max(1, srcChannels));
                            for (int f = 0; f < frameCount; f++) {
                                int sum = 0;
                                for (int ch = 0; ch < srcChannels; ch++) sum += le.getShort();
                                short mono = (short) (sum / Math.max(1, srcChannels));
                                sampleAcc += 1.0;
                                if (sampleAcc >= ratio) {
                                    sampleAcc -= ratio;
                                    pcm.write(mono & 0xFF);
                                    pcm.write((mono >> 8) & 0xFF);
                                }
                            }
                            if (pcm.size() >= maxChunkBytes) {
                                byte[] raw = pcm.toByteArray();
                                chunks.add(new AudioChunk(chunkStartMs, wrapWav(raw, TARGET_RATE, 1, 16)));
                                chunkStartMs += (raw.length / 2L) * 1000L / TARGET_RATE;
                                pcm.reset();
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        ratio = (double) srcRate / TARGET_RATE;
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        srcChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
            if (!cancelled.get() && pcm.size() > 0) {
                chunks.add(new AudioChunk(chunkStartMs, wrapWav(pcm.toByteArray(), TARGET_RATE, 1, 16)));
            }
        } finally {
            try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
            try { ex.release(); } catch (Throwable ignored) {}
        }
        return chunks;
    }

    static byte[] wrapWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int totalSize = 36 + dataSize;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        h.putInt(totalSize);
        h.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        h.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        h.putInt(16);
        h.putShort((short) 1);
        h.putShort((short) channels);
        h.putInt(sampleRate);
        h.putInt(byteRate);
        h.putShort((short) (channels * bitsPerSample / 8));
        h.putShort((short) bitsPerSample);
        h.put("data".getBytes(StandardCharsets.US_ASCII));
        h.putInt(dataSize);
        byte[] out = new byte[44 + dataSize];
        System.arraycopy(h.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, dataSize);
        return out;
    }

    // ---- Gemini API ----
    private static String callGemini(String apiKey, byte[] wav, String srcLang, String dstLang) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;
        String langInstruction = "auto".equals(dstLang)
                ? "Transcribe the speech in its original language."
                : "Transcribe and translate the speech into natural " + langName(dstLang) + ".";
        String srcHint = "auto".equals(srcLang) ? "" : " The spoken language is " + langName(srcLang) + ".";
        String prompt = "Generate accurate SubRip (.srt) subtitles for this audio." + srcHint + " " + langInstruction
                + " Output ONLY valid SRT blocks (sequence number, HH:MM:SS,mmm --> HH:MM:SS,mmm, and concise subtitle lines). No markdown fences or commentary.";

        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", "audio/wav");
        inlineData.put("data", Base64.encodeToString(wav, Base64.NO_WRAP));

        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", prompt));
        parts.put(new JSONObject().put("inline_data", inlineData));

        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(new JSONObject().put("parts", parts)));

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(180_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("Gemini API 오류 (" + code + "): " + trimErr(resp));

        JSONObject json = new JSONObject(resp);
        JSONArray cands = json.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new IllegalStateException("Gemini 응답이 비어 있습니다.");
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        JSONArray outParts = content == null ? null : content.optJSONArray("parts");
        if (outParts == null || outParts.length() == 0) throw new IllegalStateException("Gemini 자막 텍스트가 없습니다.");
        return outParts.getJSONObject(0).optString("text", "");
    }

    // ---- OpenAI / Groq / Custom Whisper API ----
    private static String callWhisper(String url, String apiKey, String model, byte[] wav, String srcLang) throws Exception {
        String boundary = "----PlayAiSub" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(180_000);
        conn.setDoOutput(true);
        if (apiKey != null && !apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream()) {
            writeField(os, boundary, "model", model);
            writeField(os, boundary, "response_format", "srt");
            if (srcLang != null && !"auto".equals(srcLang)) writeField(os, boundary, "language", srcLang);
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
            os.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(wav);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("Whisper API 오류 (" + code + "): " + trimErr(resp));
        return resp;
    }

    private static void writeField(OutputStream os, String boundary, String name, String val) throws Exception {
        String s = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + val + "\r\n";
        os.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream s = in) {
            return new String(Subtitle.readAll(s), StandardCharsets.UTF_8);
        }
    }

    static String normalizeSrt(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end > 0) t = t.substring(0, end);
        }
        return t.trim();
    }

    static String buildSrt(List<Subtitle.Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Subtitle.Cue c = cues.get(i);
            sb.append(i + 1).append('\n')
                    .append(Subtitle.formatSrtTime(c.startMs)).append(" --> ")
                    .append(Subtitle.formatSrtTime(c.endMs)).append('\n')
                    .append(c.text).append("\n\n");
        }
        return sb.toString();
    }

    private static String langName(String code) {
        switch (code) {
            case "ko": return "Korean";
            case "en": return "English";
            case "ja": return "Japanese";
            case "zh": return "Chinese";
            case "es": return "Spanish";
            default: return "Korean";
        }
    }

    private static String trimErr(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
