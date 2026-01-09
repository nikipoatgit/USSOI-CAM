package com.github.nikipo.ussoi.Tunnel;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.UAR_TUNNEL;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.github.nikipo.ussoi.MacroServices.WebSocketHandler;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.psp.bluetoothlibrary.BluetoothListener;
import com.psp.bluetoothlibrary.Connection;
import com.psp.bluetoothlibrary.SendReceive;

public class BluetoothHandler {
    public static final String ACTION_BT_FAILED = "com.example.ussoi.BT_CONNECTION_FAILED";
    private static final String TAG = "BtHandel";
    private static BluetoothHandler instance;
    private Context context;
    private BluetoothDevice device;
    private Connection connection;
    private WebSocketHandler webSocketHandler;
    private SaveInputFields saveInputFields;
    private boolean isRunning = false;

    private BluetoothHandler(Context context){
        this.context = context.getApplicationContext();
        this.connection = new Connection(this.context);
        this.saveInputFields = SaveInputFields.getInstance(context);
    };
    public static synchronized BluetoothHandler getInstance(Context context){
        if (instance == null){
            instance = new BluetoothHandler(context);
        }
        return instance;
    }
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
    public void setDevice(BluetoothDevice device){
        this.device = device;
    }
    public void setupConnection(){
        isRunning = true;

        if(device == null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if(adapter == null){
            showToast("Bluetooth not supported");
            return;
        }
        if (!adapter.isEnabled()) {
            showToast("Please enable Bluetooth first");
            return;
        }
        // Android 12+ permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            showToast("BLUETOOTH_CONNECT permission required");
            return;
        }
        // Immediately connect to the supplied device
        connectToDevice(device);

        webSocketHandler = new WebSocketHandler(context,new WebSocketHandler.MessageCallback() {
            @Override
            public void onOpen() {
                Log.d(TAG, "Connected to WS");
            }

            @Override
            public void onPayloadReceivedText(String payload) {
            }
            @Override
            public void onPayloadReceivedByte(byte[] mavlinkBytes) {
                SendReceive.getInstance().send(mavlinkBytes);
            }
            @Override
            public void onClosed() {
                Log.d(TAG, "WS connection closed: " );
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error : " + error);
            }
        });

        SharedPreferences prefs = saveInputFields.get_shared_pref();
        webSocketHandler.setupConnection(UAR_TUNNEL,prefs.getString(KEY_Session_KEY,"block"));
    }
    private void connectToDevice(BluetoothDevice device){
        BluetoothListener.onConnectionListener connectionListener =
                new BluetoothListener.onConnectionListener() {
                    @Override
                    public void onConnectionStateChanged(android.bluetooth.BluetoothSocket socket, int state) {
                        switch (state) {
                            case Connection.CONNECTING:
                                Log.d(TAG, "Connecting…");
                                showToast("Connecting…");
                                break;
                            case Connection.CONNECTED:
                                Log.d(TAG, "Connected successfully");
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    if (ActivityCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.BLUETOOTH_CONNECT
                                    ) != PackageManager.PERMISSION_GRANTED) {
                                        return;
                                    }
                                }

                                showToast("Connected to " + device.getName());
                                setupBluetoothListener();
                                break;
                            case Connection.DISCONNECTED:
                                Log.d(TAG, "Disconnected");
                                stopAllServices();
                                break;

                        }
                    }

                    @Override
                    public void onConnectionFailed(int errorCode) {
                        Log.d(TAG, "Connection failed, code " + errorCode);
                        showToast("Connection failed");
                        Intent intent = new Intent(ACTION_BT_FAILED);
                        context.sendBroadcast(intent);
                        connection.disconnect();
                        stopAllServices();
                    }
                };

        connection.connect(device, true, connectionListener, null);
    }
    private void setupBluetoothListener() {
        // Create the listener object
        BluetoothListener.onReceiveListener myListener = new BluetoothListener.onReceiveListener() {
            @Override
            public void onReceived(String data) {
            }
            @Override
            public void onReceived(String data, byte[] mavlinkBytes) {
                webSocketHandler.connSendPayloadBytes(mavlinkBytes);
            }
        };
        //set listener
        SendReceive.getInstance().setOnReceiveListener(myListener);

    }
    public boolean isRunning(){
        return isRunning;
    }
    public void stopAllServices() {
        device = null;
        stopByUser();
    }

    public void stopByUser() {
        isRunning = false;
        if (connection.isConnected()) {
            connection.disconnect();
        }
        if (webSocketHandler != null) {
            webSocketHandler.closeConnection();
            webSocketHandler = null;
        }
    }

}
