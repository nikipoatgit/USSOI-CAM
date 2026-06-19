package com.github.nikipo.ussoi.system.permissions;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionControl {

    public static String[] required(Context c) {
        List<String> needed = new ArrayList<>();

        add(c, needed, Manifest.permission.CAMERA);
        add(c, needed, Manifest.permission.RECORD_AUDIO);
        add(c, needed, Manifest.permission.ACCESS_FINE_LOCATION);
        add(c, needed, Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= 33)
            add(c, needed, Manifest.permission.POST_NOTIFICATIONS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(c, needed, Manifest.permission.BLUETOOTH_SCAN);
            add(c, needed, Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            add(c, needed, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        return needed.toArray(new String[0]);
    }

    private static void add(Context c, List<String> list, String perm) {
        if (ContextCompat.checkSelfPermission(c, perm)
                != PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }
}

