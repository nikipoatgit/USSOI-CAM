package com.github.nikipo.ussoi.hardware.usb;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.github.nikipo.ussoi.tunnel.UsbHandler;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.List;

public final class UsbDriverController {

    private static final String ACTION_USB_PERMISSION =
            "com.github.nikipo.ussoi.USB_PERMISSION";

    private final Activity activity;
    private final UsbManager usbManager;
    private final UsbHandler usbHandler;

    public UsbDriverController(Activity activity) {
        this.activity = activity;
        this.usbManager =
                (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        this.usbHandler = UsbHandler.getInstance(activity);
    }

    public void selectAndStart(Runnable onReady) {

        List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        if (drivers.isEmpty()) {
            Toast.makeText(activity,
                    "No USB serial drivers available",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> labels = new ArrayList<>();
        for (UsbSerialDriver d : drivers) {
            UsbDevice dev = d.getDevice();
            labels.add(
                    "VID: " + Integer.toHexString(dev.getVendorId()) +
                            " PID: " + Integer.toHexString(dev.getProductId()) +
                            " Ports: " + d.getPorts().size()
            );
        }

        new AlertDialog.Builder(activity)
                .setTitle("Select USB Device")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    UsbSerialDriver driver = drivers.get(which);
                    UsbDevice device = driver.getDevice();
                    usbHandler.setDriver(driver);

                    if (!usbManager.hasPermission(device)) {
                        requestPermission(device);
                    } else {
                        onReady.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestPermission(UsbDevice device) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            flags |= PendingIntent.FLAG_MUTABLE;

        PendingIntent pi = PendingIntent.getBroadcast(
                activity,
                0,
                new Intent(ACTION_USB_PERMISSION)
                        .setPackage(activity.getPackageName()),
                flags
        );

        usbManager.requestPermission(device, pi);
    }
}
