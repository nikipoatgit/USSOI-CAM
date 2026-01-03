package com.github.nikipo.ussoi;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_BT_SWITCH;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_url;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_USB_Switch;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.PREF_LOG_URI;

import android.Manifest;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.util.UnstableApi;

import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.github.nikipo.ussoi.Tunnel.BluetoothHandler;
import com.github.nikipo.ussoi.Tunnel.UsbHandler;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.example.ussoi.USB_PERMISSION";
    public static final String ACTION_BT_FAILED = "com.example.ussoi.ACTION_BT_FAILED";
    private static final String MASK = "••••••••";
    private static final int REQ_PICK_LOG_FOLDER = 9001;

    // Managers
    private UsbManager usbManager;
    private UsbHandler usbHandler;
    private SaveInputFields saveInputFields;
    private BluetoothHandler bluetoothHandler;
    private Logging logging;
    private SharedPreferences pref;
    private ActivityResultLauncher<Intent> pickLogFolderLauncher;

    // UI Components
    private TextView usbInfoText;
    private EditText urlIp, roomId, roomPwd;
    private Button serviceButton;
    private MaterialButton radioUsb, radioBt;
    private MaterialButtonToggleGroup connectionToggleGroup;

    // State
    private BluetoothDevice selectedBtDevice;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private boolean keepScreenOn = false;
    private MaterialButton btnKeepScreenOn;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        saveInputFields = SaveInputFields.getInstance(this);
        pref = saveInputFields.get_shared_pref();
        logging = Logging.getInstance(this);

        pickLogFolderLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() != RESULT_OK || result.getData() == null)
                                return;

                            Uri treeUri = result.getData().getData();
                            if (treeUri == null) return;


                            getContentResolver().takePersistableUriPermission(
                                    treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );

                            pref.edit()
                                    .putString(SaveInputFields.PREF_LOG_URI, treeUri.toString())
                                    .apply();

                            createUssoiFolders(treeUri);

                            if (!isIgnoringBatteryOptimizations()) {
                                showBatteryOptimizationNote();
                            }
                        }
                );
        logging.log("Application Started / Main Activity Created");

        initViews();
        setupListeners();// sets listener to btn

        initLogFolder();

        restoreUiState();

        setupPermissionLauncher();
        registerBroadcastReceivers();

        checkExistingUsbDevices();
        checkAndRequestRuntimePermissions();
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onResume() {
        super.onResume();
        updateServiceButtonState(isMyServiceRunning(ServiceManager.class));
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { logging.log(TAG+" usbReceiver was not registered");}
        try { unregisterReceiver(btFailReceiver); } catch (Exception ignored) {logging.log(TAG + " btFailReceiver was not registered");}
    }

    // --- Initialization Methods ---

    private void initViews() {
        usbInfoText = findViewById(R.id.usbInfoText);
        urlIp = findViewById(R.id.url_ip);
        roomId = findViewById(R.id.roomId);
        roomPwd = findViewById(R.id.roomPwd);
        serviceButton = findViewById(R.id.serviceButton);
        radioUsb = findViewById(R.id.btnUsb);
        radioBt = findViewById(R.id.btnBt);
        connectionToggleGroup = findViewById(R.id.connectionToggle);
        btnKeepScreenOn = findViewById(R.id.btnKeepScreenOn);
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
                btnKeepScreenOn.setText("Screen Always On");
                btnKeepScreenOn.setTextColor(Color.WHITE);
                btnKeepScreenOn.setBackgroundTintList(
                        ColorStateList.valueOf(Color.parseColor("#a182bf")));
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                btnKeepScreenOn.setText("Timeout Removed");
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

    // --- Service Logic ---

    @OptIn(markerClass = UnstableApi.class)
    private void handleServiceToggle() {
        if (isMyServiceRunning(ServiceManager.class)) {
            stopMainService();
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
                bluetoothHandler = BluetoothHandler.getInstance(this);
                pickBluetoothDeviceThenStart();
            }
            else if (radioUsb.isChecked()) {
                usbHandler = UsbHandler.getInstance(this);
                usbHandler.clearDriver();
                selectUSBDriverThenStart();
            }
            else {
                startMainService();
            }
        }
    }

    private void selectUSBDriverThenStart() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        if (drivers.isEmpty()) {
            Log.w(TAG, "No USB serial drivers available");
            Toast.makeText(this,
                    "No USB serial drivers available",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> labels = new ArrayList<>();
        for (UsbSerialDriver d : drivers) {
            UsbDevice dev = d.getDevice();
            labels.add(
                    "VID: " + Integer.toHexString(dev.getVendorId()) +
                            "  PID: " + Integer.toHexString(dev.getProductId()) +
                            "  Ports: " + d.getPorts().size()
            );
        }

        new AlertDialog.Builder(this)
                .setTitle("Select USB Device")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    UsbSerialDriver selectedDriver = drivers.get(which);
                    usbHandler.setDriver(selectedDriver);
                    UsbDevice device = selectedDriver.getDevice();

                    if (!usbManager.hasPermission(device)) {
                        requestUsbPermission(device);
                    } else {
                        startMainService();
                    }
                })
                .setCancelable(true)
                .setNegativeButton("Cancel", (dialog, which) -> {
                    usbHandler.clearDriver();
                });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startMainService() {
        Log.d(TAG, "Starting Main Service...");
        Intent serviceIntent = new Intent(this, ServiceManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        updateServiceButtonState(true);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void stopMainService() {
        Log.d(TAG, "Stopping Main Service...");
        stopService(new Intent(this, ServiceManager.class));
        updateServiceButtonState(false);
    }

    private void updateServiceButtonState(boolean isRunning) {
        serviceButton.setText(isRunning ? "Stop Service" : "Start Service");
    }

    private boolean isMyServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    // --- Bluetooth Logic ---

    private void pickBluetoothDeviceThenStart() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasBluetoothPermissions()) return; // Permission check

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {

                Toast.makeText(this, "Bluetooth Permission Not Granted", Toast.LENGTH_SHORT).show();
                requestBluetoothConnect();
                return;
            }
        }

        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired devices found", Toast.LENGTH_SHORT).show();
            return;
        }

        List<BluetoothDevice> devices = new ArrayList<>(pairedDevices);
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            names[i] = devices.get(i).getName() + "\n" + devices.get(i).getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Bluetooth Device")
                .setItems(names, (dialog, which) -> {
                    selectedBtDevice = devices.get(which);
                    bluetoothHandler.setDevice(selectedBtDevice);
                    startMainService();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private void requestBluetoothConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Only request on Android 12+
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN // Add if you are also scanning
            });
        }
    }

    // --- Permissions Logic ---

    private void setupPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (Boolean isGranted : permissions.values()) {
                        if (!isGranted) allGranted = false;
                    }
                    if (allGranted) Log.d(TAG, "All permissions granted.");
                    else Toast.makeText(this, "Permissions required for operation", Toast.LENGTH_LONG).show();
                }
        );
    }

    private void checkAndRequestRuntimePermissions() {
        List<String> needed = new ArrayList<>();

        // Add permissions based on Android version
        addPermIfMissing(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        addPermIfMissing(needed, Manifest.permission.ACCESS_COARSE_LOCATION);
        addPermIfMissing(needed, Manifest.permission.READ_PHONE_STATE);
        addPermIfMissing(needed, Manifest.permission.CAMERA);
        addPermIfMissing(needed, Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addPermIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addPermIfMissing(needed, Manifest.permission.BLUETOOTH_SCAN);
            addPermIfMissing(needed, Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            addPermIfMissing(needed, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }


        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void addPermIfMissing(List<String> list, String perm) {
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // --- USB Logic ---

    private void checkExistingUsbDevices() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            usbInfoText.setText("No USB device connected");
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        PendingIntent pi = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                flags
        );
        usbManager.requestPermission(device, pi);
    }

    // --- Broadcast Receivers ---

    private void registerBroadcastReceivers() {
        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, usbFilter);
    }


    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (device == null) return;

                String info =
                        "USB Detected\n" +
                                "Name: " + device.getDeviceName() + "\n" +
                                "Vendor ID: " + device.getVendorId() + "\n" +
                                "Product ID: " + device.getProductId() + "\n" +
                                "Class: " + device.getDeviceClass() + "\n" +
                                "SubClass: " + device.getDeviceSubclass() + "\n" +
                                "Protocol: " + device.getDeviceProtocol() + "\n" +
                                "Interfaces: " + device.getInterfaceCount();

                usbInfoText.setText(info);
                logging.log(TAG + " USB detected (no permission requested)");
            }

            else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                usbInfoText.setText("USB device disconnected");
            }
        }
    };

    private final BroadcastReceiver btFailReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Toast.makeText(context, "Bluetooth connection failed", Toast.LENGTH_SHORT).show();
        }
    };

    // --- Export Result ---

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_LOG_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();

            assert treeUri != null;
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );

            getSharedPreferences("storage", MODE_PRIVATE)
                    .edit()
                    .putString(PREF_LOG_URI, treeUri.toString())
                    .apply();

            createUssoiFolders(treeUri);
        }
    }

    // ----- log folder ------


    private void initLogFolder() {
        SharedPreferences pref = saveInputFields.get_shared_pref();
        String uriStr = pref.getString(SaveInputFields.PREF_LOG_URI, null);

        if (uriStr == null) {
            showLogFolderNote();
            return;
        }


        Uri treeUri = Uri.parse(uriStr);
        if (!hasPersistedPermission(treeUri)) {
            pref.edit().remove(SaveInputFields.PREF_LOG_URI).apply();
            showLogFolderNote();
            return;
        }

        try {
            createUssoiFolders(treeUri);
        } catch (SecurityException e) {
            pref.edit().remove(SaveInputFields.PREF_LOG_URI).apply();
            showLogFolderNote();
        }
    }
    private boolean hasPersistedPermission(Uri uri) {
        List<UriPermission> perms =
                getContentResolver().getPersistedUriPermissions();
        for (UriPermission p : perms) {
            if (p.getUri().equals(uri) && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }


    private void createUssoiFolders(Uri treeUri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.canWrite()) {
                throw new SecurityException("No write access");
            }

            DocumentFile log = root.findFile("log");
            if (log == null) root.createDirectory("log");

            DocumentFile videos = root.findFile("videos");
            if (videos == null) root.createDirectory("videos");

        } catch (Exception e) {
            pref.edit().remove(SaveInputFields.PREF_LOG_URI).apply();
            showLogFolderNote();
        }
    }

    private void showLogFolderNote() {
        new AlertDialog.Builder(this)
                .setTitle("Select Logging Folder")
                .setMessage(
                        "Please select a folder where the app will store logs and videos.\n\n" +
                                "Folder access is required for the application to work.\n\n"
                )
                .setCancelable(false)
                .setPositiveButton("Select Folder", (d, w) -> {
                    launchLogFolderPicker();
                })
                .setNegativeButton("Close App", (d, w) -> {
                    d.dismiss();
                    finishAffinity();
                    // TODO : when i manually remove permission of external folder the app crashes
                })
                .show();
    }
    private void launchLogFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pickLogFolderLauncher.launch(intent);
    }
    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    //---- no bat optimisation ------
    private void showBatteryOptimizationNote() {
        new AlertDialog.Builder(this)
                .setTitle("Allow Background Activity")
                .setMessage(
                        "To work reliably in the background, the app needs to be excluded from battery optimizations.\n\n" +
                                "Without this, streaming and logging may stop."
                )
                .setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> requestIgnoreBatteryOptimization())
                .setNegativeButton("Cancel", (d, w) -> {
                    d.dismiss();
                })
                .show();
    }
    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }



}