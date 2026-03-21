package com.github.nikipo.ussoi.service;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Device_Id;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_RoomID;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_RoomPWD;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_control_api_path;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_device_name;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_url;
import static com.github.nikipo.ussoi.storage.SaveInputFields.USSOI_version;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ServiceLifecycleDispatcher;
import androidx.media3.common.util.UnstableApi;

import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.system.notification.ServiceNotificationHelper;

import org.jetbrains.annotations.Nullable;

@UnstableApi
public class ServiceManager extends Service implements LifecycleOwner {
    private final ServiceLifecycleDispatcher mDispatcher = new ServiceLifecycleDispatcher(this);
    private final String TAG = "ServiceManager";
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs; // code related to handel preferences
    private SaveInputFields saveInputFields;
    private Logging logger;
    public volatile static boolean isRunning = false;
    private ConnectionManager connectionManager;

    @Override
    public void onCreate() {
        mDispatcher.onServicePreSuperOnCreate();
        super.onCreate();

        // start Up code
        isRunning = true;
        saveInputFields = SaveInputFields.getInstance(this);
        logger = Logging.getInstance(this);

        // --- Acquire partial WakeLock  ---
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            if (wakeLock == null || !wakeLock.isHeld()) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ussoi:streaming"
                );
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(); // no timeout
            }
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mDispatcher.onServicePreSuperOnStart();

        logger.log(TAG + ": Application started" + " Version" + " " + USSOI_version);

        //Start foreground
        Notification notification = ServiceNotificationHelper.createNotification(this);
        startForeground(ServiceNotificationHelper.NOTIFICATION_ID, notification);

        // Reset status flags in preferences
        prefs = saveInputFields.get_shared_pref();

        // Login
        AuthLogin authLogin = new AuthLogin();
        String roomId = prefs.getString(KEY_RoomID, "blockMe");
        String roomPwd = prefs.getString(KEY_RoomPWD, "blockMe");
        String apiUrl = prefs.getString(KEY_url, "http://10.0.0.1");
        String deviceName = prefs.getString(KEY_device_name,"Lelouch");

        authLogin.login(logger, deviceName, roomId, roomPwd, apiUrl, new AuthLogin.LoginCallback() {
            @Override
            public void onSuccess(String sessionKey,String deviceId) {
                logger.log(TAG + ": Login Successful");
                prefs.edit().putString(KEY_Session_KEY, sessionKey).apply();
                prefs.edit().putString(KEY_Device_Id, deviceId).apply();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(
                                ServiceManager.this,
                                "Authentication Successful",
                                android.widget.Toast.LENGTH_LONG
                        ).show()
                );
                initiateConnection();
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "Login Failed: " + error);
                logger.log(TAG + ": Login Failed " + error);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(
                                ServiceManager.this,
                                "Authentication failed",
                                android.widget.Toast.LENGTH_LONG
                        ).show()
                );
            }
        });

        return START_STICKY;
    }

    private void initiateConnection() {
        connectionManager = ConnectionManager.getInstance(this, KEY_control_api_path);
        connectionManager.connect();
        logger.log(TAG + ": ConnManager connected and Scheduler initialized");

    }

    @Override
    public void onDestroy() {
        mDispatcher.onServicePreSuperOnDestroy();
        super.onDestroy();
        isRunning = false;
        logger.log(TAG + ": Service is being destroyed");

        if (connectionManager != null) {
            connectionManager.stopAllServices();
            connectionManager = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        if (logger != null) {
            logger.closeLogging();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        mDispatcher.onServicePreSuperOnBind();
        return null;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mDispatcher.getLifecycle();
    }
}

