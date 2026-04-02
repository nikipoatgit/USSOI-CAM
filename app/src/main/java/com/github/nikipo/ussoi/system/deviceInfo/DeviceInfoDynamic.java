package com.github.nikipo.ussoi.system.deviceInfo;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file DeviceInfoDynamic
 * @attention Copyright (c) 2026
 * All rights reserved.
 * <p>
 * This software is licensed under the terms described in the LICENSE file
 * located in the root directory of this project.
 * If no LICENSE file is present, this software is provided "AS IS",
 * without warranty of any kind, express or implied.
 * <p>
 * *****************************************************************************
 */



/**
 * Collects device state that changes at runtime.
 *
 *   getAll()    → full JSONObject  (About overlay / one-shot debug sends)
 *   getPacket() → compact hex string (frequent WebSocket polling)
 *
 * ── Packet layout (little-endian) ────────────────────────────────────────────
 *
 *  Off  Sz   Type    Field
 *   0    2  uint16  RAM total MB          (0xFFFF = N/A)
 *   2    1  uint8   RAM used/256          usage × 255 / 100  → 0.39% resolution
 *   3    2  uint16  Internal storage total MB
 *   5    1  uint8   Internal storage used/256
 *   6    1  uint8   External volume count E (0–4)
 *   7   E×3         Per external volume: [uint16 totalMB][uint8 used/256]
 *  7+E×3  1  uint8   Net transport (0=none 1=wifi 2=cell 3=bt 4=unknown)
 *          1  int8    WiFi dBm   (0x7F = N/A)
 *          1  int8    RSRP dBm   (0x7F = N/A)
 *          1  int8    RSRQ dBm   (0x7F = N/A)
 *          1  int8    SINR       (0x7F = N/A)
 *          1  uint8   Timing Advance (0xFF = N/A)
 *          1  uint8   Core count N (≤ 8)
 *         ⌈N×12/8⌉  12-bit packed core freqs in MHz (0xFFF = N/A)
 *
 * Typical (0 ext, 8 cores): 7 + 0 + 6 + 1 + 12 = 26 bytes / 52 hex chars.
 *
 * Decoding free from the two compressed fields:
 *   freeBytes = totalMB × (1 - used256/255) × 1024 × 1024
 *   freeMB    = totalMB - round(totalMB × used256 / 255)
 */
public class DeviceInfoDynamic {

    // ── Packet sentinels ──────────────────────────────────────────────────────
    private static final int  NA_UINT16_MB  = 0xFFFF;   // uint16 N/A
    private static final byte NA_INT8       = 0x7F;     // int8 signal N/A (127)
    private static final int  NA_UINT8      = 0xFF;     // uint8 TA N/A
    private static final int  NA_CORE_FREQ  = 0xFFF;   // 12-bit freq N/A

    // ── Transport constants ───────────────────────────────────────────────────
    public static final int TRANSPORT_NONE    = 0;
    public static final int TRANSPORT_WIFI    = 1;
    public static final int TRANSPORT_CELL    = 2;
    public static final int TRANSPORT_BT      = 3;
    public static final int TRANSPORT_UNKNOWN = 4;

    // ── Misc ──────────────────────────────────────────────────────────────────
    private static final int MAX_CORES = 8;
    private static final int MAX_EXT   = 4;

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static volatile DeviceInfoDynamic INSTANCE;
    private final Context context;

