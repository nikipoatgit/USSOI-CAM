package com.github.nikipo.ussoi.ServicesManager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.github.nikipo.ussoi.R;

public class ServiceNotificationHelper {

    public static final String NOTIFICATION_CHANNEL_ID = "StreamingServiceChannel";
    public static final int NOTIFICATION_ID = 1;

    public static Notification createNotification(Context ctx) {
        createNotificationChannel(ctx);

        return new NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("UAV Stream Active")
                .setContentText("Broadcasting camera and telemetry.")
                .setSmallIcon(R.drawable.ic_ussoi_notification_icon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();
    }

    private static void createNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Streaming Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
