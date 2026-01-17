package com.github.nikipo.ussoi.Tunnel;


import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_BAUD_RATE;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.UAR_TUNNEL;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.MacroServices.WebSocketHandler;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;



import java.io.IOException;


public class UsbHandler {
    private static final String TAG = "UsbHandler";
    private static UsbHandler instance;
    private SharedPreferences prefs;
    private SaveInputFields saveInputFields;
    private static UsbManager usbManager;
    private WebSocketHandler webSocketHandler;
    private Thread readThread;
    private volatile boolean reading = false;
    private final Object usbLock = new Object();
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
                    synchronized (usbLock) {
                        if (port != null) {
                            port.write(mavlinkBytes, timeOut);
                        }
                    }
                } catch (IOException e) {
               //     stopAllServices();
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
                    if (port == null) break;
                    int len = port.read(buffer, READ_WAIT_MILLIS);
                    if (len > 0) {
                        byte[] mavlinkBytes = java.util.Arrays.copyOf(buffer, len);
                        webSocketHandler.connSendPayloadBytes(mavlinkBytes);
                    }
                } catch (IOException e) {
                //    stopAllServices();
                    logging.log(TAG + " Error Reading to USB port from WebSocket"+ e);
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
    public void stopAllServices(){
        logging.log(TAG + " USB services Stopped");
        stopReading();

        // here closing order matters
        if (webSocketHandler != null ) {
            webSocketHandler.closeConnection();
            webSocketHandler = null;
            Log.d(TAG,"socket closed");

        }

            if (port != null) {
                try {
                    port.close();
                    Log.d(TAG,"port closed");
                } catch (IOException ignored) {}
                port = null;
            }


    }
}
