package com.github.nikipo.ussoi.hardware.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import com.github.nikipo.ussoi.system.UsbAttachReceiver;

public final class UsbBroadcastHandler {

    private final Context context;
    private final UsbController controller;

    public UsbBroadcastHandler(Context context, UsbController controller) {
        this.context = context;
        this.controller = controller;
    }

    // USB device attach/detach events
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            UsbDevice device =
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String action = intent.getAction();

            if (device == null || action == null) return;

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                controller.updateDeviceInfo(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                controller.checkExistingDevices();
            }
        }
    };

    public void register() {
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbAttachReceiver.ACTION_USB_EVENT);

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                    usbReceiver,
                    f,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            context.registerReceiver(usbReceiver, f);
        }
    }

    public void unregister() {
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }
}
