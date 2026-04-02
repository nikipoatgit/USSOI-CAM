package com.github.nikipo.ussoi.system.deviceInfo;

import android.Manifest;
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
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.List;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file DeviceInfoStatic
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
 * Collects device properties that do NOT change at runtime:
 *   - Hardware identity (model, board, CPU, ABIs)
 *   - Display resolution & refresh rate
 *   - LTE cell identity (CI, eNB, CID, TAC, PCI, EARFCN, Band)
 *
 * Build once at startup and cache; no need to refresh periodically.
 */
public class DeviceInfoStatic {
    private final Context context;
    public DeviceInfoStatic(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a JSON snapshot of all static properties. */
    public JSONObject getAll() {
        JSONObject root = new JSONObject();
        try {
            root.put("Device",   getDeviceIdentity());
            root.put("CPU",      getCpuInfo());
            root.put("Display",  getDisplayInfo());
            root.put("LTECell",  getLteCellIdentity());
        } catch (JSONException ignored) {}
        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device identity
    // ─────────────────────────────────────────────────────────────────────────

    private JSONObject getDeviceIdentity() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("Model",          Build.MODEL);
        json.put("Manufacturer",   Build.MANUFACTURER);
        json.put("Brand",          Build.BRAND);
        json.put("Device",         Build.DEVICE);
        json.put("Board",          Build.BOARD);
        json.put("Hardware",       Build.HARDWARE);
        json.put("Product",        Build.PRODUCT);

        String deviceName = Settings.Global.getString(
                context.getContentResolver(), "device_name");
        if (deviceName == null) {
            deviceName = Settings.Secure.getString(
                    context.getContentResolver(), "bluetooth_name");
        }
        json.put("DeviceName",     deviceName != null ? deviceName : Build.MODEL);
        json.put("AndroidVersion", Build.VERSION.RELEASE);
        json.put("SDKVersion",     Build.VERSION.SDK_INT);
        json.put("BuildID",        Build.DISPLAY);
        return json;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CPU — core count, ABI list, architecture; NOT per-core frequency
    // ─────────────────────────────────────────────────────────────────────────

    private JSONObject getCpuInfo() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("Processor",    getProcessorName());
        json.put("Architecture", System.getProperty("os.arch"));
        json.put("SupportedABIs", new JSONArray(Build.SUPPORTED_ABIS));
        json.put("Cores",        Runtime.getRuntime().availableProcessors());

        boolean is64bit = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? android.os.Process.is64Bit()
                : System.getProperty("os.arch").contains("64");
        json.put("CPUType", is64bit ? "64 Bit" : "32 Bit");
        return json;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Display — resolution and refresh rate are fixed for a given device
    // ─────────────────────────────────────────────────────────────────────────

    private JSONObject getDisplayInfo() throws JSONException {
        JSONObject json = new JSONObject();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            json.put("Resolution",   dm.widthPixels + " x " + dm.heightPixels);
            json.put("RefreshRate",  (int) wm.getDefaultDisplay().getRefreshRate() + " Hz");
        }
        return json;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LTE cell identity — CI/eNB/CID/TAC/PCI/EARFCN/Band don't change unless
    // the device hands off to a different tower (handled by DeviceInfoDynamic
    // for signal, but identity itself is treated as semi-static here)
    // ─────────────────────────────────────────────────────────────────────────

    private JSONObject getLteCellIdentity() throws JSONException {
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

        for (CellInfo c : cells) {
            if (!(c instanceof CellInfoLte) || !c.isRegistered()) continue;

            CellIdentityLte id = ((CellInfoLte) c).getCellIdentity();
            int ci  = id.getCi();
            int enb = ci > 0 ? (ci >> 8)   : -1;
            int cid = ci > 0 ? (ci & 0xFF) : -1;

            JSONObject serving = new JSONObject();
            serving.put("CI",  ci);
            serving.put("eNB", enb);
            serving.put("CID", cid);
            serving.put("TAC", id.getTac());
            serving.put("PCI", id.getPci());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                int earfcn = id.getEarfcn();
                serving.put("EARFCN", earfcn);
                serving.put("Band",   getBandFromEarfcn(earfcn));
            } else {
                serving.put("EARFCN", "Unavailable");
                serving.put("Band",   "Unavailable");
            }

            root.put("ServingCell", serving);
            break; // only need the registered cell
        }
        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String getProcessorName() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("Hardware") || line.contains("model name")) {
                    return line.split(":")[1].trim();
                }
            }
        } catch (IOException ignored) {}
        return Build.HARDWARE;
    }

    /** 3GPP TS 36.101 EARFCN → LTE band number. */
    private int getBandFromEarfcn(int earfcn) {
        if (earfcn >= 0     && earfcn <= 599)   return 1;
        if (earfcn >= 600   && earfcn <= 1199)  return 2;
        if (earfcn >= 1200  && earfcn <= 1949)  return 3;
        if (earfcn >= 1950  && earfcn <= 2399)  return 4;
        if (earfcn >= 2400  && earfcn <= 2649)  return 5;
        if (earfcn >= 2650  && earfcn <= 2749)  return 6;
        if (earfcn >= 2750  && earfcn <= 3449)  return 7;
        if (earfcn >= 3450  && earfcn <= 3799)  return 8;
        if (earfcn >= 3800  && earfcn <= 4149)  return 9;
        if (earfcn >= 4150  && earfcn <= 4749)  return 10;
        if (earfcn >= 4750  && earfcn <= 4949)  return 11;
        if (earfcn >= 5010  && earfcn <= 5179)  return 12;
        if (earfcn >= 5180  && earfcn <= 5279)  return 13;
        if (earfcn >= 5280  && earfcn <= 5379)  return 14;
        if (earfcn >= 5730  && earfcn <= 5849)  return 17;
        if (earfcn >= 5850  && earfcn <= 5999)  return 18;
        if (earfcn >= 6000  && earfcn <= 6149)  return 19;
        if (earfcn >= 6150  && earfcn <= 6449)  return 20;
        if (earfcn >= 6450  && earfcn <= 6599)  return 21;
        if (earfcn >= 6600  && earfcn <= 7399)  return 22;
        if (earfcn >= 7500  && earfcn <= 7699)  return 23;
        if (earfcn >= 7700  && earfcn <= 8039)  return 24;
        if (earfcn >= 8040  && earfcn <= 8689)  return 25;
        if (earfcn >= 8690  && earfcn <= 9039)  return 26;
        if (earfcn >= 9040  && earfcn <= 9209)  return 27;
        if (earfcn >= 9210  && earfcn <= 9659)  return 28;
        if (earfcn >= 9660  && earfcn <= 9769)  return 29;
        if (earfcn >= 9770  && earfcn <= 9869)  return 30;
        // TDD bands
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
        if (earfcn >= 66436 && earfcn <= 67335) return 66;
        if (earfcn >= 68586 && earfcn <= 68935) return 71;
        return 0;
    }
}
