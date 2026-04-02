package com.github.nikipo.ussoi.tunnel.usb;


import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_data_api_path;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.tunnel.Tunnel;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;


import java.io.IOException;
import java.util.Arrays;


public class UsbHandler implements Tunnel {

    private static final String TAG = "UsbHandler";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static UsbHandler instance;

    private UsbHandler(Context context) {
        this.context    = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        this.baudRate   = 115200;
    }

    public static synchronized UsbHandler getInstance(Context context) {
        if (instance == null) {
            instance = new UsbHandler(context);
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Object      usbLock    = new Object();
    private final Context     context;
    private final UsbManager  usbManager;

    private SharedPreferences  prefs;
    private SaveInputFields    saveInputFields;
    private WebSocketHandler   webSocketHandler;
    private Logging            logging;

    private UsbSerialDriver    driver;
    private UsbSerialPort      port;
    private String             usbName;
    private int                baudRate;
    private int                timeOut = 100;

    private Thread             readThread;
    private volatile boolean   reading = false;

    // -------------------------------------------------------------------------
    // Tunnel interface
    // -------------------------------------------------------------------------

    @Override
    public void init() {
        saveInputFields = SaveInputFields.getInstance(context);
        logging         = Logging.getIfInitialized();
        prefs           = saveInputFields.get_shared_pref();

        if (!checkDriver())                         return;
        UsbDevice device = driver.getDevice();
        if (!checkPermission(device))               return;
        UsbDeviceConnection connection = openConnection(device);
        if (connection == null)                     return;
        if (!openPort(connection, device))          return;

        setupWebSocket();
        startReading(port);
    }

    @Override
    public void close() {
        logging.log(TAG + " USB services stopped");
        stopReading();

        // Closing order matters: WebSocket before port
        if (webSocketHandler != null) {
            webSocketHandler.closeConnection();
            webSocketHandler = null;
            Log.d(TAG, "Socket closed");
        }

        if (port != null) {
            try {
                port.close();
                Log.d(TAG, "Port closed");
            } catch (IOException ignored) {}
            port = null;
        }
    }

    @Override
    public boolean isTunnelRunning() {
        return false;
    }

    @Override
    public String getTunnelName() {
        return usbName;
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    public void setDeviceConfig(UsbSerialDriver driver, String name, int baudRate) {
        this.driver   = driver;
        this.usbName  = name;
        this.baudRate = baudRate;
    }

    public void clearDriver() {
        this.driver = null;
    }

    // -------------------------------------------------------------------------
    // Private helpers — init steps
    // -------------------------------------------------------------------------

    private boolean checkDriver() {
        if (driver != null) return true;
        Log.w(TAG, "Driver is null");
        logging.log(TAG + " Driver is null");
        showToast("Driver is null");
        return false;
    }

    private boolean checkPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) return true;
        Log.d(TAG, "No USB permission for device");
        logging.log(TAG + " No USB permission for device");
        showToast("No USB permission for device");
        return false;
    }

    private UsbDeviceConnection openConnection(UsbDevice device) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection != null) return connection;
        Log.e(TAG, "Failed to open USB device connection");
        logging.log(TAG + " Failed to open USB device connection");
        showToast("Failed to open USB device connection");
        return null;
    }

    /** Opens the serial port and configures baud/framing. Returns false on failure. */
    private boolean openPort(UsbDeviceConnection connection, UsbDevice device) {
        port    = driver.getPorts().get(0);
        timeOut = usbWriteTimeoutMs(baudRate);

        try {
            port.open(connection);
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            String serialCfg = "USB SERIAL CONFIG | baud=" + baudRate + ", dataBits=8, stopBits=1, parity=NONE";
            Log.i(TAG, serialCfg);
            logging.log(TAG + " " + serialCfg);
            logging.log(TAG + " VID=0x" + Integer.toHexString(device.getVendorId())
                    + ", PID=0x" + Integer.toHexString(device.getProductId())
                    + ", baud=" + baudRate + ", 8N1");
            return true;

        } catch (IOException e) {
            Log.e(TAG, "USB SERIAL CONFIG FAILED", e);
            logging.log(TAG + " USB SERIAL CONFIG FAILED: " + e.getMessage());
            tryClosePort();
            return false;
        }
    }

    private void tryClosePort() {
        if (port == null) return;
        try {
            port.close();
        } catch (IOException e) {
            Log.w(TAG, "USB port close failed", e);
            logging.log(TAG + " USB port close failed");
        }
    }

    private void setupWebSocket() {
        webSocketHandler = new WebSocketHandler(context, new WebSocketHandler.MessageCallback() {
            @Override
            public void onOpen() {
                Log.d(TAG, "Connected to WS");
            }

            @Override
            public void onPayloadReceivedText(String payload) {}

            @Override
            public void onPayloadReceivedByte(byte[] byteData) {
                synchronized (usbLock) {
                    if (port == null) return;
                    try {
                        port.write(byteData, timeOut);
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing to USB port from WebSocket", e);
                        logging.log(TAG + " Error writing to USB port from WebSocket: " + e);
                    }
                }
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

    // -------------------------------------------------------------------------
    // Private helpers — read loop
    // -------------------------------------------------------------------------

    private void startReading(UsbSerialPort port) {
        stopReading();
        reading = true;

        readThread = new Thread(() -> {
            byte[] buffer              = new byte[4096];
            final int READ_WAIT_MILLIS = 1000;

            while (reading) {
                try {
                    if (port == null) break;
                    int len = port.read(buffer, READ_WAIT_MILLIS);
                    if (len > 0) {
                        webSocketHandler.connSendPayloadBytes(Arrays.copyOf(buffer, len));
                    }
                } catch (IOException e) {
                    logging.log(TAG + " Error reading from USB port: " + e);
                    break;
                }
            }
            reading = false;
        }, "UsbReadLoop");

        readThread.start();
    }

    private void stopReading() {
        reading = false;
        if (readThread != null) {
            try { readThread.join(500); } catch (InterruptedException ignored) {}
            readThread = null;
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private static int usbWriteTimeoutMs(int baud) {
        if (baud >= 115200) return 100;
        if (baud >= 57600)  return 150;
        if (baud >= 38400)  return 200;
        if (baud >= 19200)  return 300;
        return 500;
    }
}

