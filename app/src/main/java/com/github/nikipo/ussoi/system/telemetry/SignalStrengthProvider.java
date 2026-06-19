package com.github.nikipo.ussoi.system.telemetry;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file SignalStrengthProvider
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


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

/**
 * Provides cellular and Wi-Fi signal strength telemetry.
 * Compatible with Android 7.0+ (API 24+).
 *
 * - Uses TelephonyCallback on API 31+ (Android 12+)
 * - Falls back to PhoneStateListener on API 24–30
 * - WifiManager.getConnectionInfo() used on API < 31;
 *   NetworkCapabilities.getTransportInfo() used on API 31+
 *
 * Requires: READ_PHONE_STATE, ACCESS_WIFI_STATE permissions.
 */
public class SignalStrengthProvider {

    private static final String TAG = "SignalStrengthProvider";
    private static final short DBM_UNKNOWN = -127;

    private final Context context;
    private final TelephonyManager telephonyManager;

    private volatile SignalStrength lastSignalStrength;
    private boolean isListening = false;

    // API 31+ listener
    private TelephonyCallback modernListener;

    // API 24–30 listener
    private PhoneStateListener legacyListener;

    public SignalStrengthProvider(Context context) {
        this.context = context.getApplicationContext();
        this.telephonyManager = (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Starts listening for signal strength changes.
     * Safe to call multiple times — registers only once.
     * Must be called from the main thread.
     */
    public synchronized void startListening() {
        if (isListening || telephonyManager == null) return;

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "READ_PHONE_STATE not granted — signal strength unavailable");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startModernListener();
        } else {
            startLegacyListener();
        }
        isListening = true;
    }

    /**
     * Stops listening and releases the listener.
     */
    public synchronized void stopListening() {
        if (!isListening || telephonyManager == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modernListener != null) {
                telephonyManager.unregisterTelephonyCallback(modernListener);
                modernListener = null;
            } else if (legacyListener != null) {
                //noinspection deprecation
                telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
                legacyListener = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopListening failed", e);
        } finally {
            isListening = false;
        }
    }

    /**
     * Returns the strongest reported cellular signal in dBm.
     * Returns DBM_UNKNOWN (-127) if no reading is available.
     */
    public short getCellularSignalDbm() {
        if (lastSignalStrength == null) return DBM_UNKNOWN;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: iterate all reported cells and pick the strongest
                int bestDbm = Integer.MIN_VALUE;
                for (android.telephony.CellSignalStrength css
                        : lastSignalStrength.getCellSignalStrengths()) {
                    int dbm = css.getDbm();
                    if (dbm != Integer.MAX_VALUE && dbm > bestDbm) {
                        bestDbm = dbm;
                    }
                }
                return (short) (bestDbm != Integer.MIN_VALUE ? bestDbm : DBM_UNKNOWN);
            }

            // Pre-API 29: GSM ASU fallback
            //noinspection deprecation
            int gsmAsu = lastSignalStrength.getGsmSignalStrength();
            if (gsmAsu >= 0 && gsmAsu != 99) {
                return (short) (-113 + (2 * gsmAsu));
            }
        } catch (Exception e) {
            Log.w(TAG, "getCellularSignalDbm failed", e);
        }

        return DBM_UNKNOWN;
    }

    /**
     * Returns the Wi-Fi RSSI in dBm if Wi-Fi is the active transport,
     * or DBM_UNKNOWN (-127) otherwise.
     */
    public short getWifiSignalDbm() {
        if (!isWifiConnected()) return DBM_UNKNOWN;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getWifiRssiModern();
        } else {
            return getWifiRssiLegacy();
        }
    }

    /**
     * Returns true if Wi-Fi is the currently active network transport.
     */
    public boolean isWifiConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void startModernListener() {

        if (modernListener == null) {
            modernListener = new SignalStrengthsCallback();
        }

        telephonyManager.registerTelephonyCallback(
                context.getMainExecutor(),   // ensures main thread
                modernListener
        );
    }
    @RequiresApi(api = Build.VERSION_CODES.S)
    private class SignalStrengthsCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener {
        @Override
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
            lastSignalStrength = signalStrength;
        }
    }

    @SuppressWarnings("deprecation")
    private void startLegacyListener() {
        legacyListener = new PhoneStateListener() {
            @Override
            public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                super.onSignalStrengthsChanged(signalStrength);
                lastSignalStrength = signalStrength;
            }
        };
        telephonyManager.listen(
                legacyListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private short getWifiRssiModern() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return DBM_UNKNOWN;

            Network network = cm.getActiveNetwork();
            if (network == null) return DBM_UNKNOWN;

            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return DBM_UNKNOWN;

            android.net.TransportInfo ti = caps.getTransportInfo();
            if (ti instanceof WifiInfo) {
                return (short) ((WifiInfo) ti).getRssi();
            }
        } catch (Exception e) {
            Log.w(TAG, "getWifiRssiModern failed", e);
        }
        return DBM_UNKNOWN;
    }

    @SuppressWarnings("deprecation")
    private short getWifiRssiLegacy() {
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return DBM_UNKNOWN;

            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return DBM_UNKNOWN;

            return (short) info.getRssi();
        } catch (Exception e) {
            Log.w(TAG, "getWifiRssiLegacy failed", e);
            return DBM_UNKNOWN;
        }
    }
}
