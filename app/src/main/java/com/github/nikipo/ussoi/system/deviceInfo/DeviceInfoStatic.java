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
            root.put("Sim",  getSimInfo());
        } catch (JSONException ignored) {}
        return root;
    }

    private JSONObject getSimInfo() throws JSONException {
        JSONObject json = new JSONObject();

        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        if (tm == null) {
            json.put("Status", "Unavailable");
            return json;
        }

        json.put("SIMState", getSimStateString(tm.getSimState()));
        json.put("NetworkOperator", tm.getNetworkOperatorName());
        json.put("CountryISO", tm.getSimCountryIso());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            json.put("CarrierId", tm.getSimCarrierId());
            json.put("CarrierName", String.valueOf(tm.getSimCarrierIdName()));
        }

        json.put("PhoneType", getPhoneTypeString(tm.getPhoneType()));

        return json;
    }
    private String getSimStateString(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_READY:
                return "READY";
            case TelephonyManager.SIM_STATE_ABSENT:
                return "ABSENT";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return "PIN_REQUIRED";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return "PUK_REQUIRED";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return "NETWORK_LOCKED";
            default:
                return "UNKNOWN";
        }
    }

    private String getPhoneTypeString(int type) {
        switch (type) {
            case TelephonyManager.PHONE_TYPE_GSM:
                return "GSM";
            case TelephonyManager.PHONE_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.PHONE_TYPE_SIP:
                return "SIP";
            default:
                return "NONE";
        }
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

}
