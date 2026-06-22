package com.github.nikipo.ussoi.tunnel.hardware.usb;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.util.List;
/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file UsbSetupDialog
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
public class UsbSetupDialog extends DialogFragment {

    public interface OnConfigured {
        void onConfigured(UsbSerialDriver driver, String name, int baudRate);
    }

    private static final int[] BAUD = {
            2400, 4800,9600, 19200, 38400, 57600,115200, 230400, 460800, 921600,1000000, 1500000
    };

    private List<UsbSerialDriver> drivers;
    private OnConfigured callback;

    public static void show(androidx.fragment.app.FragmentManager fm,
                            List<UsbSerialDriver> drivers,
                            OnConfigured callback) {
        UsbSetupDialog d = new UsbSetupDialog();
        d.drivers = drivers;
        d.callback = callback;
        d.show(fm, "usb");
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 10);

        // ── Device labels ─────────────────────────────
        String[] deviceLabels = new String[drivers.size()];

        for (int i = 0; i < drivers.size(); i++) {
            android.hardware.usb.UsbDevice dev = drivers.get(i).getDevice();

            String vid = Integer.toHexString(dev.getVendorId()).toUpperCase();
            String pid = Integer.toHexString(dev.getProductId()).toUpperCase();

            String mfg = dev.getManufacturerName();
            String prod = dev.getProductName();

            if (mfg == null) mfg = "Unknown";
            if (prod == null) prod = "Unknown";

            deviceLabels[i] = mfg + " - " + prod + " (" + vid + ":" + pid + ")";
        }

        // ── Device spinner ────────────────────────────
        Spinner deviceSpinner = new Spinner(requireContext());
        deviceSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                deviceLabels
        ));

        Spinner baudSpinner = new Spinner(requireContext());
        String[] baudLabels = new String[BAUD.length];
        for (int i = 0; i < BAUD.length; i++) {
            baudLabels[i] = String.valueOf(BAUD[i]);
        }

        baudSpinner.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                baudLabels
        ));

        // default = 115200
        int defaultIndex = 0;
        for (int i = 0; i < BAUD.length; i++) {
            if (BAUD[i] == 115200) {
                defaultIndex = i;
                break;
            }
        }
        baudSpinner.setSelection(defaultIndex);

        // ── Name input ────────────────────────────────
        EditText name = new EditText(requireContext());
        name.setHint("Device name");

        // ── Add views ────────────────────────────────
        layout.addView(deviceSpinner);
        layout.addView(baudSpinner);
        layout.addView(name);

        // ── Dialog ───────────────────────────────────
        return new AlertDialog.Builder(requireContext())
                .setTitle("USB Setup")
                .setView(layout)
                .setNegativeButton("Cancel", (d, w) -> dismiss())
                .setPositiveButton("Connect", (d, w) -> {

                    String n = name.getText().toString().trim();
                    if (TextUtils.isEmpty(n)) return;

                    UsbSerialDriver driver =
                            drivers.get(deviceSpinner.getSelectedItemPosition());

                    int baud =
                            BAUD[baudSpinner.getSelectedItemPosition()];

                    callback.onConfigured(driver, n, baud);
                })
                .create();
    }
}