    public DeviceInfoDynamic(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    // =========================================================================
    // COMPACT BINARY PACKET
    // =========================================================================

    public String getPacket() {
        try {
            ActivityManager.MemoryInfo mi = getMemoryInfo();
            long[]  extRaw    = getExternalVolumesRaw();
            int     extCount  = Math.min(extRaw.length / 2, MAX_EXT);
            int[]   freqs     = readCoreFreqsMhz();
            int     coreCount = Math.min(freqs.length, MAX_CORES);
            int     freqBytes = (coreCount * 12 + 7) / 8;

            ByteBuffer buf = ByteBuffer.allocate(7 + extCount * 3 + 6 + 1 + freqBytes);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // ── RAM: total MB + used/256 (3 bytes) ───────────────────────────
            buf.putShort((short) mbClamped(mi.totalMem));
            buf.put(usageByte(mi.totalMem - mi.availMem, mi.totalMem));

            // ── Internal storage: total MB + used/256 (3 bytes) ──────────────
            long intTotal = getInternalTotalBytes();
            long intFree  = getInternalFreeBytes();
            buf.putShort((short) mbClamped(intTotal));
            buf.put(usageByte(intTotal - intFree, intTotal));

            // ── External volumes: count + E×(total MB + used/256) (1+E×3 B) ──
            buf.put((byte) extCount);
            for (int i = 0; i < extCount; i++) {
                long eTotal = extRaw[i * 2];
                long eFree  = extRaw[i * 2 + 1];
                buf.putShort((short) mbClamped(eTotal));
                buf.put(usageByte(eTotal - eFree, eTotal));
            }

            // ── Network: transport + WiFi dBm (2 bytes) ───────────────────────
            buf.put((byte) getTransportType());
            buf.put(signalByte(getWifiRssi()));

            // ── LTE signal: RSRP + RSRQ + SINR (3 bytes) ─────────────────────
            short[] lte = getLteSignalRaw();
            buf.put(signalByte(lte[0]));
            buf.put(signalByte(lte[1]));
            buf.put(signalByte(lte[2]));

            // ── TA + core count (2 bytes) ──────────────────────────────────────
            buf.put((byte) getTimingAdvanceRaw());
            buf.put((byte) coreCount);

            // ── Core freqs: 12-bit packed ─────────────────────────────────────
            buf.put(packCoreFreqs(freqs, coreCount, freqBytes));

            return bytesToHex(buf.array());

        } catch (Exception e) {
            return "00000000000000"; // 7-byte zero fallback
        }
    }

    // ── Packet helpers ────────────────────────────────────────────────────────

    /** Encodes used/total as a 0–255 byte (0.39% resolution). 0xFF reserved for N/A. */
    private static byte usageByte(long used, long total) {
        if (total <= 0) return (byte) NA_UINT8;
        int v = (int) ((used * 255L) / total);
        return (byte) Math.min(v, 254); // 255 = 0xFF reserved as N/A
    }

    /** Clamps bytes → MB into [0, 65534]. 0xFFFF is the N/A sentinel. */
    private static int mbClamped(long bytes) {
        long mb = bytes >> 20; // bytes / 1048576
        if (mb <= 0)    return 0;
        return (int) Math.min(mb, 0xFFFE);
    }

    /** Narrows an int16 signal to int8. 0x7FFF (N/A) → 0x7F. */
    private static byte signalByte(short raw) {
        if (raw == (short) 0x7FFF) return NA_INT8;
        if (raw > 126 || raw < -128) return NA_INT8;
        return (byte) raw;
    }

    /** 12-bit packs core freqs into a byte array. */
    private static byte[] packCoreFreqs(int[] freqs, int count, int byteLen) {
        byte[] packed = new byte[byteLen];
        for (int i = 0; i < count; i++) {
            int mhz     = (freqs[i] <= 0 || freqs[i] >= NA_CORE_FREQ)
                    ? NA_CORE_FREQ : freqs[i];
            int bitPos  = i * 12;
            int byteIdx = bitPos / 8;
            int bitOff  = bitPos % 8;
            if (bitOff == 0) {
                packed[byteIdx]     = (byte) ((mhz >> 4) & 0xFF);
                packed[byteIdx + 1] = (byte) ((mhz & 0x0F) << 4);
            } else {
                packed[byteIdx]     |= (byte) ((mhz >> 8) & 0x0F);
                packed[byteIdx + 1]  = (byte) (mhz & 0xFF);
            }
        }
        return packed;
    }

    // =========================================================================
    // FULL JSON  (About overlay / debug)
    // =========================================================================

    public JSONObject getAll() {
        JSONObject root = new JSONObject();
        try {
            root.put("RAM",       getRamUsage());
            root.put("Storage",   getStorageUsage());
            root.put("Network",   getNetworkInfo());
            root.put("LTESignal", getLteSignal());
            root.put("CPUFreq",   getPerCoreFrequency());
        } catch (JSONException ignored) {}
        return root;
    }

    // =========================================================================
    // RAM
    // =========================================================================

    /** Single MemoryInfo read — avoids calling getSystemService twice per packet. */
    private ActivityManager.MemoryInfo getMemoryInfo() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        return mi;
    }

