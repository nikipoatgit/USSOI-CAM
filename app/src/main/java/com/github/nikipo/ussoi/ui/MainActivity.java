package com.github.nikipo.ussoi.ui;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_BT_SWITCH;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Device_Id;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_device_name;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_url;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_USB_Switch;
import static com.github.nikipo.ussoi.storage.SaveInputFields.MASK;
import static com.github.nikipo.ussoi.storage.SaveInputFields.USSOI_version;
import static com.github.nikipo.ussoi.storage.StorageUtils.normaliseUrl;
import static com.github.nikipo.ussoi.ui.UssoiStrings.PasswordMask;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

import com.github.nikipo.ussoi.hardware.bluetooth.BluetoothController;
import com.github.nikipo.ussoi.hardware.usb.UsbDeviceMonitor;
import com.github.nikipo.ussoi.hardware.usb.UsbDriverController;
import com.github.nikipo.ussoi.network.update.VersionChecker;
import com.github.nikipo.ussoi.storage.StorageController;
import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.R;
import com.github.nikipo.ussoi.system.permissions.PermissionControl;
import com.github.nikipo.ussoi.system.power.PowerController;
import com.github.nikipo.ussoi.ui.controller.ServiceController;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private ServiceController serviceController;
    private StorageController storageController;
    private UsbDeviceMonitor usbDeviceMonitor;
    private BluetoothController bluetoothController;
    private UsbDriverController usbDriverController;
    private PowerController powerController;
    private SaveInputFields saveInputFields;
    private Logging logging;
    private SharedPreferences pref;

    // UI Components
    private TextView usbInfoText, versionTextField;
    private EditText urlIp, roomId, roomPwd,deviceName;
    private Button serviceButton;
    private MaterialButton radioUsb, radioBt;
    private MaterialButtonToggleGroup connectionToggleGroup;

    // State
    private ActivityResultLauncher<String[]> permissionLauncher;
    private boolean keepScreenOn = false;
    private MaterialButton btnKeepScreenOn;

    private boolean initFlag = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        logging = Logging.getInstance(this);
        saveInputFields = SaveInputFields.getInstance(this);
        pref = saveInputFields.get_shared_pref();


        powerController = new PowerController(this);

        // ActivityResultLauncher lets user pick directory
        ActivityResultLauncher<Intent> pickLogFolderLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                Toast.makeText(this, "Storage folder is required for logging!", Toast.LENGTH_LONG).show();
                return;
            }
            ;

            Uri treeUri = result.getData().getData();
            if (treeUri == null) return;

            storageController.onFolderPicked(treeUri);

            if (!powerController.isIgnoringBatteryOptimizations()) {
                showBatteryOptimizationNote();
            }
        });

        // Storage Folder Permission Check ,Dialog for folder selection
        storageController = new StorageController(this, saveInputFields, pickLogFolderLauncher);

         logging.log( TAG + ": "   + "Main Activity Created");

        initUi();
        // Create Permission Launcher
        setupPermissionLauncher();
        // launch  Dialog for permission
        String[] permissions = PermissionControl.required(this);
        for (String p : permissions) {
            logging.log(TAG + "Permissions :" + p);
        }
        permissionLauncher.launch(permissions);

        // usb event
        usbDriverController = new UsbDriverController(this);
        usbDeviceMonitor = new UsbDeviceMonitor(this,usbDriverController,new UsbDeviceMonitor.Listener() {
            @Override
            public void onDeviceConnected(UsbDevice device) {
                runOnUiThread(() -> usbInfoText.setText(buildDeviceInfo(device)));
            }
            @Override
            public void onDeviceDisconnected() {
                runOnUiThread(() -> usbInfoText.setText("No USB device connected"));
            }
        });
        usbDeviceMonitor.init();

        // bt controller
        bluetoothController = new BluetoothController(this, permissionLauncher);

        // foreground service
        serviceController = new ServiceController(this);

        // update check
        VersionChecker.check(USSOI_version, text -> runOnUiThread(() -> versionTextField.setText(text)));

    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onResume() {
        super.onResume();
        StorageController.InitState state = storageController.init();

        switch (state) {

            case OK:
                break;

            case NEED_FOLDER:
            case PERMISSION_REVOKED:
                storageController.requestFolder();
                break;

            case ERROR:
                Toast.makeText(this, "Storage error", Toast.LENGTH_LONG).show();
                finishAffinity();
                break;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        usbDeviceMonitor.release();
    }

    private void initUi() {
        setViews();
        setupListeners();
        restoreUiState();
    }

    private void setViews() {
        usbInfoText = findViewById(R.id.usbInfoText);
        deviceName = findViewById(R.id.deviceName);
        urlIp = findViewById(R.id.url_ip);
        roomId = findViewById(R.id.roomId);
        roomPwd = findViewById(R.id.roomPwd);
        serviceButton = findViewById(R.id.serviceButton);
        radioUsb = findViewById(R.id.btnUsb);
        radioBt = findViewById(R.id.btnBt);
        connectionToggleGroup = findViewById(R.id.connectionToggle);
        btnKeepScreenOn = findViewById(R.id.btnKeepScreenOn);
        versionTextField = findViewById(R.id.versionText);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setupListeners() {
        // Toggle Group Logic
        connectionToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {

            if (checkedId == R.id.btnUsb) {
                radioUsb.setTextColor(isChecked ? Color.WHITE : Color.parseColor("#1A1C1E"));
            }

            if (checkedId == R.id.btnBt) {
                radioBt.setTextColor(isChecked ? Color.WHITE : Color.parseColor("#1A1C1E"));
            }
        });

        // Service Start/Stop Button
        serviceButton.setOnClickListener(v -> handleServiceToggle());

        btnKeepScreenOn.setOnClickListener(v -> {
            keepScreenOn = !keepScreenOn;
            if (keepScreenOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                btnKeepScreenOn.setText(R.string.screen_always_on);
                btnKeepScreenOn.setTextColor(Color.WHITE);
                btnKeepScreenOn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#a182bf")));
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                btnKeepScreenOn.setText(R.string.timeout_removed);
                btnKeepScreenOn.setTextColor(Color.BLACK);
                btnKeepScreenOn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.white)));
            }
        });
    }

    private void restoreUiState() {
        urlIp.setText(pref.getString(KEY_url, ""));
        deviceName.setText(pref.getString(KEY_device_name,"Lelouch"));
        boolean btSelected = pref.getBoolean(KEY_BT_SWITCH, false);
        boolean usbSelected = pref.getBoolean(KEY_USB_Switch, false);

        if (btSelected) connectionToggleGroup.check(R.id.btnBt);
        else if (usbSelected) connectionToggleGroup.check(R.id.btnUsb);

        roomId.setText(PasswordMask);
        roomPwd.setText(PasswordMask);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void handleServiceToggle() {
        if (serviceController.isRunning()) {
            serviceController.stop(serviceButton);
        } else {
            // Save inputs before starting
            String url = normaliseUrl(urlIp.getText().toString().trim());
            pref.edit().putString(KEY_url, url).putString(KEY_device_name,deviceName.getText().toString().trim()).putBoolean(KEY_BT_SWITCH, radioBt.isChecked()).putBoolean(KEY_USB_Switch, radioUsb.isChecked()).apply();

            // Handle Password Update securely
            String currentId = roomId.getText().toString().trim();
            String currentPwd = roomPwd.getText().toString().trim();
            // Only save if user actually typed something new (not dots)
            if (!MASK.equals(currentId) && !MASK.equals(currentPwd)) {
                saveInputFields.saveCredentials(currentId, currentPwd);
                Log.d(TAG, "Credentials updated");
            }
            // Mask fields again
            roomId.setText(MASK);
            roomPwd.setText(MASK);

            // Branch based on connection type
            if (radioBt.isChecked()) {
                bluetoothController.selectDevicesAndStart(() -> serviceController.start(serviceButton));

            } else if (radioUsb.isChecked()) {
                usbDriverController.selectAndStart(() -> serviceController.start(serviceButton));
            } else {
                serviceController.start(serviceButton);
            }
        }


    }
    private void updateServiceButtonState(boolean isRunning) {
        serviceButton.setText(isRunning ? "Stop Service" : "Start Service");
    }
    // bulk permission request handler
    private void setupPermissionLauncher() {
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
            boolean allGranted = true;
            for (Boolean isGranted : permissions.values()) {
                if (!isGranted) allGranted = false;
            }
            if (allGranted) {
                Log.d(TAG, "All permissions granted.");
                 logging.log( TAG + ": "   + "All permissions granted");
            } else {
                 logging.log( TAG + ": "   + "All Were not permissions granted");
                Toast.makeText(this, "Permissions required for operation", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showBatteryOptimizationNote() {
        new AlertDialog.Builder(this).setTitle("Allow Background Activity").setMessage("To work reliably in the background, the app needs to be excluded from battery optimizations.\n\n" + "Without this, streaming and logging may stop.").setCancelable(false).setPositiveButton("Allow", (d, w) -> powerController.requestIgnoreBatteryOptimization()).setNegativeButton("Cancel", (d, w) -> {
            d.dismiss();
        }).show();
    }

    private String buildDeviceInfo(UsbDevice device) {
        android.hardware.usb.UsbManager mgr = (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);

        String m = " ", p = " ", s = " ";
        if (mgr != null && mgr.hasPermission(device)) {
            s = device.getSerialNumber();
            m = device.getManufacturerName();
            p = device.getProductName();
        }

        return "Path: " + device.getDeviceName() + "\n" +
                "VID : " + Integer.toHexString(device.getVendorId()) +
                " PID : " + Integer.toHexString(device.getProductId()) + "\n" +
                "USB: C/S/P = " + device.getDeviceClass() + "/" +
                device.getDeviceSubclass() + "/" + device.getDeviceProtocol() + "\n" +
                "Mfg: " + m + "\n" +
                "Prod: " + p + "\n" +
                "SN: " + s;
    }
}

