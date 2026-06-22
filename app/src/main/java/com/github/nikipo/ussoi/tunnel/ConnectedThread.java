package com.github.nikipo.ussoi.tunnel;


import android.bluetooth.BluetoothSocket;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;


/**
 * *****************************************************************************
 *
 * @author nikipo
 * *****************************************************************************
 * @file ConnectedThread
 * @date 6/20/26 9:08 PM
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
public class ConnectedThread extends Thread {

    private static final String TAG = "ConnectedThread";

    /** Lets the owning tunnel (e.g. BluetoothHandler) react to inbound data / disconnects. */
    public interface DataListener {
        void onDataReceived(byte[] data);
        void onDisconnected();
    }

    private final BluetoothSocket socket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final DataListener listener;

    private volatile boolean running = true;

    public ConnectedThread(BluetoothSocket socket, DataListener listener) {
        this.socket = socket;
        this.listener = listener;

        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "Error creating independent streams", e);
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    @Override
    public void run() {
        if (mmInStream == null) {
            Log.e(TAG, "Input stream unavailable; thread exiting immediately.");
            notifyDisconnected();
            return;
        }

        byte[] buffer = new byte[1024];

        while (running) {
            try {
                int bytes = mmInStream.read(buffer);

                if (bytes == -1) {
                    // End of stream — remote side closed the connection cleanly.
                    Log.d(TAG, "Input stream closed; thread dying.");
                    break;
                }
                if (bytes > 0 && listener != null) {
                    listener.onDataReceived(Arrays.copyOf(buffer, bytes));
                }

            } catch (IOException e) {
                Log.d(TAG, "Input stream disconnected; thread dying.");
                break;
            }
        }

        notifyDisconnected();
    }

    private void notifyDisconnected() {
        if (listener != null) listener.onDisconnected();
    }

    // Call this from the main activity/tunnel class to send data to the remote device
    public void write(byte[] bytes) {
        if (mmOutStream == null) {
            Log.e(TAG, "Output stream unavailable; cannot write.");
            return;
        }
        try {
            mmOutStream.write(bytes);
            mmOutStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error writing to stream", e);
        }
    }

    // Call this from the main activity/tunnel class to shut down the connection safely
    public void cancel() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}