package com.github.nikipo.ussoi.ui.controller;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.github.nikipo.ussoi.service.ServiceManager;

public final class ServiceController {

    private static final String TAG = "ServiceController";
    private final Context appContext;

    public ServiceController(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @OptIn(markerClass = UnstableApi.class)
    public void start(Button serviceButton) {
        Log.d(TAG, "Starting Main Service");
        Intent intent = new Intent(appContext, ServiceManager.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
        serviceButton.setText("Stop Service");
    }

    @OptIn(markerClass = UnstableApi.class)
    public void stop(Button serviceButton) {
        Log.d(TAG, "Stopping Main Service");
        appContext.stopService(
                new Intent(appContext, ServiceManager.class)
        );
        serviceButton.setText("Start Service");
    }

    @OptIn(markerClass = UnstableApi.class)
    public boolean isRunning() {
        ActivityManager am =
                (ActivityManager) appContext.getSystemService(
                        Context.ACTIVITY_SERVICE
                );

        if (am == null) return false;

        for (ActivityManager.RunningServiceInfo s :
                am.getRunningServices(Integer.MAX_VALUE)) {

            if (ServiceManager.class.getName()
                    .equals(s.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
