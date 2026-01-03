package com.github.nikipo.ussoi.MacroServices;

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
import android.provider.Settings;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.List;

public class DeviceInfo {
    private static volatile DeviceInfo INSTANCE;
    private final Context context;
    private DeviceInfo(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public static DeviceInfo getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (DeviceInfo.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DeviceInfo(ctx);
                }
            }
        }
        return INSTANCE;
    }
    /* ----------------------------- PUBLIC API ----------------------------- */

    public JSONObject getAllDetailsAsJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("Device", getDeviceIdentity());
            root.put("Dashboard", getDashboardInfo());
            root.put("CPU", getCpuInfo());
            root.put("Network", getNetworkInfo());
            root.put("Mislenious", getLteRadioInfo());
        } catch (JSONException ignored) {}
        return root;
    }

    /* ----------------------------- DEVICE ----------------------------- */

    private JSONObject getDeviceIdentity() throws JSONException {
        JSONObject json = new JSONObject();

        json.put("Model", Build.MODEL);
        json.put("Manufacturer", Build.MANUFACTURER);
        json.put("Brand", Build.BRAND);
        json.put("Device", Build.DEVICE);
        json.put("Board", Build.BOARD);
        json.put("Hardware", Build.HARDWARE);
        json.put("Product", Build.PRODUCT);

        String deviceName = Settings.Global.getString(
                context.getContentResolver(), "device_name");
        if (deviceName == null) {
            deviceName = Settings.Secure.getString(
                    context.getContentResolver(), "bluetooth_name");
        }

        json.put("DeviceName", deviceName != null ? deviceName : Build.MODEL);
        json.put("AndroidVersion", Build.VERSION.RELEASE);
        json.put("SDKVersion", Build.VERSION.SDK_INT);
        json.put("BuildID", Build.DISPLAY);

        return json;
    }

    /* ----------------------------- CPU ----------------------------- */

    private JSONObject getCpuInfo() throws JSONException {
        JSONObject json = new JSONObject();

        json.put("Processor", getProcessorName());
        json.put("Architecture", System.getProperty("os.arch"));
        json.put("SupportedABIs", new JSONArray(Build.SUPPORTED_ABIS));
        json.put("Cores", Runtime.getRuntime().availableProcessors());

        boolean is64bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? android.os.Process.is64Bit()
                : System.getProperty("os.arch").contains("64");

        json.put("CPUType", is64bit ? "64 Bit" : "32 Bit");
        json.put("CoreStatus", getPerCoreFrequency());
        return json;

    }
    private JSONArray getPerCoreFrequency() {
        JSONArray cores = new JSONArray();
        int coreCount = Runtime.getRuntime().availableProcessors();

        for (int i = 0; i < coreCount; i++) {
            JSONObject core = new JSONObject();
            try {
                String path =
                        "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
                String freq = readOneLine(path);

                core.put("Core", i);

                if (!freq.isEmpty()) {
                    core.put("CurrentFreq",
                            (Integer.parseInt(freq) / 1000) + " MHz");
                } else {
                    core.put("CurrentFreq", "Unavailable");
                }

            } catch (Exception e) {
                try {
                    core.put("Core", i);
                    core.put("CurrentFreq", "Unavailable");
                } catch (JSONException ignored) {}
            }
            cores.put(core);
        }
        return cores;
    }


    /* ----------------------------- DASHBOARD ----------------------------- */

    private JSONObject getDashboardInfo() throws JSONException {
        JSONObject json = new JSONObject();

        ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();

        if (am != null) {
            am.getMemoryInfo(mi);
            long used = mi.totalMem - mi.availMem;

            JSONObject ram = new JSONObject();
            ram.put("Total", formatSize(mi.totalMem));
            ram.put("Used", formatSize(used));
            ram.put("Free", formatSize(mi.availMem));
            ram.put("Usage", (int) ((used * 100.0) / mi.totalMem) + "%");
            json.put("RAM", ram);
        }

        File path = Environment.getDataDirectory();
        StatFs fs = new StatFs(path.getPath());

        long total = fs.getBlockCountLong() * fs.getBlockSizeLong();
        long free = fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
        long used =  (total - free);
        JSONObject storage = new JSONObject();
        storage.put("Total", formatSize(total));
        storage.put("Free", formatSize(free));
        storage.put("Used", formatSize(total - free));
        storage.put("Usage", (int) ((used * 100.0) / total) + "%");
        json.put("InternalStorage", storage);

        WindowManager wm =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        if (wm != null) {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);

            JSONObject display = new JSONObject();
            display.put("Resolution", dm.widthPixels + " x " + dm.heightPixels);
            display.put("RefreshRate",
                    (int) wm.getDefaultDisplay().getRefreshRate() + " Hz");
            json.put("Display", display);
        }

        return json;
    }

    /* ----------------------------- NETWORK ----------------------------- */

    private JSONObject getNetworkInfo() throws JSONException {
        JSONObject json = new JSONObject();

        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        Network net = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;

        if (caps == null) {
            json.put("Type", "Disconnected");
            return json;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wm = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            DhcpInfo dhcp = wm != null ? wm.getDhcpInfo() : null;

            json.put("Type", "WiFi");
            json.put("Interface", "wlan0");

            if (wi != null) {
                json.put("SSID", wi.getSSID().replace("\"", ""));
                json.put("LinkSpeed", wi.getLinkSpeed() + " Mbps");
                int f = wi.getFrequency();
                json.put("Frequency", f + " MHz");

                if (f >= 2400 && f <= 2500) json.put("Band", "2.4 GHz");
                else if (f >= 4900 && f <= 5900) json.put("Band", "5 GHz");
                else if (f >= 5925 && f <= 7125) json.put("Band", "6 GHz");
            }

            if (dhcp != null) {
                json.put("IP", intToIp(dhcp.ipAddress));
                json.put("Gateway", intToIp(dhcp.gateway));
                json.put("DNS1", intToIp(dhcp.dns1));
                json.put("DNS2", intToIp(dhcp.dns2));
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
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        json.put("NetworkType", tm.getDataNetworkType());
                    }
                }
            }

            json.put("IP", getAnyIPv4());
            json.put("IPv6", getAnyIPv6());
            return json;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            json.put("Type", "Bluetooth PAN");
            json.put("IP", getAnyIPv4());
            return json;
        }

        json.put("Type", "Unknown");
        return json;
    }

    /* ----------------------------- SERVICE CONTROL ----------------------------- */

    public void stopAllServices() {
        ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        if (am == null) return;

        List<ActivityManager.RunningServiceInfo> services =
                am.getRunningServices(Integer.MAX_VALUE);

        for (ActivityManager.RunningServiceInfo s : services) {
            if (s.service.getPackageName().equals(context.getPackageName())) {
                try {
                    context.stopService(
                            new android.content.Intent().setComponent(s.service));
                } catch (Exception ignored) {}
            }
        }
    }

    /* ----------------------------- HELPERS ----------------------------- */

    private String getProcessorName() {
        try (BufferedReader br = new BufferedReader(
                new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("Hardware") || line.contains("model name")) {
                    return line.split(":")[1].trim();
                }
            }
        } catch (IOException ignored) {}
        return Build.HARDWARE;
    }

    private String getAnyIPv4() {
        try {
            for (NetworkInterface ni : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "Unavailable";
    }

    private String getAnyIPv6() {
        try {
            for (NetworkInterface ni : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet6Address) {
                        String ip = a.getHostAddress();
                        int i = ip.indexOf('%');
                        return i > 0 ? ip.substring(0, i) : ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "Unavailable";
    }

    private String intToIp(int i) {
        return (i & 0xFF) + "." +
                ((i >> 8) & 0xFF) + "." +
                ((i >> 16) & 0xFF) + "." +
                ((i >> 24) & 0xFF);
    }

    private String formatSize(long s) {
        if (s <= 0) return "0";
        final String[] u = {"B", "KB", "MB", "GB", "TB"};
        int g = (int) (Math.log10(s) / Math.log10(1024));
        return new DecimalFormat("#,##0.#")
                .format(s / Math.pow(1024, g)) + " " + u[g];
    }

    private String readOneLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Exception e) {
            return "";
        }
    }

    private JSONObject getLteRadioInfo() throws JSONException {
        JSONObject root = new JSONObject();
        JSONObject serving = new JSONObject();
        JSONArray neighbors = new JSONArray();

        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        if (tm == null) {
            root.put("LTE", "Unavailable");
            return root;
        }

        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            root.put("LTE", "PermissionDenied");
            return root;
        }

        List<CellInfo> cells = tm.getAllCellInfo();
        if (cells == null || cells.isEmpty()) {
            root.put("LTE", "Unavailable");
            return root;
        }

        for (CellInfo c : cells) {
            if (!(c instanceof CellInfoLte)) continue;

            CellInfoLte lte = (CellInfoLte) c;
            CellIdentityLte id = lte.getCellIdentity();
            CellSignalStrengthLte ss = lte.getCellSignalStrength();

            int ci = id.getCi();
            int enb = ci > 0 ? (ci >> 8) : -1;
            int cid = ci > 0 ? (ci & 0xFF) : -1;

            JSONObject target = c.isRegistered() ? serving : new JSONObject();

            target.put("CI", ci);
            target.put("eNB", enb);
            target.put("CID", cid);
            target.put("TAC", id.getTac());
            target.put("PCI", id.getPci());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                int earfcn = id.getEarfcn();
                target.put("EARFCN", earfcn);
                target.put("Band", getBandFromEarfcn(earfcn)); // Use helper method
            } else {
                target.put("EARFCN", "Unavailable");
                target.put("Band", "Unavailable");
            }

            // Signal metrics: API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                target.put("RSRP", ss.getRsrp());
                target.put("RSRQ", ss.getRsrq());
                target.put("SINR", ss.getRssnr());
            } else {
                target.put("RSRP", "Unavailable");
                target.put("RSRQ", "Unavailable");
                target.put("SINR", "Unavailable");
            }

            // Timing Advance (API 24+, but often -1)
            int ta = ss.getTimingAdvance();
            JSONObject taObj = new JSONObject();
            taObj.put("Value", ta);
            taObj.put(
                    "ApproxDistanceMeters",
                    ta >= 0 ? ta * 78 : "Unknown"
            );
            target.put("TimingAdvance", taObj);

            if (!c.isRegistered()) {
                neighbors.put(target);
            }
        }

        JSONObject lteObj = new JSONObject();
        lteObj.put("ServingCell", serving);
        lteObj.put("NeighborCells", neighbors);

        root.put("LTE", lteObj);
        return root;
    }
    private int getBandFromEarfcn(int earfcn) {
        if (earfcn > 65535) {
            // Offset for extended EARFCNs (uncommon in standard apps but possible)
            return -1;
        }

        // Reference: 3GPP TS 36.101
        if (earfcn >= 0 && earfcn <= 599) return 1;
        if (earfcn >= 600 && earfcn <= 1199) return 2;
        if (earfcn >= 1200 && earfcn <= 1949) return 3;
        if (earfcn >= 1950 && earfcn <= 2399) return 4;
        if (earfcn >= 2400 && earfcn <= 2649) return 5;
        if (earfcn >= 2650 && earfcn <= 2749) return 6;
        if (earfcn >= 2750 && earfcn <= 3449) return 7;
        if (earfcn >= 3450 && earfcn <= 3799) return 8;
        if (earfcn >= 3800 && earfcn <= 4149) return 9;
        if (earfcn >= 4150 && earfcn <= 4749) return 10;
        if (earfcn >= 4750 && earfcn <= 4949) return 11;
        if (earfcn >= 5010 && earfcn <= 5179) return 12;
        if (earfcn >= 5180 && earfcn <= 5279) return 13;
        if (earfcn >= 5280 && earfcn <= 5379) return 14;
        // ... Bands 15-16 skipped (rare/unused)
        if (earfcn >= 5730 && earfcn <= 5849) return 17;
        if (earfcn >= 5850 && earfcn <= 5999) return 18;
        if (earfcn >= 6000 && earfcn <= 6149) return 19;
        if (earfcn >= 6150 && earfcn <= 6449) return 20;
        if (earfcn >= 6450 && earfcn <= 6599) return 21;
        if (earfcn >= 6600 && earfcn <= 7399) return 22;
        if (earfcn >= 7500 && earfcn <= 7699) return 23;
        if (earfcn >= 7700 && earfcn <= 8039) return 24;
        if (earfcn >= 8040 && earfcn <= 8689) return 25;
        if (earfcn >= 8690 && earfcn <= 9039) return 26;
        if (earfcn >= 9040 && earfcn <= 9209) return 27;
        if (earfcn >= 9210 && earfcn <= 9659) return 28;
        if (earfcn >= 9660 && earfcn <= 9769) return 29;
        if (earfcn >= 9770 && earfcn <= 9869) return 30;

        // TDD Bands
        if (earfcn >= 36000 && earfcn <= 36199) return 33;
        if (earfcn >= 36200 && earfcn <= 36349) return 34;
        if (earfcn >= 36350 && earfcn <= 36949) return 35;
        if (earfcn >= 36950 && earfcn <= 37549) return 36;
        if (earfcn >= 37550 && earfcn <= 37749) return 37;
        if (earfcn >= 37750 && earfcn <= 38249) return 38;
        if (earfcn >= 38250 && earfcn <= 38649) return 39;
        if (earfcn >= 38650 && earfcn <= 39649) return 40;
        if (earfcn >= 39650 && earfcn <= 41589) return 41;
        if (earfcn >= 41590 && earfcn <= 43589) return 42;
        if (earfcn >= 43590 && earfcn <= 45589) return 43;
        if (earfcn >= 45590 && earfcn <= 46589) return 44;
        if (earfcn >= 46590 && earfcn <= 46789) return 46;
        if (earfcn >= 46790 && earfcn <= 54539) return 47;
        if (earfcn >= 54540 && earfcn <= 55239) return 48;

        // Band 66 (AWS-3) - Very common in North America
        if (earfcn >= 66436 && earfcn <= 67335) return 66;

        // Band 71 (600 MHz) - Common T-Mobile US
        if (earfcn >= 68586 && earfcn <= 68935) return 71;

        return 0; // Unknown
    }
}