    public JSONObject getRamUsage() throws JSONException {
        ActivityManager.MemoryInfo mi = getMemoryInfo();
        long used = mi.totalMem - mi.availMem;
        JSONObject json = new JSONObject();
        json.put("TotalBytes", mi.totalMem);
        json.put("FreeBytes",  mi.availMem);
        json.put("UsedBytes",  used);
        json.put("Total",      formatSize(mi.totalMem));
        json.put("Free",       formatSize(mi.availMem));
        json.put("Used",       formatSize(used));
        json.put("Usage",      percent(used, mi.totalMem) + "%");
        return json;
    }

    // =========================================================================
    // STORAGE
    // =========================================================================

    private long getInternalTotalBytes() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            return fs.getBlockCountLong() * fs.getBlockSizeLong();
        } catch (Exception e) { return 0; }
    }

    private long getInternalFreeBytes() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            return fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
        } catch (Exception e) { return 0; }
    }

    /** Flat pairs [total0, free0, total1, free1 …] in bytes. Empty if none found. */
    private long[] getExternalVolumesRaw() {
        List<long[]> vols = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume sv : sm.getStorageVolumes()) {
                    if (sv.isPrimary()) continue;
                    File path = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? sv.getDirectory() : null;
                    if (path == null || !path.exists() || !path.canRead()) continue;
                    try {
                        StatFs fs = new StatFs(path.getAbsolutePath());
                        vols.add(new long[]{
                                fs.getBlockCountLong()     * fs.getBlockSizeLong(),
                                fs.getAvailableBlocksLong() * fs.getBlockSizeLong()
                        });
                    } catch (Exception ignored) {}
                }
            }
        }

        // Pre-API-24 fallback
        if (vols.isEmpty()) {
            for (String p : new String[]{
                    "/storage/sdcard1", "/storage/extSdCard",
                    "/storage/external_SD", "/mnt/extsd"}) {
                File f = new File(p);
                if (!f.exists() || !f.canRead()) continue;
                try {
                    StatFs fs = new StatFs(p);
                    vols.add(new long[]{
                            fs.getBlockCountLong()     * fs.getBlockSizeLong(),
                            fs.getAvailableBlocksLong() * fs.getBlockSizeLong()
                    });
                    break;
                } catch (Exception ignored) {}
            }
        }

        long[] result = new long[vols.size() * 2];
        for (int i = 0; i < vols.size(); i++) {
            result[i * 2]     = vols.get(i)[0];
            result[i * 2 + 1] = vols.get(i)[1];
        }
        return result;
    }

    public JSONObject getStorageUsage() throws JSONException {
        JSONObject json     = new JSONObject();
        long       intTotal = getInternalTotalBytes();
        long       intFree  = getInternalFreeBytes();
        long       intUsed  = intTotal - intFree;

        JSONObject internal = new JSONObject();
        internal.put("TotalBytes", intTotal);
        internal.put("FreeBytes",  intFree);
        internal.put("UsedBytes",  intUsed);
        internal.put("Total",      formatSize(intTotal));
        internal.put("Free",       formatSize(intFree));
        internal.put("Used",       formatSize(intUsed));
        internal.put("Usage",      percent(intUsed, intTotal) + "%");
        json.put("Internal", internal);

        long[]    extRaw   = getExternalVolumesRaw();
        JSONArray extArr   = new JSONArray();
        for (int i = 0; i < extRaw.length / 2; i++) {
            long total = extRaw[i * 2], free = extRaw[i * 2 + 1], used = total - free;
            JSONObject vol = new JSONObject();
            vol.put("Volume",     i);
            vol.put("TotalBytes", total);
            vol.put("FreeBytes",  free);
            vol.put("UsedBytes",  used);
            vol.put("Total",      formatSize(total));
            vol.put("Free",       formatSize(free));
            vol.put("Used",       formatSize(used));
            vol.put("Usage",      percent(used, total) + "%");
            extArr.put(vol);
        }
        json.put("External",    extArr);
        json.put("HasExternal", extArr.length() > 0);
        return json;
    }

    // =========================================================================
    // NETWORK
    // =========================================================================

    private int getTransportType() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return TRANSPORT_NONE;
        Network net = cm.getActiveNetwork();
        if (net == null) return TRANSPORT_NONE;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return TRANSPORT_NONE;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))      return TRANSPORT_WIFI;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))  return TRANSPORT_CELL;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return TRANSPORT_BT;
        return TRANSPORT_UNKNOWN;
    }

    /** Returns WiFi RSSI as a short, or 0x7FFF if unavailable. */
    private short getWifiRssi() {
        try {
            WifiManager wm = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return (short) 0x7FFF;
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return (short) 0x7FFF;
            int rssi = wi.getRssi();
            return rssi == Integer.MIN_VALUE ? (short) 0x7FFF : (short) rssi;
        } catch (Exception e) { return (short) 0x7FFF; }
    }

    public JSONObject getNetworkInfo() throws JSONException {
        JSONObject json = new JSONObject();
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net  = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
        if (caps == null) { json.put("Type", "Disconnected"); return json; }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wm = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi   = wm != null ? wm.getConnectionInfo() : null;
            DhcpInfo dhcp = wm != null ? wm.getDhcpInfo()       : null;
            json.put("Type", "WiFi");
            json.put("Interface", "wlan0");
            if (wi != null) {
                json.put("SSID",      wi.getSSID().replace("\"", ""));
                json.put("LinkSpeed", wi.getLinkSpeed() + " Mbps");
                int f = wi.getFrequency();
                json.put("Frequency", f + " MHz");
                if      (f >= 2400 && f <= 2500) json.put("Band", "2.4 GHz");
                else if (f >= 4900 && f <= 5900) json.put("Band", "5 GHz");
                else if (f >= 5925 && f <= 7125) json.put("Band", "6 GHz");
            }
            if (dhcp != null) {
                json.put("IP",      intToIp(dhcp.ipAddress));
                json.put("Gateway", intToIp(dhcp.gateway));
                json.put("DNS1",    intToIp(dhcp.dns1));
                json.put("DNS2",    intToIp(dhcp.dns2));
            }
            json.put("IPv6", getAnyIPv6());
            return json;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            json.put("Type", "Cellular");
            if (tm != null) {
                json.put("Carrier", tm.getNetworkOperatorName());
                if (ActivityCompat.checkSelfPermission(
                        context, Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    json.put("NetworkType", tm.getDataNetworkType());
                }
            }
            json.put("IP",   getAnyIPv4());
            json.put("IPv6", getAnyIPv6());
            return json;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            json.put("Type", "Bluetooth PAN");
            json.put("IP",   getAnyIPv4());
            return json;
        }
        json.put("Type", "Unknown");
        return json;
    }

    // =========================================================================
    // LTE SIGNAL
    // =========================================================================

    /** Returns [RSRP, RSRQ, SINR] as int16; 0x7FFF for any unavailable field. */
    private short[] getLteSignalRaw() {
        short[] r = {(short) 0x7FFF, (short) 0x7FFF, (short) 0x7FFF};
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return r;
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return r;
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells == null) return r;
            for (CellInfo c : cells) {
                if (!(c instanceof CellInfoLte) || !c.isRegistered()) continue;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    CellSignalStrengthLte ss = ((CellInfoLte) c).getCellSignalStrength();
                    r[0] = (short) ss.getRsrp();
                    r[1] = (short) ss.getRsrq();
                    r[2] = (short) ss.getRssnr();
                }
                break;
            }
        } catch (Exception ignored) {}
        return r;
    }

    private int getTimingAdvanceRaw() {
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return NA_UINT8;
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return NA_UINT8;
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells == null) return NA_UINT8;
            for (CellInfo c : cells) {
                if (!(c instanceof CellInfoLte) || !c.isRegistered()) continue;
                int ta = ((CellInfoLte) c).getCellSignalStrength().getTimingAdvance();
                return (ta < 0 || ta > 254) ? NA_UINT8 : ta;
            }
        } catch (Exception ignored) {}
        return NA_UINT8;
    }

    public JSONObject getLteSignal() throws JSONException {
        JSONObject root = new JSONObject();
        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) { root.put("LTE", "Unavailable"); return root; }
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            root.put("LTE", "PermissionDenied");
            return root;
        }
        List<CellInfo> cells = tm.getAllCellInfo();
        if (cells == null || cells.isEmpty()) { root.put("LTE", "Unavailable"); return root; }

        JSONObject serving   = new JSONObject();
        JSONArray  neighbors = new JSONArray();
        for (CellInfo c : cells) {
            if (!(c instanceof CellInfoLte)) continue;
            CellSignalStrengthLte ss = ((CellInfoLte) c).getCellSignalStrength();
            JSONObject target = c.isRegistered() ? serving : new JSONObject();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                target.put("RSRP", ss.getRsrp());
                target.put("RSRQ", ss.getRsrq());
                target.put("SINR", ss.getRssnr());
            } else {
                target.put("RSRP", "Unavailable");
                target.put("RSRQ", "Unavailable");
                target.put("SINR", "Unavailable");
            }
            int ta = ss.getTimingAdvance();
            JSONObject taObj = new JSONObject();
            taObj.put("Value",                ta);
            taObj.put("ApproxDistanceMeters", ta >= 0 ? ta * 78 : "Unknown");
            target.put("TimingAdvance", taObj);
            if (!c.isRegistered()) neighbors.put(target);
        }
        JSONObject lteObj = new JSONObject();
        lteObj.put("ServingCell",   serving);
        lteObj.put("NeighborCells", neighbors);
        root.put("LTE", lteObj);
        return root;
    }

    // =========================================================================
    // CPU FREQUENCY
    // =========================================================================

    /** Returns per-core MHz; NA_CORE_FREQ for any unreadable core. */
    private int[] readCoreFreqsMhz() {
        int   count = Math.min(Runtime.getRuntime().availableProcessors(), MAX_CORES);
        int[] freq  = new int[count];
        for (int i = 0; i < count; i++) {
            String raw = readOneLine(
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (raw.isEmpty()) { freq[i] = NA_CORE_FREQ; continue; }
            try { freq[i] = Integer.parseInt(raw.trim()) / 1000; }
            catch (NumberFormatException e) { freq[i] = NA_CORE_FREQ; }
        }
        return freq;
    }

    public JSONArray getPerCoreFrequency() {
        int[]     freqs = readCoreFreqsMhz();
        JSONArray cores = new JSONArray();
        for (int i = 0; i < freqs.length; i++) {
            JSONObject core = new JSONObject();
            try {
                core.put("Core",        i);
                core.put("CurrentFreq", freqs[i] == NA_CORE_FREQ
                        ? "Unavailable" : freqs[i] + " MHz");
            } catch (JSONException ignored) {}
            cores.put(core);
        }
        return cores;
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    private static int percent(long used, long total) {
        return total == 0 ? 0 : (int) ((used * 100L) / total);
    }

    private String getAnyIPv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address)
                        return a.getHostAddress();
        } catch (Exception ignored) {}
        return "Unavailable";
    }

    private String getAnyIPv6() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet6Address) {
                        String ip  = a.getHostAddress();
                        int    idx = ip.indexOf('%');
                        return idx > 0 ? ip.substring(0, idx) : ip;
                    }
        } catch (Exception ignored) {}
        return "Unavailable";
    }

    private String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "."
                + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF);
    }

    private String formatSize(long s) {
        if (s <= 0) return "0";
        final String[] u = {"B", "KB", "MB", "GB", "TB"};
        int g = (int) (Math.log10(s) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(s / Math.pow(1024, g)) + " " + u[g];
    }

    private String readOneLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line != null ? line : "";
        } catch (Exception e) { return ""; }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}