package com.github.nikipo.ussoi.ui;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_BT_SWITCH;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_url;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_USB_Switch;
import static com.github.nikipo.ussoi.storage.SaveInputFields.MASK;
import static com.github.nikipo.ussoi.storage.SaveInputFields.USSOI_version;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import com.github.nikipo.ussoi.hardware.usb.UsbBroadcastHandler;
import com.github.nikipo.ussoi.hardware.usb.UsbController;
import com.github.nikipo.ussoi.hardware.usb.UsbDriverController;
import com.github.nikipo.ussoi.network.update.VersionChecker;
import com.github.nikipo.ussoi.storage.logs.LogStorageController;
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
    private LogStorageController logStorageController;
    private UsbBroadcastHandler usbBroadcastHandler;
    private UsbDriverController usbDriverController;
    private BluetoothController bluetoothController;
    private PowerController powerController;

    private SaveInputFields saveInputFields;
    private Logging logging;
    private SharedPreferences pref;

    // UI Components
    private TextView usbInfoText, versionTextField;
    private EditText urlIp, roomId, roomPwd;
    private Button serviceButton;
    private MaterialButton radioUsb, radioBt;
    private MaterialButtonToggleGroup connectionToggleGroup;

    // State
    private ActivityResultLauncher<String[]> permissionLauncher;
    private boolean keepScreenOn = false;
    private MaterialButton btnKeepScreenOn;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        saveInputFields = SaveInputFields.getInstance(this);
        pref = saveInputFields.get_shared_pref();
        logging = Logging.getInstance(this);

        ActivityResultLauncher<Intent> pickLogFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK ||
                            result.getData() == null) return;

                    Uri treeUri = result.getData().getData();
                    if (treeUri == null) return;

                    logStorageController.onFolderPicked(treeUri);

                    if (!powerController.isIgnoringBatteryOptimizations()) {
                        showBatteryOptimizationNote();
                    }
                }
        );

        logging.log("Application Started / Main Activity Created");


        initUi();
        setupPermissionLauncher();
        powerController = new PowerController(this);
        usbDriverController = new UsbDriverController(this);
        permissionLauncher.launch(
                PermissionControl.required(this)
        );
        UsbController usbController = new UsbController(this, usbInfoText);

        usbBroadcastHandler =
                new UsbBroadcastHandler(this, usbController);

        usbController.checkExistingDevices();
        usbBroadcastHandler.register();
        bluetoothController =
                new BluetoothController(this, permissionLauncher);


        logStorageController =
                new LogStorageController(
                        this,
                        saveInputFields,
                        pickLogFolderLauncher
                );

        logStorageController.init();


        serviceController = new ServiceController(this);

        VersionChecker.check(USSOI_version,
                text -> runOnUiThread(() -> versionTextField.setText(text)));

        // short hand for
        // new VersionChecker.Callback() {
        //    @Override
        //    public void onResult(String text) {
        //        runOnUiThread(() -> versionTextField.setText(text));
        //    }
        //}

    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onResume() {
        super.onResume();
        updateServiceButtonState(serviceController.isRunning());
    }

    @Override
    protected void onStop() {
        super.onStop();
        usbBroadcastHandler.unregister();

    }

    private void initUi() {
        setViews();
        setupListeners();
        restoreUiState();
    }

    private void setViews() {
        usbInfoText = findViewById(R.id.usbInfoText);
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
                radioUsb.setTextColor(isChecked
                        ? Color.WHITE
                        : Color.parseColor("#1A1C1E"));
            }

            if (checkedId == R.id.btnBt) {
                radioBt.setTextColor(isChecked
                        ? Color.WHITE
                        : Color.parseColor("#1A1C1E"));
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
                btnKeepScreenOn.setBackgroundTintList(
                        ColorStateList.valueOf(Color.parseColor("#a182bf")));
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                btnKeepScreenOn.setText(R.string.timeout_removed);
                btnKeepScreenOn.setTextColor(Color.BLACK);
                btnKeepScreenOn.setBackgroundTintList(
                        ColorStateList.valueOf(
                                ContextCompat.getColor(this, android.R.color.white)));
            }
        });
    }

    private void restoreUiState() {
        urlIp.setText(pref.getString(KEY_url, ""));
        boolean btSelected = pref.getBoolean(KEY_BT_SWITCH, false);
        boolean usbSelected = pref.getBoolean(KEY_USB_Switch, false);

        if (btSelected) connectionToggleGroup.check(R.id.btnBt);
        else if (usbSelected) connectionToggleGroup.check(R.id.btnUsb);

        roomId.setText("••••••••");
        roomPwd.setText("••••••••");
    }

    @OptIn(markerClass = UnstableApi.class)
    private void handleServiceToggle() {
        if (serviceController.isRunning()) {
            serviceController.stop();
        } else {
            // Save inputs before starting
            String url = urlIp.getText().toString().trim();
            if (!url.endsWith("/")) url += "/";


            pref.edit()
                    .putString(KEY_url, url)
                    .putBoolean(KEY_BT_SWITCH, radioBt.isChecked())
                    .putBoolean(KEY_USB_Switch, radioUsb.isChecked())
                    .apply();

            // Handle Password Update securely
            String currentId = roomId.getText().toString().trim();
            String currentPwd = roomPwd.getText().toString();
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
                bluetoothController.selectDevicesAndStart(
                        () -> serviceController.start()
                );

            } else if (radioUsb.isChecked()) {
                usbDriverController.selectAndStart(
                        () -> serviceController.start()
                );
            } else {
                serviceController.start();
            }
        }
    }

    private void updateServiceButtonState(boolean isRunning) {
        serviceButton.setText(isRunning ? "Stop Service" : "Start Service");
    }

    private void setupPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (Boolean isGranted : permissions.values()) {
                        if (!isGranted) allGranted = false;
                    }
                    if (allGranted) Log.d(TAG, "All permissions granted.");
                    else
                        Toast.makeText(this, "Permissions required for operation", Toast.LENGTH_LONG).show();
                }
        );

    }

    private void showBatteryOptimizationNote() {
        new AlertDialog.Builder(this)
                .setTitle("Allow Background Activity")
                .setMessage(
                        "To work reliably in the background, the app needs to be excluded from battery optimizations.\n\n" +
                                "Without this, streaming and logging may stop."
                )
                .setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> powerController.requestIgnoreBatteryOptimization())
                .setNegativeButton("Cancel", (d, w) -> {
                    d.dismiss();
                })
                .show();
    }


}

