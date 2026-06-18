package com.github.nikipo.ussoi.system.telemetry;


import android.content.Context;
import android.util.Log;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file SysTelemetry
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
 * Singleton orchestrator for device telemetry collection.
 * Compatible with Android 7.0+ (API 24+).
 *
 * Usage:
 *   SysTelemetry telemetry = SysTelemetry.getInstance(context);
 *   telemetry.startMonitoring();           // once, e.g. in Service.onCreate()
 *   String packet = telemetry.getPacket(); // call periodically
 *   telemetry.stopMonitoring();            // once, e.g. in Service.onDestroy()
 *
 * Permissions required in AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
 *   <uses-permission android:name="android.permission.READ_PHONE_STATE"/>
 *   <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
 *   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
 *
 * Runtime permissions that must be granted before startMonitoring():
 *   - ACCESS_FINE_LOCATION
 *   - READ_PHONE_STATE
 */
public class SysTelemetry {

    private static final String TAG = "SysTelemetry";

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile SysTelemetry instance;

    public static SysTelemetry getInstance(Context context) {
        if (instance == null) {
            synchronized (SysTelemetry.class) {
                if (instance == null) {
                    instance = new SysTelemetry(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    private final PowerStatsProvider powerStats;
    private final LocationProvider location;
    private final SignalStrengthProvider signalStrength;
    private final NetworkStatsProvider networkStats;
    private final TelemetryPacketBuilder packetBuilder;

    private volatile boolean monitoring = false;

    private SysTelemetry(Context context) {
        powerStats     = new PowerStatsProvider(context);
        location       = new LocationProvider(context);
        signalStrength = new SignalStrengthProvider(context);
        networkStats   = new NetworkStatsProvider(context);
        packetBuilder  = new TelemetryPacketBuilder(powerStats, signalStrength, networkStats, location);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts all background listeners and initialises counters.
     * Must be called from the main thread.
     * Safe to call multiple times — each provider guards against double-registration.
     */
    public synchronized void startMonitoring() {
        if (monitoring) {
            Log.d(TAG, "Already monitoring — ignoring startMonitoring()");
            return;
        }
        location.startUpdates();
        signalStrength.startListening();
        networkStats.init();
        monitoring = true;
        Log.d(TAG, "Monitoring started");
    }

    /**
     * Stops all listeners and frees resources.
     * Safe to call from any thread.
     */
    public synchronized void close() {
        if (!monitoring) return;
        location.stopUpdates();
        signalStrength.stopListening();
        monitoring = false;
        Log.d(TAG, "Monitoring stopped");
    }

    // ── Data access ───────────────────────────────────────────────────────────

    /**
     * Returns a 116-character uppercase hex string representing the current
     * telemetry snapshot (58 bytes, little-endian).
     *
     * See {@link TelemetryPacketBuilder} for the full protocol layout.
     * Never throws — returns a zero-filled fallback string on failure.
     */
    public String getPacket() {
        return packetBuilder.build();
    }

    // ── Optional direct accessors (for debugging / display) ───────────────────

    /** Current battery current in mA (signed). */
    public short getBatteryCurrent()     { return powerStats.getCurrent(); }

    /** Battery level 0–100. */
    public byte  getBatteryLevel()       { return powerStats.getBatteryLevel(); }

    /** Battery temperature in °C. */
    public float getBatteryTemperature() { return powerStats.getBatteryTemperature(); }

    /** Thermal status byte (0–6 or 0xFF). */
    public byte  getThermalStatus()      { return powerStats.getThermalStatus(); }

    /** Cellular signal in dBm. */
    public short getCellularSignalDbm()  { return signalStrength.getCellularSignalDbm(); }

    /** Wi-Fi signal in dBm. */
    public short getWifiSignalDbm()      { return signalStrength.getWifiSignalDbm(); }

    /** Whether Wi-Fi is the active network transport. */
    public boolean isWifiConnected()     { return signalStrength.isWifiConnected(); }

    /** Upload throughput in KB/s (from last packet build). */
    public float getUploadKbps()         { return networkStats.getUploadKBps(); }

    /** Download throughput in KB/s (from last packet build). */
    public float getDownloadKbps()       { return networkStats.getDownloadKBps(); }

    /** Total session data usage in MB. */
    public float getSessionMb()          { return networkStats.getSessionConsumptionMB(); }
}
