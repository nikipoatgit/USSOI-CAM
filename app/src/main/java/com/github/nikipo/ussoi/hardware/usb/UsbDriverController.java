package com.github.nikipo.ussoi.hardware.usb;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.widget.Toast;

import com.github.nikipo.ussoi.tunnel.usb.UsbHandler;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.util.List;

public final class UsbDriverController {

    static final String ACTION_USB_PERMISSION ="com.github.nikipo.ussoi.USB_PERMISSION";

    private final Activity activity;
    private final UsbManager usbManager;
    private final UsbHandler usbHandler;

    public UsbDriverController(Activity activity) {
        this.activity = activity;
        this.usbManager =(UsbManager) activity.getSystemService(Context.USB_SERVICE);
        this.usbHandler = UsbHandler.getInstance(activity);

        // make sure previous connected device is cleared usbHandler is singleton
        usbHandler.clearDriver();
    }
    public interface OnComplete {
        void run();
    }
    public void selectAndStart(OnComplete onComplete) {
        List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        if (drivers.isEmpty()) {
            Toast.makeText(activity, "No USB serial devices found", Toast.LENGTH_SHORT).show();
            return;
        }

        UsbSetupDialog.show(
                ((androidx.fragment.app.FragmentActivity) activity).getSupportFragmentManager(),
                drivers,
                (driver, name, baudRate) -> {
                    UsbDevice device = driver.getDevice();

                    if (!usbManager.hasPermission(device)) {
                        requestPermission(device);
                        return;
                    }

                    usbHandler.setDeviceConfig(driver, name, baudRate);
                    onComplete.run();
                }
        );
    }

    void requestPermission(UsbDevice device) {
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
