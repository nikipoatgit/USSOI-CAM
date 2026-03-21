package com.github.nikipo.ussoi.storage;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public  class SaveInputFields {
    private static SaveInputFields instance;
    private final SharedPreferences prefs;
    public static final List<BluetoothDevice> selectedBtDevices =  new ArrayList<>();
    public static final JSONObject btDevices = new JSONObject();
    // --- General Preferences ---
    static final String PREFS_NAME              = "UsbAppPrefs";
    // todo UPDATE TO 2.1.0 LEGACY
    public static final String USSOI_version  = "2.0.1";
    public static final String PREF_LOG_URI = "LOG_FOLDER_URI";

    // --- Network & Connectivity ---
    public static final String KEY_url          = "ip";
    public static final String KEY_device_name    = "device_name";
    public static final String KEY_control_api_path = "/ws/device/control";
    public static final String KEY_auth_api_path = "api/device/authenticate";
    public static final String KEY_stream_api_path = "/ws/device/mse";
    public static final String KEY_data_api_path = "/ws/device/data";
    public static final String KEY_turn_list = "turnArray";

    // --- Feature Toggles (Switches) ---
    public static final String KEY_BT_SWITCH  = "bt";
    public static final String KEY_USB_Switch  = "usb";

    // --- Session & Authentication ---
    public static final String KEY_RoomID       = "roomId";
    public static final String KEY_RoomPWD      = "roomPwd";
    public static final String KEY_Session_KEY  = "sessionKey";
    public static final String KEY_Device_Id  = "deviceId";

    // Misc
    public static final String MASK = "••••••••";



    // Private constructor for singleton
    private SaveInputFields(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static synchronized SaveInputFields getInstance(Context context) {
        if (instance == null) {
            instance = new SaveInputFields(context);
        }
        return instance;
    }
    public void saveCredentials(String roomId,String roomPwd) {
        prefs.edit()
                .putString(KEY_RoomID, roomId)
                .putString(KEY_RoomPWD, roomPwd)
                .apply();
    }
    public SharedPreferences get_shared_pref(){ // returns obj obj of shared prefs
        return prefs;
    }

}
