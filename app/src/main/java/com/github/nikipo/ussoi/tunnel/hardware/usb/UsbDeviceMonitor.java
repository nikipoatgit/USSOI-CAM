package com.github.nikipo.ussoi.tunnel.hardware.usb;

import static com.github.nikipo.ussoi.tunnel.hardware.usb.UsbDriverController.ACTION_USB_PERMISSION;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file UsbDeviceMonitor
 * @attention Copyright (c) 2026
 * All rights reserved.
 * <p>
 * This software is licensed under the terms described in the LICENSE file
 * located in the root directory of this project.
 * If no LICENSE file is present, this software is provided "AS IS",
 * without warranty of any kind, express or implied.
 * <p>
 * *****************************************************************************
 */
public final class UsbDeviceMonitor {
    public interface Listener {
        void onDeviceConnected(UsbDevice device);
        void onDeviceDisconnected();
    }

    private final Context context;
    private final Activity activity;
    private final UsbManager usbManager;
    private UsbDriverController usbDriverController;
    private final Listener listener;
    private boolean registered = false;

    public UsbDeviceMonitor(Activity activity,UsbDriverController usbDriverController, Listener listener) {
        this.usbDriverController = usbDriverController;
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.listener = listener;
    }

    /** Registers broadcast receiver and fires listener for any already-connected device. */
    public void init() {
        registerReceiver();
        checkExistingDevices();
    }

    /** Unregisters the broadcast receiver. Call in onStop. */
    public void release() {
        if (!registered) return;
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        registered = false;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void checkExistingDevices() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            listener.onDeviceDisconnected();
        } else {
            UsbDevice device = devices.values().iterator().next();
            if (usbManager.hasPermission(device)) {
                listener.onDeviceConnected(device);
            }
        }
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        registered = true;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {

            String action = intent.getAction();
            if (action == null) return;

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) return;

            switch (action) {

                case UsbManager.ACTION_USB_DEVICE_ATTACHED:

                    if (!usbManager.hasPermission(device)) {
                        usbDriverController.requestPermission(device);
                    } else {
                        listener.onDeviceConnected(device);
                    }
                    break;

                case ACTION_USB_PERMISSION:
                        listener.onDeviceConnected(device);
                    break;

                case UsbManager.ACTION_USB_DEVICE_DETACHED:

                    listener.onDeviceDisconnected();
                    checkExistingDevices();
                    break;
            }
        }
    };


}
