package com.github.nikipo.ussoi.system.telemetry;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file TelemetryPacketBuilder
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

import android.location.Location;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file TelemetryPacketBuilder
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


//Field,Offset (byte),Size (bytes),Type,Raw Value,Scale / Conversion,Final Unit
//Current,0,2,int16,raw,raw,mA
//Battery Level,2,1,uint8,raw,raw,%
//Temperature,3,4,float32,raw,raw,°C
//Thermal Status,7,1,uint8,raw,raw,enum
//
//Cellular dBm,8,2,int16,raw,raw,dBm
//WiFi dBm,10,2,int16,raw,raw,dBm
//Network Type,12,1,uint8,raw,raw,enum
//Data Network Type,13,1,uint8,raw,raw,enum
//
//Upload Speed,14,4,int32,raw,raw / 100,KB/s
//Download Speed,18,4,int32,raw,raw / 100,KB/s
//Session Usage,22,4,int32,raw,raw / 100,MB
//
//Latitude,26,4,float32,raw,raw,degrees
//Longitude,30,4,float32,raw,raw,degrees
//Accuracy,34,4,float32,raw,raw,meters
//Speed,38,4,float32,raw,raw,m/s
//Altitude,42,4,float32,raw,raw,meters
public class TelemetryPacketBuilder {

    private static final String TAG = "TelemetryPacketBuilder";

    public static final int PACKET_BYTES = 46;
    public static final int PACKET_HEX_LENGTH = PACKET_BYTES * 2;

    /**
     * Fallback returned when build() fails entirely — 116 hex '0' chars.
     * Receivers can detect this as a null packet.
     */
    public static final String FALLBACK_PACKET = repeat('0', PACKET_HEX_LENGTH);

    private final PowerStatsProvider powerStats;
    private final SignalStrengthProvider signalStrength;
    private final NetworkStatsProvider networkStats;
    private final LocationProvider location;

    public TelemetryPacketBuilder(
            PowerStatsProvider powerStats,
            SignalStrengthProvider signalStrength,
            NetworkStatsProvider networkStats,
            LocationProvider location) {
        this.powerStats = powerStats;
        this.signalStrength = signalStrength;
        this.networkStats = networkStats;
        this.location = location;
    }

    /**
     * Assembles and returns the 116-character hex telemetry string.
     * Never throws — returns FALLBACK_PACKET on catastrophic failure.
     */

    public String build() {
        try {
            networkStats.snapshot();

            ByteBuffer buf = ByteBuffer.allocate(PACKET_BYTES);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // ── 1. Power (8B)
            buf.putShort(powerStats.getCurrent());
            buf.put(powerStats.getBatteryLevel());

            float temp = powerStats.getBatteryTemperature();
            buf.putFloat(Float.isNaN(temp) ? 0f : temp);

            buf.put(powerStats.getThermalStatus());

            // ── 2. Telecom (6B)
            buf.putShort((short) signalStrength.getCellularSignalDbm());
            buf.putShort((short) signalStrength.getWifiSignalDbm());
            buf.put(networkStats.getNetworkType());
            buf.put(networkStats.getDataNetworkType());

            // ── 3. Network (12B) → scaled ints
            buf.putInt((int)(networkStats.getUploadKBps() * 100));   // KB/s *100
            buf.putInt((int)(networkStats.getDownloadKBps() * 100)); // KB/s *100
            buf.putInt((int)(networkStats.getSessionConsumptionMB() * 100)); // MB *100

            // ── 4. Location (20B)
            Location loc = location.getLastLocation();

            if (loc != null) {
                buf.putFloat((float) loc.getLatitude());
                buf.putFloat((float) loc.getLongitude());
                buf.putFloat(loc.hasAccuracy() ? loc.getAccuracy() : 0f);
                buf.putFloat(loc.hasSpeed()    ? loc.getSpeed()    : 0f);
                buf.putFloat(loc.hasAltitude() ? (float) loc.getAltitude() : 0f);
            } else {
                buf.putFloat(0f); // lat
                buf.putFloat(0f); // lon
                buf.putFloat(0f); // acc
                buf.putFloat(0f); // speed
                buf.putFloat(0f); // alt
            }

            return bytesToHex(buf.array());

        } catch (Exception e) {
            Log.e(TAG, "build() failed", e);
            return FALLBACK_PACKET;
        }
    }

    // -------------------------------------------------------------------------

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** Simple repeat helper — avoids requiring API 26 String.repeat(). */
    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }
}
