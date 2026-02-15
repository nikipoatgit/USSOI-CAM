package com.github.nikipo.ussoi.system.power;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

public final class PowerController {

    private final Activity activity;

    public PowerController(Activity activity) {
        this.activity = activity;
    }

    public boolean isIgnoringBatteryOptimizations() {
        PowerManager pm =
                (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(activity.getPackageName());
    }

    public void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent =
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        }
    }
}
