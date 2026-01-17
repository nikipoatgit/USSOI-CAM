package com.github.nikipo.ussoi.MacroServices;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ClientInfoProvider {
    private final String TAG = "ClientInfoProvider";
    private static volatile ClientInfoProvider instance;
    private final Context context;

    // System Service Managers
    private final BatteryManager batteryManager;
    private final LocationManager locationManager;
    private final TelephonyManager telephonyManager;
    private final PowerManager powerManager;
    private final int myUid;

    // For Network Speed Calculation
    private volatile long lastTxBytes = 0;
    private volatile long lastRxBytes = 0;
    private volatile long lastTimestamp = 0;
    private static final byte VALUE_UNKNOWN = (byte) 0xFF;
    private static final double KB = 1024.0;

    // Accumulators for total session usage
    private volatile double sessionUploadBytes = 0;
    private volatile double sessionDownloadBytes = 0;

    // Listeners and Schedulers
    private volatile Location lastKnownLocation;
    private volatile SignalStrength lastSignalStrength;
    private MySignalStrengthListener modernListener;
    private PhoneStateListener legacyListener;
    private final LocationListener locationListener;

    private ClientInfoProvider(Context context) {
        this.context = context.getApplicationContext();

        // Initialize Managers
        this.batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        this.myUid = android.os.Process.myUid();

        this.locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                lastKnownLocation = location;
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                // Do not nullify lastKnownLocation immediately to preserve last known state
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {}

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
    }

    public static ClientInfoProvider getInstance(Context context) {
        if (instance == null) {
            synchronized (ClientInfoProvider.class) {
                if (instance == null) {
                    instance = new ClientInfoProvider(context);
                }
            }
        }
        return instance;
    }

    public void startMonitoring() {
        startLocationUpdates();
        startSignalStrengthListener();
        initializeNetworkStats();
    }

    public void stopMonitoring() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }

        if (telephonyManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modernListener != null) {
                telephonyManager.unregisterTelephonyCallback(modernListener);
            } else if (legacyListener != null) {
                telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
            }
        }
    }

    private void startLocationUpdates() {
        if (locationManager == null) return;

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission missing");
            return;
        }

        if (!gpsEnabled && !netEnabled) {
            Log.e(TAG, "Location service OFF");
            return;
        }

        String provider = gpsEnabled ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;

        Location cached = locationManager.getLastKnownLocation(provider);
        if (cached != null) {
            lastKnownLocation = cached;
        }

        locationManager.requestLocationUpdates(
                provider,
                0,
                0,
                locationListener
        );
    }

    // ---------------- Signal Strength Logic ----------------

    @RequiresApi(api = Build.VERSION_CODES.S)
    private class MySignalStrengthListener extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
        @Override
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
            lastSignalStrength = signalStrength;
        }
    }

    private void startSignalStrengthListener() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "READ_PHONE_STATE permission not granted");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernListener = new MySignalStrengthListener();
            telephonyManager.registerTelephonyCallback(context.getMainExecutor(), modernListener);
        } else {
            legacyListener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                    super.onSignalStrengthsChanged(signalStrength);
                    lastSignalStrength = signalStrength;
                }
            };
            telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS | PhoneStateListener.LISTEN_SERVICE_STATE);
        }
    }

    // ---------------- Network Stats Logic ----------------

    private void initializeNetworkStats() {
        lastTxBytes = TrafficStats.getUidTxBytes(myUid);
        lastRxBytes = TrafficStats.getUidRxBytes(myUid);
        lastTimestamp = System.currentTimeMillis();

         sessionUploadBytes = 0;
         sessionDownloadBytes = 0;
    }

    /**
     * Constructs the Byte Array containing client telemetry.
     * <br>
     * <b>Protocol Structure (56 bytes):</b><br>
     * Power (8B) | Telecom (4B) | Network (12B) | Location (32B)
     */
    /**
     * Constructs the Byte Array containing client telemetry.
     * Protocol Structure (56 bytes):
     * Power (8B) | Telecom (4B) | Network (12B) | Location (32B)
     */
    private int lastCharge_uAh = Integer.MIN_VALUE;

    public String getClientStats() {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(58);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // ---------------- 1. Power Stats (8 Bytes) ----------------
            short outValue = 0;

            try {
                BatteryManager bm = batteryManager;

                /* ---------- Charging state (authoritative) ---------- */
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = context.registerReceiver(null, ifilter);

                boolean isCharging = false;
                if (batteryStatus != null) {
                    int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL);
                }

                int milliAmps = 0;
                boolean usedFallback = true;

                /* ---------- Try CHARGE_COUNTER ---------- */
                int charge_uAh =
                        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);

                if (charge_uAh > 0 && lastCharge_uAh > 0 && charge_uAh != lastCharge_uAh) {
                    int delta_uAh = lastCharge_uAh - charge_uAh;
                    milliAmps = Math.abs(delta_uAh / 1000);
                    usedFallback = false;
                }

                lastCharge_uAh = charge_uAh;

                /* ---------- Fallback: CURRENT_NOW ---------- */
                if (usedFallback) {
                    int rawCurrent =
                            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);

                    if (rawCurrent != Integer.MIN_VALUE &&
                            rawCurrent != Integer.MAX_VALUE) {

                        int abs = Math.abs(rawCurrent);
                        milliAmps = (abs > 50000) ? abs / 1000 : abs;
                    }
                }

                /* ---------- Normalize sign ---------- */
                milliAmps = Math.abs(milliAmps);
                if (!isCharging) milliAmps = -milliAmps;

                /* ---------- Clamp ---------- */
                if (milliAmps > Short.MAX_VALUE) milliAmps = Short.MAX_VALUE;
                if (milliAmps < Short.MIN_VALUE) milliAmps = Short.MIN_VALUE;

                outValue = (short) milliAmps;

            } catch (Exception ignored) {
                outValue = 0;
            }

            buffer.putShort(outValue);




            try {
                int capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                buffer.put((byte) (capacity > 0 ? capacity : 0));
            } catch (Exception e) { buffer.put((byte) 0); } // Fallback: 0

            // Battery Temp
            float batTemp = getBatteryTemperatureLegacy();
            // check for NaN (not a number)
            if (Float.isNaN(batTemp)) {
                buffer.putFloat(0.0f);
            } else {
                buffer.putFloat(batTemp);
            }

            // Thermal Status
            buffer.put(getThermalStatus()); // method already handles fallback to 0xFF


            // ---------------- 2. Telecom Stats (4 Bytes) ----------------

            // Signal Strength
            try {
                short dbm = (short) getCellularSignalStrengthDbm();
                buffer.putShort(dbm);
            } catch (Exception e) { buffer.putShort((short) -140); }

            // wifi Strength
            try {
                if (isWifiConnected()) {
                    short dbm = (short) getWifiSignalDbm();
                    buffer.putShort(dbm);
                } else {
                    buffer.putShort((short) -140);
                }
            } catch (Exception e) { buffer.putShort((short) -140); }

            // Network Types
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    buffer.put((byte) telephonyManager.getNetworkType());
                } catch (Exception e) { buffer.put(VALUE_UNKNOWN); }

                try {
                    buffer.put((byte) (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? telephonyManager.getDataNetworkType()
                            : VALUE_UNKNOWN));
                } catch (Exception e) { buffer.put(VALUE_UNKNOWN); }
            } else {
                buffer.put(VALUE_UNKNOWN);
                buffer.put(VALUE_UNKNOWN);
            }


            // ---------------- 3. Throughput & Consumption (12 Bytes) ----------------
            double uploadBps = 0.0;
            double downloadBps = 0.0;

            try {
                long currentTx = TrafficStats.getUidTxBytes(myUid);
                long currentRx = TrafficStats.getUidRxBytes(myUid);
                long currentTs = System.currentTimeMillis();
                long deltaTs = currentTs - lastTimestamp;

                if (currentTx != TrafficStats.UNSUPPORTED && currentRx != TrafficStats.UNSUPPORTED && deltaTs > 0) {
                    long txDiff = currentTx - lastTxBytes;
                    long rxDiff = currentRx - lastRxBytes;

                    // Sanity checks
                    if (txDiff < 0) txDiff = 0;
                    if (rxDiff < 0) rxDiff = 0;

                    uploadBps = (txDiff * 1000.0) / deltaTs;
                    downloadBps = (rxDiff * 1000.0) / deltaTs;

                    sessionUploadBytes += txDiff;
                    sessionDownloadBytes += rxDiff;

                    // Update trackers
                    lastTxBytes = currentTx;
                    lastRxBytes = currentRx;
                    lastTimestamp = currentTs;
                }
            } catch (Exception e) {
                // If TrafficStats fails, variables remain 0.0
            }

            buffer.putFloat((float) (uploadBps / KB));
            buffer.putFloat((float) (downloadBps / KB));
            buffer.putFloat((float) getAppDataConsumptionMb());


            // ---------------- 4. Location Stats (32 Bytes) ----------------
            // If location is null, fill everything with 0
            if (lastKnownLocation != null) {
                buffer.putDouble(lastKnownLocation.getLatitude());  // Lat
                buffer.putDouble(lastKnownLocation.getLongitude()); // Lon

                // Extra safety checks for accuracy/speed/altitude
                buffer.putFloat(lastKnownLocation.hasAccuracy() ? lastKnownLocation.getAccuracy() : 0f);
                buffer.putFloat(lastKnownLocation.hasSpeed() ? lastKnownLocation.getSpeed() : 0f);
                buffer.putDouble(lastKnownLocation.hasAltitude() ? lastKnownLocation.getAltitude() : 0.0);
            } else {
                buffer.putDouble(0.0); // Lat
                buffer.putDouble(0.0); // Lon
                buffer.putFloat(0f);   // Accuracy
                buffer.putFloat(0f);   // Speed
                buffer.putDouble(0.0); // Altitude
            }

            return bytesToHex(buffer.array());

        } catch (Exception e) {
            Log.e(TAG, "Failed to construct info packet", e);
            // In worst case failure, return empty string or a string of 00s
            return "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private int getCellularSignalStrengthDbm() {
        final int UNKNOWN_DBM = -127;

        if (lastSignalStrength == null) {
            return UNKNOWN_DBM;
        }

        // API 29+ : use all reported cells, pick strongest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int bestDbm = Integer.MIN_VALUE;
            try {
                for (android.telephony.CellSignalStrength css : lastSignalStrength.getCellSignalStrengths()) {
                    int dbm = css.getDbm();
                    if (dbm != Integer.MAX_VALUE && dbm > bestDbm) {
                        bestDbm = dbm;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing signal strengths: " + e.getMessage());
            }
            return (bestDbm != Integer.MIN_VALUE) ? bestDbm : UNKNOWN_DBM;
        }

        // Pre-Q fallback
        int gsmAsu = lastSignalStrength.getGsmSignalStrength();
        // 99 is the GSM "unknown" value
        if (gsmAsu >= 0 && gsmAsu != 99) {
            return -113 + (2 * gsmAsu);
        }

        return UNKNOWN_DBM;
    }

    private double getAppDataConsumptionMb() {
        // Convert Bytes to Megabytes
        return (sessionUploadBytes + sessionDownloadBytes) / (1024.0 * 1024.0);
    }

    private byte getThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return (byte) 0xFF; // Not supported on older APIs
        }
        try {
            switch (powerManager.getCurrentThermalStatus()) {
                case PowerManager.THERMAL_STATUS_NONE:       return 0;
                case PowerManager.THERMAL_STATUS_LIGHT:      return 1;
                case PowerManager.THERMAL_STATUS_MODERATE:   return 2;
                case PowerManager.THERMAL_STATUS_SEVERE:     return 3;
                case PowerManager.THERMAL_STATUS_CRITICAL:   return 4;
                case PowerManager.THERMAL_STATUS_EMERGENCY:  return 5;
                case PowerManager.THERMAL_STATUS_SHUTDOWN:   return 6;
                default:                                     return (byte) 0xFF;
            }
        } catch (Exception e) {
            return (byte) 0xFF;
        }
    }

    private volatile float lastBatteryTempC = 0.0f;
    private float getBatteryTemperatureLegacy() {
        try {
            Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return lastBatteryTempC;

            int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE) {
                lastBatteryTempC = temp / 10.0f;
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery temp read failed", e);
        }
        return lastBatteryTempC;
    }

    private int getWifiSignalDbm() {
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);

            if (wifiManager == null) return -127;

            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return -127;

            return info.getRssi();
        } catch (Exception e) {
            return -127;
        }
    }
    private boolean isWifiConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

}