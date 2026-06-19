package com.github.nikipo.ussoi.tunnel.bt;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_data_api_path;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
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

import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.tunnel.Tunnel;
import com.psp.bluetoothlibrary.BluetoothListener;
import com.psp.bluetoothlibrary.Connection;
import com.psp.bluetoothlibrary.SendReceive;

public class BluetoothHandler implements Tunnel {

    public static final String ACTION_BT_FAILED = "com.example.ussoi.BT_CONNECTION_FAILED";
    private static final String TAG = "BtHandler";
    private final Context         context;
    private final Connection      connection;
    private final SaveInputFields saveInputFields;

    private BluetoothDevice  device;
    private WebSocketHandler webSocketHandler;
    private boolean          isRunning = false;

    public BluetoothHandler(Context context) {
        this.context         = context.getApplicationContext();
        this.connection      = new Connection(this.context);
        this.saveInputFields = SaveInputFields.getInstance(context);
    }

    // Configuration
    public void setDevice(BluetoothDevice device) {
        this.device = device;
    }

    // Tunnel interface
    @Override
    public void init() {
        if (!checkPreconditions()) return;

        connectToDevice(device);
        setupWebSocket();
        isRunning = true;
    }

    @Override
    public void close() {
        isRunning = false;
        SendReceive.getInstance().setOnReceiveListener(null);

        if (connection.isConnected()) {
            connection.disconnect();
        }

        if (webSocketHandler != null) {
            webSocketHandler.close();
            webSocketHandler = null;
        }
    }

    @Override
    public boolean isTunnelRunning() {
        return isRunning;
    }

    @Override
    public String getTunnelName() {
        return device != null ? device.getName() : "null";
    }

    // Private helpers — init steps
    /** Validates device, adapter state, and runtime permissions before connecting. */
    private boolean checkPreconditions() {
        if (device == null) {
            Log.w(TAG, "No device set");
            return false;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            showToast("Bluetooth not supported");
            return false;
        }

        if (!adapter.isEnabled()) {
            showToast("Please enable Bluetooth first");
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            showToast("BLUETOOTH_CONNECT permission required");
            return false;
        }

        return true;
    }

    private void connectToDevice(BluetoothDevice device) {
        connection.connect(device, true, new BluetoothListener.onConnectionListener() {
            @Override
            public void onConnectionStateChanged(BluetoothSocket socket, int state) {
                switch (state) {
                    case Connection.CONNECTING:
                        Log.d(TAG, "Connecting…");
                        showToast("Connecting…");
                        break;

                    case Connection.CONNECTED:
                        Log.d(TAG, "Connected successfully");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                                        != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }
                        showToast("Connected to " + device.getName());
                        setupBluetoothListener();
                        break;

                    case Connection.DISCONNECTED:
                        Log.d(TAG, "Disconnected");
                        close();
                        break;
                }
            }

            @Override
            public void onConnectionFailed(int errorCode) {
                Log.d(TAG, "Connection failed, code " + errorCode);
                showToast("Connection failed");
                context.sendBroadcast(new Intent(ACTION_BT_FAILED));
                connection.disconnect();
                close();
            }
        }, null);
    }

    private void setupBluetoothListener() {
        SendReceive.getInstance().setOnReceiveListener(new BluetoothListener.onReceiveListener() {
            @Override
            public void onReceived(String data) {}

            @Override
            public void onReceived(String data, byte[] byteData) {
                webSocketHandler.sendBytes(byteData);
            }
        });
    }

    private void setupWebSocket() {
        SharedPreferences prefs = saveInputFields.get_shared_pref();

        webSocketHandler = new WebSocketHandler(context, new WebSocketHandler.MessageCallback() {
            @Override
            public void onOpen() {
                Log.d(TAG, "Connected to WS");
            }

            @Override
            public void onPayloadReceivedText(String payload) {}

            @Override
            public void onPayloadReceivedByte(byte[] byteData) {
                SendReceive.getInstance().send(byteData);
            }

            @Override
            public void onClosed() {
                Log.d(TAG, "WS connection closed");
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "WS error: " + error);
            }
        });

        webSocketHandler.setupConnection(KEY_data_api_path, prefs.getString(KEY_Session_KEY, "block"));
    }

    // Utility
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}