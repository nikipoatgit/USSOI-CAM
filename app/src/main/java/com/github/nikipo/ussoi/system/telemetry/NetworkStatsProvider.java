package com.github.nikipo.ussoi.system.telemetry;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file NetworkStatsProvider
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

import android.content.Context;
import android.net.TrafficStats;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.content.pm.PackageManager;

/**
 * Provides network throughput and data-consumption telemetry for this app's UID.
 * Compatible with Android 7.0+ (API 24+).
 *
 * Call init() once (e.g. from SysTelemetry.startMonitoring()) to reset counters.
 * Call snapshot() before reading throughput values — it recalculates rates.
 *
 * All public getters are thread-safe.
 */
public class NetworkStatsProvider {

    private static final String TAG = "NetworkStatsProvider";
    private static final double KB = 1024.0;
    private static final double MB = 1024.0 * 1024.0;

    private final Context context;
    private final int myUid;
    private final TelephonyManager telephonyManager;

    // Written only inside synchronized snapshot(); read by getters (also synchronized)
    private long lastTxBytes = 0;
    private long lastRxBytes = 0;
    private long lastTimestampMs = 0;

    private double sessionUploadBytes = 0;
    private double sessionDownloadBytes = 0;

    // Latest computed rates — updated by snapshot()
    private float uploadKbps = 0f;
    private float downloadKbps = 0f;

    public NetworkStatsProvider(Context context) {
        this.context = context.getApplicationContext();
        this.myUid = android.os.Process.myUid();
        this.telephonyManager =
                (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Initialises (or resets) baseline counters and session accumulators.
     * Call once when monitoring starts.
     */
    public synchronized void init() {
        long tx = TrafficStats.getUidTxBytes(myUid);
        long rx = TrafficStats.getUidRxBytes(myUid);

        lastTxBytes = (tx == TrafficStats.UNSUPPORTED) ? 0 : tx;
        lastRxBytes = (rx == TrafficStats.UNSUPPORTED) ? 0 : rx;
        lastTimestampMs = System.currentTimeMillis();

        sessionUploadBytes = 0;
        sessionDownloadBytes = 0;
        uploadKbps = 0f;
        downloadKbps = 0f;
    }

    /**
     * Reads current TrafficStats counters, computes instantaneous throughput,
     * and accumulates session totals.
     *
     * Call this once per telemetry cycle before reading upload/download values.
     */
    public synchronized void snapshot() {
        long currentTx = TrafficStats.getUidTxBytes(myUid);
        long currentRx = TrafficStats.getUidRxBytes(myUid);
        long currentTs = System.currentTimeMillis();

        if (currentTx == TrafficStats.UNSUPPORTED || currentRx == TrafficStats.UNSUPPORTED) {
            // Platform doesn't support per-UID stats
            return;
        }

        long deltaMs = currentTs - lastTimestampMs;
        if (deltaMs <= 0) return;

        long txDiff = currentTx - lastTxBytes;
        long rxDiff = currentRx - lastRxBytes;

        // Guard against counter resets (e.g. after process restart)
        if (txDiff < 0) txDiff = 0;
        if (rxDiff < 0) rxDiff = 0;

        // Bytes/ms → KB/s
        uploadKbps   = (float) ((txDiff * 1000.0) / deltaMs / KB);
        downloadKbps = (float) ((rxDiff * 1000.0) / deltaMs / KB);

        sessionUploadBytes   += txDiff;
        sessionDownloadBytes += rxDiff;

        lastTxBytes = currentTx;
        lastRxBytes = currentRx;
        lastTimestampMs = currentTs;
    }

    /**
     * Upload throughput in KB/s since the last snapshot() call.
     */
    public synchronized float getUploadKBps() {
        return uploadKbps;
    }

    /**
     * Download throughput in KB/s since the last snapshot() call.
     */
    public synchronized float getDownloadKBps() {
        return downloadKbps;
    }

    /**
     * Total data consumed (upload + download) this session, in MB.
     */
    public synchronized float getSessionConsumptionMB() {
        return (float) ((sessionUploadBytes + sessionDownloadBytes) / MB);
    }

    /**
     * Returns the voice-network type constant (TelephonyManager.NETWORK_TYPE_*).
     * Returns 0xFF as byte if READ_PHONE_STATE is not granted or on error.
     *
     * Note: deprecated on API 30+ in favour of getDataNetworkType(),
     * but kept for the voice-network field in the packet.
     */
    public byte getNetworkType() {
        if (telephonyManager == null) return (byte) 0xFF;
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return (byte) 0xFF;
        }
        try {
            //noinspection deprecation
            return (byte) telephonyManager.getNetworkType();
        } catch (Exception e) {
            Log.w(TAG, "getNetworkType failed", e);
            return (byte) 0xFF;
        }
    }

    /**
     * Returns the data-network type constant (TelephonyManager.NETWORK_TYPE_*).
     * Uses getDataNetworkType() on API 24+ (requires READ_PHONE_STATE).
     * Returns 0xFF on error or missing permission.
     */
    public byte getDataNetworkType() {
        if (telephonyManager == null) return (byte) 0xFF;
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return (byte) 0xFF;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return (byte) telephonyManager.getDataNetworkType();
            }
            // API 24 doesn't have getDataNetworkType() — use the combined one
            //noinspection deprecation
            return (byte) telephonyManager.getNetworkType();
        } catch (Exception e) {
            Log.w(TAG, "getDataNetworkType failed", e);
            return (byte) 0xFF;
        }
    }
}
