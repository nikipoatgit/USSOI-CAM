package com.github.nikipo.ussoi.hardware.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.widget.TextView;

import java.util.HashMap;

public final class UsbController {

    private final UsbManager usbManager;
    private final TextView usbInfoText;

    public UsbController(Context context, TextView usbInfoText) {
        this.usbManager =
                (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.usbInfoText = usbInfoText;
    }

    public void checkExistingDevices() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            usbInfoText.setText("No USB device connected");
        }
    }

    public void updateDeviceInfo(UsbDevice device) {
        String m = " ", p = " ", s = " ";

        if (usbManager.hasPermission(device)) {
            s = device.getSerialNumber();
            m = device.getManufacturerName();
            p = device.getProductName();
        }

        String info =
                "Path: " + device.getDeviceName() + "\n" +
                        "VID : " + Integer.toHexString(device.getVendorId()) +
                        " PID : " + Integer.toHexString(device.getProductId()) + "\n" +
                        "USB: C/S/P = " +
                        device.getDeviceClass() + "/" +
                        device.getDeviceSubclass() + "/" +
                        device.getDeviceProtocol() + "\n" +
                        "Mfg: " + m + "\n" +
                        "Prod: " + p + "\n" +
                        "SN: " + s;

        usbInfoText.setText(info);
    }

    public UsbManager getUsbManager() {
        return usbManager;
    }
}
