package com.github.nikipo.ussoi.tunnel;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_data_api_path;
import static com.github.nikipo.ussoi.ui.UssoiStrings.EMPTY;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;

import java.io.IOException;
import java.util.UUID;

public class BluetoothHandler implements Tunnel, ConnectedThread.DataListener {
    private static final String TAG = "NativeBtTunnel";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothDevice device;
    private BluetoothSocket socket;
    private ConnectedThread connectedThread;
    private WebSocketHandler webSocketHandler;

    private volatile boolean isRunning = false;
    private volatile boolean cancelled = false;

    public BluetoothHandler(Context context, BluetoothDevice device) {
        this.context = context;
        this.device = device;
    }

    @Override
    public void init() {
        // Intentionally a no-op now — setup happens on Start()
        Log.d(TAG, "init() called - no-op, waiting for Start().");
    }

    @Override
    @SuppressLint("MissingPermission")
    public void Start() {
        if (isRunning) {
            Log.d(TAG, "Bluetooth Tunnel hardware link is already running.");
            return;
        }

        isRunning = true;
        cancelled = false;

        // Bring up the WebSocket alongside the hardware link
        setupWebSocket();

        // Spin up async Bluetooth connection thread
        new Thread(() -> {
            BluetoothSocket localSocket;
            try {
                localSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                localSocket.connect(); // Blocks until connection succeeds or fails
            } catch (IOException e) {
                Log.e(TAG, "Connection failed for: " + device.getAddress(), e);
                isRunning = false;
                return;
            }

            // Stop() might have run while blocked in connect() above.
            if (cancelled) {
                try { localSocket.close(); } catch (IOException ignored) {}
                isRunning = false;
                return;
            }

            socket = localSocket;
            connectedThread = new ConnectedThread(socket, this);
            connectedThread.start();
            Log.d(TAG, "Bluetooth Link Established and running.");
        }).start();
    }

    @Override
    public void Stop() {
        Log.d(TAG, "Stopping Bluetooth hardware link...");
        isRunning = false;
        cancelled = true;

        // Tear down Bluetooth
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {}
        socket = null;

        // Tear down WebSocket
        if (webSocketHandler != null) {
            webSocketHandler.close();
            webSocketHandler = null;
            Log.d(TAG, "WebSocket pipeline torn down on Stop().");
        }
    }

    @Override
    public void close() {
        // Class is being discarded — make sure everything is stripped down.
        Stop();
        Log.d(TAG, "BluetoothHandler fully closed.");
    }

    private void setupWebSocket() {
        webSocketHandler = new WebSocketHandler(context, new WebSocketHandler.MessageCallback() {
            @Override
            public void onOpen() { Log.d(TAG, "WS Open: " + device.getAddress()); }

            @Override
            public void onPayloadReceivedText(String payload) {}

            @Override
            public void onPayloadReceivedByte(byte[] byteData) {
                Log.d(TAG, "WS->BT " + byteData.length + "B: " + preview(byteData));
                // Route inbound network bytes down physical pipe if started
                if (connectedThread != null && isRunning) {
                    connectedThread.write(byteData);
                }
            }

            @Override
            public void onClosed() {
                Log.d(TAG, "WS Connection Closed");
            }

            @Override
            public void onError(String error) { Log.e(TAG, "WS Error: " + error); }
        });

        String sessionKey = SaveInputFields.getInstance(context)
                .get_shared_pref()
                .getString(KEY_Session_KEY, EMPTY);
        webSocketHandler.setupConnection(KEY_data_api_path+getTunnelName(), sessionKey);
    }

    // ConnectedThread.DataListener — bytes coming from physical Bluetooth link
    @Override
    public void onDataReceived(byte[] data) {
        Log.d(TAG, "BT->WS " + data.length + "B: " + preview(data));
        if (webSocketHandler != null) {
            webSocketHandler.sendBytes(data);
        }
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Hardware disconnected on its own.");
        Stop();
    }

    @Override
    public boolean isTunnelRunning() { return isRunning; }

    @SuppressLint("MissingPermission")
    @Override
    public String getTunnelName() { return device.getName()+device.getAddress(); }


    // add near top of class
    private static String preview(byte[] data) {
        int n = Math.min(8, data.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02X ", data[i]));
        return sb.toString().trim();
    }
}