package com.github.nikipo.ussoi;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_RoomID;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_RoomPWD;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_api_path;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_url;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_local_recording;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_mse_Enable;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_webrtc_Enable;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.USSOI_version;

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

import com.github.nikipo.ussoi.MacroServices.DeviceInfo;
import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.github.nikipo.ussoi.ServicesManager.AuthLogin;
import com.github.nikipo.ussoi.ServicesManager.ConnManager;
import com.github.nikipo.ussoi.ServicesManager.ServiceNotificationHelper;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@UnstableApi
public class ServiceManager extends Service implements LifecycleOwner{
    private final ServiceLifecycleDispatcher mDispatcher = new ServiceLifecycleDispatcher(this);
    private final String TAG = "ServiceManager";
    private ScheduledExecutorService scheduler;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs; // code related to handel preferences
    private SaveInputFields saveInputFields;
    private Logging logger;
    public volatile static boolean isRunning = false;
    private ConnManager connectionManager;
    private DeviceInfo deviceInfo;
    private volatile boolean loginStatusFlag = false;

    @Override
    public void onCreate() {
        mDispatcher.onServicePreSuperOnCreate();
        super.onCreate();
        // start Up code
        isRunning = true;
        saveInputFields = SaveInputFields.getInstance(this);
        logger = Logging.getInstance(this);
        deviceInfo = DeviceInfo.getInstance(this);

        // --- Acquire partial WakeLock (NEW) ---
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

        logger.log(TAG + ": Application started" + " Version" + USSOI_version);

        //Start foreground
        Notification notification = ServiceNotificationHelper.createNotification(this);
        startForeground(ServiceNotificationHelper.NOTIFICATION_ID, notification);

        // Reset status flags in preferences
        prefs = saveInputFields.get_shared_pref();
        prefs.edit()
                .putBoolean(KEY_mse_Enable, false)
                .putBoolean(KEY_webrtc_Enable, false)
                .putBoolean(KEY_local_recording, false)
                .apply();

        // Login
        AuthLogin authLogin = new AuthLogin();
        String roomId = prefs.getString(KEY_RoomID, "blockMe");
        String roomPwd = prefs.getString(KEY_RoomPWD, "blockMe");
        String apiUrl = prefs.getString(KEY_url, "http://10.0.0.1");

        authLogin.login(roomId, roomPwd, apiUrl, new AuthLogin.LoginCallback() {
            @Override
            public void onSuccess(String sessionKey) {
                logger.log(TAG + ": Login Successful");
                prefs.edit().putString(KEY_Session_KEY, sessionKey).apply();
                loginStatusFlag = true;
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
                loginStatusFlag = false;
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
        if (connectionManager == null) {
            connectionManager = new ConnManager(
                    this,
                    KEY_api_path
            );
            connectionManager.connect();

            // Start your scheduler here if it depends on the connection
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
            }
            logger.log(TAG + ": ConnManager connected and Scheduler initialized");
        }
    }

    @Override
    public void onDestroy() {
        mDispatcher.onServicePreSuperOnDestroy();
        super.onDestroy();
        isRunning = false;
        logger.log(TAG + ": Service is being destroyed");

        if(connectionManager != null){
            connectionManager.stopAllServices();
            connectionManager = null;}

        if (scheduler != null) scheduler.shutdownNow();

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        if (logger != null){
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

//// In your Activity or Application class
//     private Logging logger;
//       logger = Logging.getInstance(this);
//
//// Log a simple message
//logger.log("Application started successfully");
//
//// Log an error (you can overload the log method to accept exceptions if you want)
//try {
//        // some code
//        } catch (Exception e) {
//        logger.log("Error occurred: " + e.getMessage());
//        }