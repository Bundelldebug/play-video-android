package com.oneuibook.play;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.AudioProfile;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.util.List;

/**
 * Bit-perfect & Direct Hi-Res USB DAC playback for video audio tracks (Android 14+, UAC2 USB DAC).
 */
final class BitPerfect {
    private BitPerfect() {}

    static final String PREF = "bit_perfect";
    static String state = "";
    static int activeRate, activeBits;
    static boolean activeBitPerfect;
    static AudioDeviceInfo activeDevice;

    static boolean supportedOs() { return Build.VERSION.SDK_INT >= 34; }
    static boolean enabled(Context c) { return Settings.pref(c, PREF, false); }
    static boolean isActive() { return activeRate > 0 && activeDevice != null; }

    static AudioAttributes media() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();
    }

    static AudioDeviceInfo usbOut(AudioManager am) {
        if (am == null) return null;
        AudioDeviceInfo best = null;
        int bestScore = -1;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t != AudioDeviceInfo.TYPE_USB_DEVICE && t != AudioDeviceInfo.TYPE_USB_HEADSET) continue;
            int score = 10 + (t == AudioDeviceInfo.TYPE_USB_DEVICE ? 20 : 0);
            int bp = bitPerfectModes(am, d);
            if (bp > 0) score += 1000 + Math.min(200, bp * 10);
            int maxRate = maxSampleRate(am, d);
            if (maxRate >= 96000) score += 100;
            if (maxRate >= 192000) score += 50;
            if (maxBits(am, d) >= 24) score += 40;
            if (score > bestScore) { bestScore = score; best = d; }
        }
        return best;
    }

    static String status(Context c) {
        if (!supportedOs()) return c.getString(R.string.bp_needs_14);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo d = usbOut(am);
        if (d == null) return c.getString(R.string.bp_no_dac);
        if (!state.isEmpty()) return state;
        if (bitPerfectModes(am, d) > 0) return c.getString(R.string.bp_ready, name(d));
        if (usbDirectCapable(am, d)) return c.getString(R.string.bp_ready_direct, name(d));
        return c.getString(R.string.bp_unsupported, name(d));
    }

    private static String name(AudioDeviceInfo d) {
        CharSequence n = d.getProductName();
        return n == null || n.length() == 0 ? "USB DAC" : n.toString();
    }

    private static int bitPerfectModes(AudioManager am, AudioDeviceInfo d) {
        if (!supportedOs() || am == null || d == null) return 0;
        int n = 0;
        try {
            List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
            if (list != null) {
                for (AudioMixerAttributes a : list)
                    if (a.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) n++;
            }
        } catch (Throwable ignored) {}
        return n;
    }

    private static boolean usbDirectCapable(AudioManager am, AudioDeviceInfo d) {
        if (am == null || d == null) return false;
        try {
            List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
            if (list != null && !list.isEmpty()) return true;
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null && !profs.isEmpty()) return true;
            } catch (Throwable ignored) {}
        }
        int[] rates = d.getSampleRates();
        if (rates != null && rates.length > 0) return true;
        int[] encs = d.getEncodings();
        return encs != null && encs.length > 0;
    }

    static int maxSampleRate(AudioManager am, AudioDeviceInfo d) {
        int best = 0;
        if (supportedOs() && am != null) {
            try {
                List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
                if (list != null) {
                    for (AudioMixerAttributes a : list) {
                        int r = a.getFormat().getSampleRate();
                        if (r > best && r <= 768000) best = r;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        int[] rs = ap.getSampleRates();
                        if (rs != null) for (int r : rs) if (r > best && r <= 768000) best = r;
                    }
                }
            } catch (Throwable ignored) {}
        }
        int[] rs = d.getSampleRates();
        if (rs != null) for (int r : rs) if (r > best && r <= 768000) best = r;
        return best;
    }

    static int maxBits(AudioManager am, AudioDeviceInfo d) {
        int best = 0;
        if (supportedOs() && am != null) {
            try {
                List<AudioMixerAttributes> list = am.getSupportedMixerAttributes(d);
                if (list != null) {
                    for (AudioMixerAttributes a : list) {
                        int b = bitsOf(a.getFormat().getEncoding());
                        if (b > best) best = b;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        int b = bitsOf(ap.getFormat());
                        if (b > best) best = b;
                    }
                }
            } catch (Throwable ignored) {}
        }
        int[] es = d.getEncodings();
        if (es != null) for (int e : es) { int b = bitsOf(e); if (b > best) best = b; }
        return best;
    }

    static void clear(Context c) {
        state = "";
        activeRate = 0;
        activeBits = 0;
        activeBitPerfect = false;
        activeDevice = null;
        if (!supportedOs()) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                try { am.clearPreferredMixerAttributes(media(), d); } catch (Exception ignored) {}
            }
        }
    }

    static int bitsOf(int enc) {
        switch (enc) {
            case AudioFormat.ENCODING_PCM_8BIT: return 8;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: return 24;
            case AudioFormat.ENCODING_PCM_32BIT:
            case AudioFormat.ENCODING_PCM_FLOAT: return 32;
            default: return 16;
        }
    }
}
