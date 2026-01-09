package com.github.nikipo.ussoi.MacroServices;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

public class UsbAttachReceiver extends BroadcastReceiver {

    public static final String ACTION_USB_EVENT =
            "com.github.nikipo.ussoi.USB_EVENT";

    @Override
    public void onReceive(Context context, Intent intent) {
        UsbDevice device =
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) return;

        Intent i = new Intent(ACTION_USB_EVENT);
        i.putExtra(UsbManager.EXTRA_DEVICE, device);
        i.putExtra("action", intent.getAction());
        context.sendBroadcast(i);
    }
}
