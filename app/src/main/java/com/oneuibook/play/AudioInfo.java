package com.oneuibook.play;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Inspects active Bluetooth A2DP/LE Audio or direct USB DAC output and evaluates the 4-tier Hi-Res playback tier.
 */
final class AudioInfo {
    private AudioInfo() {}

    static final int TIER_NONE = 0;
    static final int TIER_GOLD = 1;
    static final int TIER_BLUE = 2;
    static final int TIER_MASTER = 3;

    static final class OutputSpec {
        String deviceName = "";
        String codec = "";
        int bits = 16;
        float khz = 48f;
        boolean external;
        boolean isBt;
        boolean isUsbDac;
        boolean isWired;
        boolean isBitPerfect;

        String khzText() {
            if (khz <= 0) return "";
            if (Math.abs(khz - Math.round(khz)) < 0.05f) return Math.round(khz) + "kHz";
            return String.format(Locale.US, "%.1fkHz", khz);
        }

        String formatLine() {
            if (!external && bits <= 16 && khz < 96f) return "";
            StringBuilder sb = new StringBuilder();
            if (codec != null && !codec.isEmpty()) sb.append(codec);
            if (bits > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(bits).append("bit");
            }
            if (khz > 0) {
                if (sb.length() > 0) sb.append(bits > 0 ? " / " : " · ");
                sb.append(khzText());
            }
            return sb.toString();
        }
    }

    static OutputSpec testSpec;
    private static BluetoothA2dp a2dpProxy;
    private static boolean proxyRequested;

    static void init(Context c) { init(c, false); }

