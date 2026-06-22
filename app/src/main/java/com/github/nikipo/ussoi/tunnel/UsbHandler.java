package com.github.nikipo.ussoi.tunnel;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_data_api_path;
import static com.github.nikipo.ussoi.ui.UssoiStrings.EMPTY;

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
        // Intentionally a no-op now — setup happens on Start()
        Log.d(TAG, "init() called - no-op, waiting for Start().");
    }

    @Override
    public void Start() {
        if (isTunnelRunning()) {
            Log.d(TAG, "USB Tunnel is already running.");
            return;
        }

        // Resolve lightweight dependencies (cheap singleton lookups, no connections opened)
        saveInputFields = SaveInputFields.getInstance(context);
        logging         = Logging.getIfInitialized();
        prefs           = saveInputFields.get_shared_pref();

        if (!checkDriver())                         return;
        UsbDevice device = driver.getDevice();
        if (!checkPermission(device))               return;
        UsbDeviceConnection connection = openConnection(device);
        if (connection == null)                     return;
        if (!openPort(connection, device))          return;

        // Bring up the WebSocket alongside the hardware link
        setupWebSocket();

        startReading(port);
        Log.d(TAG, "USB Tunnel hardware link started.");
    }

    @Override
    public void Stop() {
        Log.d(TAG, "Stopping USB hardware link...");
        stopReading();
        tryClosePort();

        // Tear down WebSocket alongside the hardware link
        if (webSocketHandler != null) {
            webSocketHandler.close();
            webSocketHandler = null;
            Log.d(TAG, "WebSocket pipeline torn down on Stop().");
        }
    }

    @Override
    public void close() {
        if (logging != null) {
            logging.log(TAG + " USB services stopped completely");
        }

        // Class is being discarded — make sure everything is stripped down.
        Stop();
    }

    @Override
    public boolean isTunnelRunning() {
        return reading && port != null;
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
    // Private helpers — init / start steps
    // -------------------------------------------------------------------------
    private boolean checkDriver() {
        if (driver != null) return true;
        Log.w(TAG, "Driver is null");
        if (logging != null) logging.log(TAG + " Driver is null");
        showToast("Driver is null");
        return false;
    }

    private boolean checkPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) return true;
        Log.d(TAG, "No USB permission for device");
        if (logging != null) logging.log(TAG + " No USB permission for device");
        showToast("No USB permission for device");
        return false;
    }

    private UsbDeviceConnection openConnection(UsbDevice device) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection != null) return connection;
        Log.e(TAG, "Failed to open USB device connection");
        if (logging != null) logging.log(TAG + " Failed to open USB device connection");
        showToast("Failed to open USB device connection");
        return null;
    }

    private boolean openPort(UsbDeviceConnection connection, UsbDevice device) {
        if (driver == null || driver.getPorts().isEmpty()) return false;
        port    = driver.getPorts().get(0);
        timeOut = usbWriteTimeoutMs(baudRate);

        try {
            port.open(connection);
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            String serialCfg = "USB SERIAL CONFIG | baud=" + baudRate + ", dataBits=8, stopBits=1, parity=NONE";
            Log.i(TAG, serialCfg);
            if (logging != null) {
                logging.log(TAG + " " + serialCfg);
                logging.log(TAG + " VID=0x" + Integer.toHexString(device.getVendorId())
                        + ", PID=0x" + Integer.toHexString(device.getProductId())
                        + ", baud=" + baudRate + ", 8N1");
            }
            return true;

        } catch (IOException e) {
            Log.e(TAG, "USB SERIAL CONFIG FAILED", e);
            if (logging != null) logging.log(TAG + " USB SERIAL CONFIG FAILED: " + e.getMessage());
            tryClosePort();
            return false;
        }
    }

    private void tryClosePort() {
        if (port == null) return;
        try {
            port.close();
            Log.d(TAG, "Port closed");
        } catch (IOException e) {
            Log.w(TAG, "USB port close failed", e);
            if (logging != null) logging.log(TAG + " USB port close failed");
        } finally {
            port = null;
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
                    // Safe drop if hardware connection isn't currently Active/Started
                    if (port == null) return;
                    try {
                        port.write(byteData, timeOut);
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing to USB port from WebSocket", e);
                        if (logging != null) logging.log(TAG + " Error writing to USB port from WebSocket: " + e);
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



        webSocketHandler.setupConnection(KEY_data_api_path+getTunnelName(), prefs.getString(KEY_Session_KEY, EMPTY));
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
                    if (len > 0 && webSocketHandler != null) {
                        webSocketHandler.sendBytes(Arrays.copyOf(buffer, len));
                    }
                } catch (IOException e) {
                    if (logging != null) logging.log(TAG + " Error reading from USB port: " + e);
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