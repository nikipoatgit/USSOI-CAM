package com.github.nikipo.ussoi.tunnel.hardware.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;

import com.github.nikipo.ussoi.storage.SaveInputFields;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class BluetoothController {
    private static final String TAG = "BluetoothController";
    private final Activity activity;
    private final ActivityResultLauncher<String[]> permissionLauncher;

    public BluetoothController(
            Activity activity,
            ActivityResultLauncher<String[]> permissionLauncher
    ) {
        this.activity = activity;
        this.permissionLauncher = permissionLauncher;
    }

    public void selectDevicesAndStart(Runnable onDone) {

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(activity,
                    "Enable Bluetooth first",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (needsPermission()) {
            requestPermission();
            return;
        }

        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        if (paired.isEmpty()) {
            Toast.makeText(activity,
                    "No paired devices found",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        showDeviceDialog(new ArrayList<>(paired), onDone);
    }

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            });
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT already verified in selectDevicesAndStart()
    private void showDeviceDialog(List<BluetoothDevice> devices, Runnable onDone) {

        String[] names = new String[devices.size()];
        boolean[] checked = new boolean[devices.size()];

        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String displayName = d.getName() != null ? d.getName() : "Unknown device";
            names[i] = displayName + "\n" + d.getAddress();
        }

        SaveInputFields.selectedBtDevices.clear();

        new AlertDialog.Builder(activity)
                .setTitle("Select Bluetooth Devices")
                .setMultiChoiceItems(names, checked,
                        (d, which, isChecked) -> {

                            BluetoothDevice dev = devices.get(which);
                            if (isChecked) {
                                if (!SaveInputFields.selectedBtDevices.contains(dev))
                                    SaveInputFields.selectedBtDevices.add(dev);
                            } else {
                                SaveInputFields.selectedBtDevices.remove(dev);
                            }
                        })
                .setPositiveButton("OK", (d, w) -> {
                    updateBtDevicesJson();
                    onDone.run();
                })
                .setNegativeButton("Cancel", null)
                .show();

    }

    // Save selected Bt devices to json
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT already verified in selectDevicesAndStart()
    private void updateBtDevicesJson() {
        JSONArray arr = new JSONArray();

        for (int i = 0; i < SaveInputFields.selectedBtDevices.size(); i++) {
            BluetoothDevice d = SaveInputFields.selectedBtDevices.get(i);
            JSONObject o = new JSONObject();
            try {
                o.put("index", i + 1);
                // JSONObject.put(key, null) silently removes the key instead of storing it,
                // so a nameless device would otherwise drop "name" from the JSON entirely.
                o.put("name", d.getName() != null ? d.getName() : "Unknown device");
                o.put("mac", d.getAddress());
            } catch (Exception ignored) {
            }
            arr.put(o);
        }

        synchronized (SaveInputFields.btDevices) {
            Iterator<String> keys = SaveInputFields.btDevices.keys();
            while (keys.hasNext()) {
                keys.next();
                keys.remove();
            }
            try {
                Log.d(TAG, arr.toString());
                SaveInputFields.btDevices.put("devices", arr);
            } catch (Exception ignored) {
            }
        }
    }
}