package com.github.nikipo.ussoi.Tunnel;


import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_BAUD_RATE;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.UAR_TUNNEL;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.MacroServices.WebSocketHandler;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;


import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

public class UsbHandler {
    private static final String TAG = "UsbHandler";
    private static UsbHandler instance;
    private SharedPreferences prefs;
    private SaveInputFields saveInputFields;
    private static UsbManager usbManager;
    private WebSocketHandler webSocketHandler;
    private Thread readThread;
    private volatile boolean reading = false;
    private volatile boolean wantReconnect = false;
    private volatile boolean reconnecting = false;
    private static final int RECONNECT_DELAY_MS = 1500;
    private int selectedVid = -1;
    private int selectedPid = -1;

    private Context context;
    private Logging logging;
    private UsbSerialDriver driver ;
    private UsbSerialPort port;
    private int timeOut = 100;
    private UsbHandler(Context context){
        this.context = context.getApplicationContext();
        usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    };
    public static synchronized UsbHandler getInstance(Context context){
        if (instance == null){
            instance = new UsbHandler(context);
        }
        return instance;
    }
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
    public void setupConnection(){
        wantReconnect = true;
        saveInputFields = SaveInputFields.getInstance(context);
        logging = Logging.getIfInitialized();
        prefs = saveInputFields.get_shared_pref();


        if (driver == null){
            Log.w(TAG, " driver id null");
            logging.log(TAG + " driver id null");
            showToast( "driver id null");
            return;
        }

        UsbDevice device = driver.getDevice();

        if (!usbManager.hasPermission(device)) {
            logging.log(TAG + " NO USB permission for device");
            showToast( "No USB permission for device");
            Log.d(TAG, "No USB permission for device");
            return;
        }


        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            logging.log(TAG + " Failed to open USB device connection");
            Log.e(TAG, "Failed to open USB device connection");
            showToast("Failed to open USB device connection");
            return;
        }

        port = driver.getPorts().get(0);

        try {
            port.open(connection);

            int baud = prefs.getInt(KEY_BAUD_RATE, 115200);

            timeOut = usbWriteTimeoutMs(baud);

            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            String serialCfg =
                    "USB SERIAL CONFIG | " +
                            "baud=" + baud +
                            ", dataBits=8" +
                            ", stopBits=1" +
                            ", parity=NONE";

            logging.log(
                    TAG + " USB SERIAL CONFIG | " +
                            "VID=0x" + Integer.toHexString(device.getVendorId()) +
                            ", PID=0x" + Integer.toHexString(device.getProductId()) +
                            ", baud=" + baud +
                            ", 8N1"
            );

            Log.i(TAG, serialCfg);
            logging.log(TAG + " " + serialCfg);
        } catch (IOException e) {
            String err = "USB SERIAL CONFIG FAILED";
            Log.e(TAG, err, e);
            logging.log(TAG + " " + err + " : " + e.getMessage());

            try {
                port.close();
            } catch (IOException closeErr) {
                Log.w(TAG, "USB port close failed", closeErr);
                logging.log(TAG + " USB port close failed");
            }
            return;
        }

        webSocketHandler = new WebSocketHandler(context,new WebSocketHandler.MessageCallback() {
            public void onOpen() {
                Log.d(TAG, "Connected to WS");
            }

            @Override
            public void onPayloadReceivedText(String payload) {
            }
            @Override
            public void onPayloadReceivedByte(byte[] mavlinkBytes) {
                try {
                    synchronized (port) {
                        port.write(mavlinkBytes, timeOut);
                    }
                } catch (IOException e) {
                    stopAllServices();
                    scheduleReconnect();
                    Log.e(TAG, "Error writing to USB port from WebSocket", e);
                    logging.log(TAG + " Error writing to USB port from WebSocket"+ e);
                }
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
        webSocketHandler.setupConnection(UAR_TUNNEL,prefs.getString(KEY_Session_KEY,"block"));
        startReading(port);
    }
    private static int usbWriteTimeoutMs(int baud) {
        if (baud >= 115200) return 100;
        if (baud >= 57600)  return 150;
        if (baud >= 38400)  return 200;
        if (baud >= 19200)  return 300;
        return 500; // ≤ 9600
    }
    public void setDriver(UsbSerialDriver d) {
        this.driver = d;
        UsbDevice dev = d.getDevice();
        selectedVid = dev.getVendorId();
        selectedPid = dev.getProductId();
    }
    public void clearDriver() {
        this.driver = null;
    }

    public boolean isRunning(){
        return reading;
    }
    private void startReading(UsbSerialPort port) {
        stopReading();
        reading = true;

        readThread = new Thread(() -> {
            byte[] buffer = new byte[4096]; // allocate buffer
            final int READ_WAIT_MILLIS = 1000;

            while (reading) {
                try {
                    int len = port.read(buffer, READ_WAIT_MILLIS);
                    if (len > 0) {
                        byte[] mavlinkBytes = java.util.Arrays.copyOf(buffer, len);
                        webSocketHandler.connSendPayloadBytes(mavlinkBytes);
                    }
                } catch (IOException e) {
                    stopAllServices();
                    scheduleReconnect();
                    logging.log(TAG + " Error Reading to USB port from WebSocket"+ e);
                    break;

                }
            }
            reading = false;
        }, "UsbReadLoop");
        readThread.start();
    }

    private void scheduleReconnect() {
        if (!wantReconnect || reconnecting) return;

        reconnecting = true;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            reconnecting = false;
            tryReconnect();
        }, RECONNECT_DELAY_MS);
    }
    private void tryReconnect() {
        if (!wantReconnect) return;

        List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber()
                        .findAllDrivers(usbManager);

        if (drivers.isEmpty()) {
            scheduleReconnect(); // device not back yet
            return;
        }

        UsbSerialDriver matched = null;

        for (UsbSerialDriver d : drivers) {
            UsbDevice dev = d.getDevice();
            if (dev.getVendorId() == selectedVid &&
                    dev.getProductId() == selectedPid) {
                matched = d;
                break;
            }
        }

        if (matched == null) {
            scheduleReconnect(); // original device not back yet
            return;
        }

        UsbDevice device = matched.getDevice();

        if (!usbManager.hasPermission(device)) {
            wantReconnect = false;
            logging.log(TAG + " USB permission lost, reconnect disabled");
            return;
        }

        setDriver(matched);
        setupConnection();
    }


    public void stopReading() {
        reading = false;
        if (readThread != null) {
            try { readThread.join(500); } catch (InterruptedException ignored) {}
            readThread = null;
        }
    }
    public void stopAllServices(){
        logging.log(TAG + " USB services Stopped");
        stopReading();
        if (port != null) {
            try { port.close(); } catch (IOException ignored) {}
            port = null;
        }

        clearDriver();
        if (webSocketHandler != null ) {
            webSocketHandler.closeConnection();
            webSocketHandler = null;
            Log.d(TAG,"socket closed");

        }
    }
}
