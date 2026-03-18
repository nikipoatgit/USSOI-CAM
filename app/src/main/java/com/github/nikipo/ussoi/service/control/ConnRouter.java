package com.github.nikipo.ussoi.service.control;

import static com.github.nikipo.ussoi.storage.SaveInputFields.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;

import com.github.nikipo.ussoi.media.highFpsH264.HighFPSCameraController;
import com.github.nikipo.ussoi.system.DeviceInfo;
import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.tunnel.BluetoothHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.tunnel.UsbHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ConnRouter {
    private static final String TAG = "ControlMessageRouter";

    // Static state variables
    private static boolean isParamsReceived;
    private static ConnectionManager connManager;
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
    public static void init(ConnectionManager sender, Context context) {
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
        if (deviceInfo != null) {
            deviceInfo.stopAllServices();
        }
    }

    public static void route(JSONObject json) {
        String serviceType = json.optString("type", "");
        switch (serviceType) {
            case "stopAll":
//              stopAllServices();
                sendAck("nack", json.optInt("reqId", -1), "Feature(All systems halt) Disabled.");
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
                    logger.log(TAG + " config:" + Log.getStackTraceString(e));
                }
                break;

            case "getClientConfig":
                connManager.send(getUserParams());
                sendAck("ack", json.optInt("reqId", -1), "");
                break;

            case "getCamRes":
                if (isParamsReceived && hasHighFpsCamera() && prefs.getBoolean(KEY_mse_high_fps_Enable,false)){
                    connManager.send(getHighSpeedVideoResolutionsJson());
                }
                else{
                    connManager.send(getNormalVideoResolutionsJson());
                }
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
            case "getBtDevices":
                // TODO : SERVER SIDE IMPLEMENTATION REMAINING
                connManager.send(btDevices);
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
            if (prefs.getBoolean(KEY_mse_high_fps_Enable, false) && json.optBoolean("mse_high_fps", false)){
                if (json.has("start")) {
                    StreamRoute.stopStream();
                    if (json.optBoolean("start", false)) {
                        StreamRoute.initMseHighFps();
                        sendAck("ack", reqId, "");
                    } else {
                        sendAck("ack", reqId, "");
                    }
                } else {
                    StreamRoute.mseHighFpsControl(json, prefs);
                    sendAck("ack", json.optInt("reqId", -1), "Mse High Fps");
                }
            } else if (!prefs.getBoolean(KEY_mse_high_fps_Enable, false) && !json.optBoolean("mse_high_fps", false)) {
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
                    sendAck("ack", json.optInt("reqId", -1), "Mse");
                }
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
            } else if (bluetoothHandler != null) {
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
        boolean mse_high_fps = json.optBoolean("mse_high_fps", false);
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
                .putBoolean(KEY_mse_high_fps_Enable,mse_high_fps)
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
            bluetoothHandler = new BluetoothHandler(ctx);
            if (!selectedBtDevices.isEmpty()) {
                bluetoothHandler.setDevice(selectedBtDevices.get(0));
                bluetoothHandler.setupConnection();
            }
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
            reply.put("webrtc", prefs.getBoolean(KEY_webrtc_Enable, false));
            reply.put("mse", prefs.getBoolean(KEY_mse_Enable, false));
            reply.put("mse_high_fps", prefs.getBoolean(KEY_mse_high_fps_Enable,false));
            reply.put("local", prefs.getBoolean(KEY_local_recording, false));
            reply.put("version", USSOI_version);
            reply.put("isParamsReceived", isParamsReceived);
            reply.put("record", record);
            reply.put("stream", stream);
            reply.put("HighFpsSupport", hasHighFpsCamera());
            // TODO : SERVER SIDE IMPLEMENTATION REMAINING
        } catch (JSONException e) {
            logger.log(TAG + " getUserParams: " + Log.getStackTraceString(e));
        }
        return reply;
    }

    private static JSONObject getHighSpeedVideoResolutionsJson() {
        JSONObject root = new JSONObject();
        JSONArray cameraArray = new JSONArray();

        try {
            CameraManager manager =
                    (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return root;

            for (String cameraId : manager.getCameraIdList()) {

                CameraCharacteristics chars =
                        manager.getCameraCharacteristics(cameraId);

                StreamConfigurationMap map =
                        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;

                JSONObject camObj = new JSONObject();
                camObj.put("cameraId", cameraId);

                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                camObj.put("facing",
                        facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back");

                JSONArray resArr = getJsonArray(map);

                camObj.put("resolutions", resArr);
                cameraArray.put(camObj);
            }

            root.put("type", "camRes");
            root.put("resType", "HighFpsRes");
            root.put("cameraResolutions", cameraArray);

        } catch (Exception ignored) {}

        return root;
    }

    @NonNull
    private static JSONArray getJsonArray(StreamConfigurationMap map) throws JSONException {
        JSONArray resArr = new JSONArray();

        for (Size size : map.getHighSpeedVideoSizes()) {
            JSONObject res = new JSONObject();
            res.put("width", size.getWidth());
            res.put("height", size.getHeight());

            JSONArray fpsArr = new JSONArray();
            for (Range<Integer> r :
                    map.getHighSpeedVideoFpsRangesFor(size)) {

                JSONObject fps = new JSONObject();
                fps.put("min", r.getLower());
                fps.put("max", r.getUpper());
                fpsArr.put(fps);
            }

            res.put("fpsRanges", fpsArr);
            resArr.put(res);
        }
        return resArr;
    }

    private static JSONObject getNormalVideoResolutionsJson() {
        JSONObject root = new JSONObject();
        JSONArray cameraArray = new JSONArray();

        try {
            CameraManager manager =
                    (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return root;

            for (String cameraId : manager.getCameraIdList()) {

                CameraCharacteristics chars =
                        manager.getCameraCharacteristics(cameraId);

                StreamConfigurationMap map =
                        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;

                JSONObject camObj = new JSONObject();
                camObj.put("cameraId", cameraId);

                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                camObj.put("facing",
                        facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back");

                JSONArray resArr = new JSONArray();

                Range<Integer>[] fpsRanges =
                        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

                for (Size size : map.getOutputSizes(MediaRecorder.class)) {
                    JSONObject res = getJsonObject(size, fpsRanges);
                    resArr.put(res);
                }

                camObj.put("resolutions", resArr);
                cameraArray.put(camObj);
            }

            root.put("type", "camRes");
            root.put("resType", "NormalRes");
            root.put("cameraResolutions", cameraArray);

        } catch (Exception ignored) {}

        return root;
    }

    @NonNull
    private static JSONObject getJsonObject(Size size, Range<Integer>[] fpsRanges) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("width", size.getWidth());
        res.put("height", size.getHeight());

        JSONArray fpsArr = new JSONArray();
        if (fpsRanges != null) {
            for (Range<Integer> r : fpsRanges) {
                JSONObject fps = new JSONObject();
                fps.put("min", r.getLower());
                fps.put("max", r.getUpper());
                fpsArr.put(fps);
            }
        }

        res.put("fpsRanges", fpsArr);
        return res;
    }

    private static boolean hasHighFpsCamera() {
        try {
            CameraManager manager =
                    (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                return false;
            }

            for (String cameraId : manager.getCameraIdList()) {

                Object caps =
                        HighFPSCameraController.getHighSpeedCapabilities(ctx, cameraId);

                if (caps != null) {
                    Log.d(TAG, "High-FPS camera found: " + cameraId);
                    return true;
                }
            }

        } catch (Exception e) {
            logger.log(TAG + ": Error querying cameras " + e);
            Log.e(TAG, "Error querying cameras", e);
        }

        return false;
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