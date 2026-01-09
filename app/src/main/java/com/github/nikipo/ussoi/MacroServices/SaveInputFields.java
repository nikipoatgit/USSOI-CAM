package com.github.nikipo.ussoi.MacroServices;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class SaveInputFields {
    private static SaveInputFields instance;
    private final SharedPreferences prefs;

    // --- General Preferences ---
    static final String PREFS_NAME              = "UsbAppPrefs";
    public static final String USSOI_version  = "2.0.1";
    public static final String PREF_LOG_URI = "LOG_FOLDER_URI";

    // --- Network & Connectivity ---
    public static final String KEY_url          = "ip";
    public static final String KEY_api_path     = "control/client";
    public static final String streamingUrl     = "mse/client";
    public static final String UAR_TUNNEL = "ws/uartunnel?mode=fc";
    public static final String KEY_turn_array   = "turnArray";

    // --- Feature Toggles (Switches) ---
    public static final String KEY_webrtc_Enable   = "webrtc";
    public static final String KEY_mse_Enable      = "mse";
    public static final String KEY_local_recording = "localRecording";
    public static final String KEY_BT_SWITCH       = "btEnable";
    public static final String KEY_USB_Switch      = "usb";

    // --- Video & Stream Configuration ---
    public static final String KEY_LocalVideoBitrate = "localVideoBitrate";
    public static final String KEY_BAUD_RATE         = "baud_rate";

    // --- Session & Authentication ---
    public static final String KEY_RoomID       = "roomId";
    public static final String KEY_RoomPWD      = "roomPwd";
    public static final String KEY_Session_KEY  = "sessionKey";



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
                .putString(KEY_RoomPWD, roomId)
                .apply();
    }
    public SharedPreferences get_shared_pref(){ // returns obj obj of shared prefs
        return prefs;
    }

}
