package com.github.nikipo.ussoi.ServicesManager;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;

import com.github.nikipo.ussoi.MacroServices.DeviceInfo;
import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.Tunnel.BluetoothHandler;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.github.nikipo.ussoi.Tunnel.UsbHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ConnRouter {
    private static final String TAG = "ControlMessageRouter";

    // Static state variables
    private static boolean isParamsReceived ;
    private static ConnManager connManager;
    private static SaveInputFields saveInputFields;
    private static SharedPreferences prefs;
    private static Context ctx;
    private static UsbHandler usbHandler;
    private static BluetoothHandler bluetoothHandler;
    private static Logging logger;
    private static DeviceInfo deviceInfo;

    /**
     * Replaces the constructor. Call this once in your main activity or service setup.
     */
    public static void init(ConnManager sender, Context context) {
        isParamsReceived = false;
        ctx = context;
        connManager = sender;
        deviceInfo = DeviceInfo.getInstance(context);
        logger = Logging.getInstance(ctx);
        saveInputFields = SaveInputFields.getInstance(ctx);
        StreamRoute.init(ctx);
        prefs = saveInputFields.get_shared_pref();
    }

    public static void stopAllServices() {
        isParamsReceived = false;

        StreamRoute.stopStream();
        if (usbHandler != null) {
            usbHandler.stopAllServices();
            usbHandler = null;
            Log.d(TAG, "usbHandler Stopped");
        }
        if (bluetoothHandler != null) {
            bluetoothHandler.stopAllServices();
            bluetoothHandler = null;
            Log.d(TAG, "Bthandler Stopped");
        }
        if(deviceInfo != null){
            deviceInfo.stopAllServices();
        }
    }

    public static void route(JSONObject json) {
        String serviceType = json.optString("type", "");
        logger.log(TAG + " serviceType :" + serviceType);
        switch (serviceType) {
            case "stopAll":
//              stopAllServices();
                sendAck("nack", json.optInt("reqId", -1), "Feature Disabled(All systems halted).");
                break;

            case "stream":
                if (isParamsReceived) {
                    handleStreamLogic(json);
                } else {
                    sendAck("nack", json.optInt("reqId", -1), "Stream Params Not Sync");
                }
                break;

            case "UARTTunnel":
                if (isParamsReceived) {
                    handelUartTunnel(json);
                }
                break;

            case "config":
                try {
                    setUpConfigParams(json);
                } catch (JSONException e) {
                    logger.log(TAG + " " + Log.getStackTraceString(e));
                }
                break;

            case "getClientConfig":
                connManager.send(getUserParams());
                sendAck("ack", json.optInt("reqId", -1), "");
                break;

            case "getCamRes":
                logCameraResolutionsAsJson();
                sendAck("ack", json.optInt("reqId", -1), "");
                break;
            case "deviceInfo":

                JSONObject reply = new JSONObject();
                try {
                    reply.put("type", "deviceInfo");
                    reply.put("info", deviceInfo.getAllDetailsAsJson());
                } catch (JSONException e) {
                    logger.log(TAG + " " + Log.getStackTraceString(e));
                }
                connManager.send(reply);
                sendAck("ack", json.optInt("reqId", -1), "");

                break;
            default:
                sendAck("nack", json.optInt("reqId", -1), "Params Invalid");
                break;
        }
    }

    private static void handleStreamLogic(JSONObject json) {
        int reqId = json.optInt("reqId", -1);
        if (prefs.getBoolean(KEY_webrtc_Enable, false) && json.optBoolean("webrtc", false)) {
            if (json.has("start")) {
                StreamRoute.stopStream();
                if (json.optBoolean("start", false)) {
                    StreamRoute.initWebrtc(connManager.getWebSocketHandlerObject());
                    sendAck("ack", reqId, "");
                } else {
                    sendAck("ack", reqId, "");
                }
            } else {
                    StreamRoute.webrtcControl(json, prefs);
                    sendAck("ack", json.optInt("reqId", -1), "");
            }
        } else if (prefs.getBoolean(KEY_mse_Enable, false) && json.optBoolean("mse", false)) {
            if (json.has("start")) {
                StreamRoute.stopStream();
                if (json.optBoolean("start", false)) {
                    StreamRoute.initMse();
                    sendAck("ack", reqId, "");
                } else {
                    sendAck("ack", reqId, "");
                }
            } else {
                    StreamRoute.mseControl(json, prefs);
                    sendAck("ack", json.optInt("reqId", -1), "");
            }
        }
    }

    public static char getClientStat() {
        boolean tunnel = false;
        if (usbHandler != null) tunnel = usbHandler.isRunning();
        if (bluetoothHandler != null) tunnel |= bluetoothHandler.isRunning();

        boolean stream = StreamRoute.isStreamRunning();
        boolean record = StreamRoute.isRecording();

        int statusBits = 0;
        if (tunnel) statusBits |= (1);
        if (stream) statusBits |= (1 << 1);
        if (record) statusBits |= (1 << 2);

        return Character.toUpperCase(Character.forDigit(statusBits, 16));
    }

    private static void handelUartTunnel(JSONObject json) {
        boolean tunnelStatus = json.optBoolean("tunnelStatus", false);

        if (usbHandler != null && !tunnelStatus) {
            usbHandler.stopAllServices();
            usbHandler = null;
        } else if (bluetoothHandler != null && !tunnelStatus) {
            bluetoothHandler.stopAllServices();
            bluetoothHandler = null;
        }

        if (tunnelStatus) {
            if (usbHandler != null) {
                usbHandler.stopAllServices();
                usbHandler = null;
            } else if (bluetoothHandler != null ) {
                bluetoothHandler.stopAllServices();
                bluetoothHandler = null;
            }
            initUartTunnel();
        }
        sendAck("ack", json.optInt("reqId", -1), "");
    }

    private static void setUpConfigParams(JSONObject json) throws JSONException {

        boolean localRecord = json.optBoolean("local", false);
        boolean webrtc = json.optBoolean("webrtc", false);
        boolean mse = json.optBoolean("mse", false);
        int baudrate = json.optInt("baudrate", 115200);
        int bitrateKbps = json.optInt("bitrate", 500);
        JSONArray turnServer = json.getJSONArray("turn");


        // Baudrate validation
        if (baudrate < 1200 || baudrate > 3_000_000) {
            sendAck("nack", json.optInt("reqId", -1), "baudrate Out of Bound");
            return;
        }
        // Bitrate validation (expects kbps)
        if (localRecord && bitrateKbps < 0) {
            sendAck("nack", json.optInt("reqId", -1), "bitrate Out of Bound");
            return;
        }



        prefs.edit()
                .putBoolean(KEY_webrtc_Enable, webrtc)
                .putBoolean(KEY_mse_Enable, mse)
                .putInt(KEY_BAUD_RATE, baudrate)
                .putInt(KEY_LocalVideoBitrate, bitrateKbps)
                .putBoolean(KEY_local_recording, localRecord)
                .putString(KEY_turn_array, turnServer.toString())
                .apply();

        StreamRoute.stopStream();
        isParamsReceived = true;
        sendAck("ack", json.optInt("reqId", -1), "");
    }

    private static void initUartTunnel() {
        if (prefs.getBoolean(KEY_BT_SWITCH, false) && !prefs.getBoolean(KEY_USB_Switch, false)) {
            bluetoothHandler = BluetoothHandler.getInstance(ctx);
            bluetoothHandler.setupConnection();
        } else if (!prefs.getBoolean(KEY_BT_SWITCH, false) && prefs.getBoolean(KEY_USB_Switch, false)) {
            usbHandler = UsbHandler.getInstance(ctx);
            usbHandler.setupConnection();
        }
    }

    private static JSONObject getUserParams() {
        String status = "null";
        boolean bt = prefs.getBoolean(KEY_BT_SWITCH, false);
        boolean usb = prefs.getBoolean(KEY_USB_Switch, false);

        if (bt && usb) status = "none";
        else if (bt) status = "bt";
        else if (usb) status = "usb";

        boolean record = StreamRoute.isRecording();
        boolean stream = StreamRoute.isStreamRunning();

        JSONObject reply = new JSONObject();
        try {
            reply.put("type", "config");
            reply.put("tunnelMode", status);
            reply.put("webrtc",prefs.getBoolean(KEY_webrtc_Enable,false));
            reply.put("mse",prefs.getBoolean(KEY_mse_Enable,false));
            reply.put("local",prefs.getBoolean(KEY_local_recording,false));
            reply.put("version",USSOI_version);
            reply.put("isParamsReceived", isParamsReceived);
            reply.put("record",record);
            reply.put("stream", stream);
        } catch (JSONException e) {
            logger.log(TAG + " " + Log.getStackTraceString(e));
        }
        return reply;
    }

    private static void logCameraResolutionsAsJson() {
        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return;

        JSONObject root = new JSONObject();
        JSONArray cameraArray = new JSONArray();

        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;

                JSONObject camObj = new JSONObject();
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                camObj.put("facing", facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back");

                JSONArray resArr = new JSONArray();
                for (Size size : map.getOutputSizes(ImageFormat.JPEG)) {
                    JSONObject res = new JSONObject();
                    res.put("width", size.getWidth());
                    res.put("height", size.getHeight());
                    resArr.put(res);
                }
                camObj.put("resolutions", resArr);
                cameraArray.put(camObj);
            }
            root.put("cameraResolutions", cameraArray);
            root.put("type", "camRes");
            connManager.send(root);
        } catch (Exception e) {
            logger.log(TAG + " " + Log.getStackTraceString(e));
        }
    }

    public static void sendAck(String type, int reqId, String msg) {
        try {
            JSONObject res = new JSONObject();
            res.put("type", type);
            res.put("reqId", reqId);
            if (msg != null) res.put("msg", msg);
            if (connManager != null) connManager.send(res);
        } catch (JSONException e) {
            Log.e(TAG, "Ack error", e);
            logger.log(TAG + " Ack error :" + Log.getStackTraceString(e));
        }
    }
}