    static void init(Context c, boolean force) {
        if ((proxyRequested && !force) || c == null) return;
        proxyRequested = true;
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            adapter.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP && proxy instanceof BluetoothA2dp) {
                        a2dpProxy = (BluetoothA2dp) proxy;
                        NpuAudio.syncPerDevice(c.getApplicationContext());
                        NpuAudio.apply(c.getApplicationContext(), true);
                        VideoPlayer.notifyAll0();
                    }
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = null;
                }
            }, BluetoothProfile.A2DP);
        } catch (Throwable ignored) {}
    }

    static int evaluateHiResTier(boolean assistOn, int bits, float khz, boolean isBitPerfect) {
        if (isBitPerfect) return TIER_MASTER;
        int count = (assistOn ? 1 : 0) + (bits > 16 ? 1 : 0) + (khz >= 96f ? 1 : 0);
        if (count >= 3) return TIER_MASTER;
        if (count == 2) return TIER_BLUE;
        if (count == 1) return TIER_GOLD;
        return TIER_NONE;
    }

    static int hiResTier(Context c, OutputSpec spec) {
        if (spec == null) spec = current(c);
        boolean assist = NpuAudio.enabled(c);
        boolean bp = spec.isBitPerfect || BitPerfect.activeRate > 0;
        return evaluateHiResTier(assist, spec.bits, spec.khz, bp);
    }

    static int deviceIcon(OutputSpec spec) {
        if (spec == null) return Icons.DEVICE;
        return deviceIconForName(spec.deviceName, spec.isBt, spec.isUsbDac, spec.isWired);
    }

    static int deviceIconForName(String name, boolean isBt, boolean isUsbDac, boolean isWired) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.contains("buds on") || n.contains("budson") || n.contains("버즈 온")) return Icons.BUDS_ON;
        if (n.contains("buds4") || n.contains("buds 4") || n.contains("버즈4") || n.contains("sm-r640") || n.contains("sm-r540")) return Icons.BUDS4;
        if (n.contains("buds3") || n.contains("buds 3") || n.contains("버즈3") || n.contains("sm-r630") || n.contains("sm-r530")) return Icons.BUDS;
        if (n.contains("buds live") || n.contains("버즈 라이브") || n.contains("sm-r180")) return Icons.BUDS_LIVE;
        if (n.contains("buds2") || n.contains("buds 2") || n.contains("buds pro") || n.contains("buds fe") || n.contains("galaxy buds") || n.contains("버즈")) return Icons.BUDS2;
        if (n.contains("headphone") || n.contains("wh-1000") || n.contains("quietcomfort") || n.contains("airpods max") || n.contains("xm4") || n.contains("xm5") || n.contains("헤드폰")) return Icons.HEADPHONES;
        if (n.contains("airpods") || n.contains("earbuds") || n.contains("wf-") || n.contains("이어폰")) return Icons.EARBUDS;
        if (isUsbDac) return Icons.DAC;
        if (isWired) return Icons.HEADPHONES;
        if (isBt) return Icons.EARBUDS;
        return Icons.DEVICE;
    }

    static OutputSpec current(Context c) {
        if (testSpec != null) return testSpec;
        OutputSpec s = new OutputSpec();
        if (c == null) return s;
        init(c);
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return s;
            AudioDeviceInfo usb = null, bt = null, wired = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    if (usb == null) usb = d;
                } else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    if (bt == null) bt = d;
                } else if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                    if (wired == null) wired = d;
                }
            }
            if (usb != null) { fillUsb(s, usb); return s; }
            if (bt != null) { fillBluetooth(c, s, bt, am); return s; }
            if (wired != null) {
                s.isWired = true;
                CharSequence pn = wired.getProductName();
                s.deviceName = (pn == null || pn.length() == 0) ? "Wired" : pn.toString();
            }
        } catch (Throwable ignored) {}
        return s;
    }

    private static void fillUsb(OutputSpec s, AudioDeviceInfo d) {
        s.external = true;
        s.isUsbDac = true;
        CharSequence pn = d.getProductName();
        s.deviceName = (pn == null || pn.length() == 0) ? "USB DAC" : pn.toString();
        if (BitPerfect.activeRate > 0 && BitPerfect.activeBits > 0) {
            s.isBitPerfect = true;
            s.codec = BitPerfect.activeBitPerfect ? "USB Bit-Perfect" : "USB Direct";
            s.bits = BitPerfect.activeBits;
            s.khz = BitPerfect.activeRate / 1000f;
            return;
        }
        s.codec = "USB PCM";
        s.bits = maxBitsFromEncodings(d.getEncodings(), 0);
        s.khz = maxKhzFromRates(d.getSampleRates(), 0f);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        int b = bitsFromEncoding(ap.getFormat());
                        if (b > s.bits) s.bits = b;
                        float r = maxKhzFromRates(ap.getSampleRates(), 0f);
                        if (r > s.khz) s.khz = r;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (s.bits <= 0) s.bits = 24;
        if (s.khz <= 0f) s.khz = 48f;
    }

    private static void fillBluetooth(Context c, OutputSpec s, AudioDeviceInfo d, AudioManager am) {
        s.external = true;
        s.isBt = true;
        CharSequence pn = d.getProductName();
        String productName = (pn == null || pn.length() == 0) ? "" : pn.toString().trim();
        s.deviceName = productName.isEmpty() ? "Bluetooth" : productName;
        boolean ble = d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET;
        s.codec = ble ? "LC3" : "AAC";
        s.bits = maxBitsFromEncodings(d.getEncodings(), 16);
        s.khz = maxKhzFromRates(d.getSampleRates(), 48f);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioProfile> profs = d.getAudioProfiles();
                if (profs != null) {
                    for (AudioProfile ap : profs) {
                        float maxR = maxKhzFromRates(ap.getSampleRates(), 0f);
                        if (maxR > s.khz) s.khz = maxR;
                        int b = bitsFromEncoding(ap.getFormat());
                        if (b > s.bits) s.bits = b;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (s.khz >= 96f || s.bits >= 24) {
            String n = s.deviceName.toLowerCase(Locale.US);
            s.codec = (n.contains("buds") || n.contains("버즈")) ? "SSC-UHQ" : "LDAC";
        }
    }

    private static int bitsFromEncoding(int e) {
        if (e == AudioFormat.ENCODING_PCM_32BIT || e == AudioFormat.ENCODING_PCM_FLOAT) return 32;
        if (e == AudioFormat.ENCODING_PCM_24BIT_PACKED) return 24;
        if (e == AudioFormat.ENCODING_PCM_16BIT) return 16;
        return 0;
    }

    private static int maxBitsFromEncodings(int[] encs, int fallback) {
        if (encs == null || encs.length == 0) return fallback;
        int best = 0;
        for (int e : encs) {
            int b = bitsFromEncoding(e);
            if (b > best) best = b;
        }
        return best > 0 ? best : fallback;
    }

    private static float maxKhzFromRates(int[] rates, float fallback) {
        if (rates == null || rates.length == 0) return fallback;
        int best = 0;
        for (int r : rates) if (r > best && r <= 384000) best = r;
        return best > 0 ? best / 1000f : fallback;
    }
}
