package com.github.nikipo.ussoi.system.telemetry;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file PowerStatsProvider
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
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * Provides battery and thermal telemetry.
 * Compatible with Android 7.0+ (API 24+).
 *
 * Data collected:
 *   - Current (mA), signed: positive = charging, negative = discharging
 *   - Battery level (%)
 *   - Battery temperature (°C)
 *   - Thermal status (0–6 or 0xFF if unsupported)
 */
public class PowerStatsProvider {

    private static final String TAG = "PowerStatsProvider";

    private final Context context;
    private final BatteryManager batteryManager;
    private final PowerManager powerManager;

    // Cached last charge counter for delta-based current estimation
    private int lastCharge_uAh = Integer.MIN_VALUE;

    // Cached last battery temperature to survive null intents
    private float lastBatteryTempC = 0.0f;

    public PowerStatsProvider(Context context) {
        this.context = context.getApplicationContext();
        this.batteryManager = (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
    }

    /**
     * Returns battery current in milliamps.
     * Positive  = charging, Negative = discharging.
     * Clamped to short range [-32768, 32767].
     * Returns 0 on failure.
     */
    public short getCurrent() {
        if (batteryManager == null) return 0;

        try {
            boolean isCharging = isCharging();

            int milliAmps = 0;
            boolean usedFallback = true;

            // --- Strategy 1: delta of CHARGE_COUNTER between calls ---
            int charge_uAh = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (charge_uAh > 0 && lastCharge_uAh > 0 && charge_uAh != lastCharge_uAh) {
                int delta_uAh = lastCharge_uAh - charge_uAh;
                milliAmps = Math.abs(delta_uAh / 1000);
                usedFallback = false;
            }
            lastCharge_uAh = charge_uAh;

            // --- Strategy 2: CURRENT_NOW (instantaneous, signed on most devices) ---
            if (usedFallback) {
                int rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (rawCurrent != Integer.MIN_VALUE && rawCurrent != Integer.MAX_VALUE) {
                    int abs = Math.abs(rawCurrent);
                    // Some devices report in µA, some in mA — heuristic threshold
                    milliAmps = (abs > 50000) ? abs / 1000 : abs;
                }
            }

            milliAmps = Math.abs(milliAmps);
            if (!isCharging) milliAmps = -milliAmps;

            // Clamp to short range
            if (milliAmps > Short.MAX_VALUE) milliAmps = Short.MAX_VALUE;
            if (milliAmps < Short.MIN_VALUE) milliAmps = Short.MIN_VALUE;

            return (short) milliAmps;

        } catch (Exception e) {
            Log.e(TAG, "getCurrent failed", e);
            return 0;
        }
    }

    /**
     * Returns battery level as a percentage [0–100].
     * Returns 0 on failure.
     */
    public byte getBatteryLevel() {
        if (batteryManager == null) return 0;
        try {
            int capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return (byte) (capacity > 0 ? capacity : 0);
        } catch (Exception e) {
            Log.e(TAG, "getBatteryLevel failed", e);
            return 0;
        }
    }

    /**
     * Returns battery temperature in degrees Celsius.
     * Uses sticky ACTION_BATTERY_CHANGED broadcast (no receiver registration overhead).
     * Returns last known value on failure; 0.0f on first failure.
     */
    public float getBatteryTemperature() {
        try {
            Intent intent = context.registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return lastBatteryTempC;

            int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE) {
                lastBatteryTempC = temp / 10.0f;
            }
        } catch (Exception e) {
            Log.e(TAG, "getBatteryTemperature failed", e);
        }
        return lastBatteryTempC;
    }

    /**
     * Returns the current thermal status.
     *
     * Mapping:
     *   0 = NONE, 1 = LIGHT, 2 = MODERATE, 3 = SEVERE,
     *   4 = CRITICAL, 5 = EMERGENCY, 6 = SHUTDOWN
     *   0xFF = not supported (API < 29)
     */
    public byte getThermalStatus() {
        // PowerManager.getCurrentThermalStatus() requires API 29
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) {
            return (byte) 0xFF;
        }
        try {
            switch (powerManager.getCurrentThermalStatus()) {
                case PowerManager.THERMAL_STATUS_NONE:      return 0;
                case PowerManager.THERMAL_STATUS_LIGHT:     return 1;
                case PowerManager.THERMAL_STATUS_MODERATE:  return 2;
                case PowerManager.THERMAL_STATUS_SEVERE:    return 3;
                case PowerManager.THERMAL_STATUS_CRITICAL:  return 4;
                case PowerManager.THERMAL_STATUS_EMERGENCY: return 5;
                case PowerManager.THERMAL_STATUS_SHUTDOWN:  return 6;
                default:                                    return (byte) 0xFF;
            }
        } catch (Exception e) {
            Log.e(TAG, "getThermalStatus failed", e);
            return (byte) 0xFF;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Returns true if the device is currently charging or full.
     * Uses sticky ACTION_BATTERY_CHANGED — no persistent receiver needed.
     */
    private boolean isCharging() {
        try {
            Intent intent = context.registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return false;
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }
}
