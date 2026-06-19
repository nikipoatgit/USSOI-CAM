package com.github.nikipo.ussoi.system.telemetry;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file LocationProvider
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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

/**
 * Provides GPS/network location telemetry.
 * Compatible with Android 7.0+ (API 24+).
 *
 * Call startUpdates() once (e.g. from SysTelemetry.startMonitoring()).
 * Call stopUpdates() when monitoring is no longer needed.
 * Call getLastLocation() to read the most recent fix.
 *
 * Requires: ACCESS_FINE_LOCATION permission.
 */
public class LocationProvider {

    private static final String TAG = "LocationProvider";

    private final Context context;
    private final LocationManager locationManager;

    // Guarded by 'this' — written by the location callback (any thread),
    // read by the packet builder thread.
    private volatile Location lastKnownLocation;

    private boolean isUpdating = false;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            lastKnownLocation = location;
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            // Preserve the last known fix — do not nullify.
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {}

        // Required for API < 29
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    public LocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Starts requesting location updates.
     * Safe to call multiple times — registers only once.
     * Must be called from the main thread (LocationManager requirement).
     */
    public synchronized void startUpdates() {
        if (isUpdating) return;
        if (locationManager == null) return;

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ACCESS_FINE_LOCATION not granted — location unavailable");
            return;
        }

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!gpsEnabled && !netEnabled) {
            Log.w(TAG, "All location providers disabled");
            return;
        }

        // Prefer GPS; fall back to network
        String provider = gpsEnabled
                ? LocationManager.GPS_PROVIDER
                : LocationManager.NETWORK_PROVIDER;

        // Seed with last known value so the first getLastLocation() call isn't null
        try {
            Location cached = locationManager.getLastKnownLocation(provider);
            if (cached != null) {
                lastKnownLocation = cached;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get last known location", e);
        }

        try {
            locationManager.requestLocationUpdates(
                    provider,
                    0L,   // minTimeMs  — update as fast as possible
                    0f,   // minDistanceM — update on any movement
                    locationListener
            );
            isUpdating = true;
            Log.d(TAG, "Location updates started via " + provider);
        } catch (Exception e) {
            Log.e(TAG, "requestLocationUpdates failed", e);
        }
    }

    /**
     * Stops location updates and releases the listener.
     */
    public synchronized void stopUpdates() {
        if (!isUpdating || locationManager == null) return;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates failed", e);
        } finally {
            isUpdating = false;
        }
    }

    /**
     * Returns the most recent Location, or null if no fix has been received yet.
     */
    public Location getLastLocation() {
        return lastKnownLocation;
    }
}
