package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Device_Id;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import com.github.nikipo.ussoi.media.camera.HighFPSCameraController;
import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.system.device.DeviceInfo;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file Router
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
public class Router {
    private static final String TAG = "Router";
    private static Context ctx;
    private StreamRoute streamRoute;
    private TunnelRoute tunnelRoute;
    private ConnectionManager connectionManager;
    private static Logging logger;
    private DeviceInfo deviceInfo;

    // global state variables
    private boolean is_params_set;
    private boolean high_fps_support;
    private boolean is_streaming;
    private boolean is_recording;
    private StreamMode stream_type;
    private String deviceId;

    public void init(ConnectionManager sender, Context context) {
        this.ctx = context;
        connectionManager = sender;
        deviceInfo = DeviceInfo.getInstance(context);
        logger = Logging.getInstance(ctx);

        deviceId = SaveInputFields.getInstance(ctx).get_shared_pref().getString(KEY_Device_Id,"123");
        is_params_set = false;
        high_fps_support = hasHighFpsCamera();
        is_streaming = false;
        is_recording = false;
        tunnelRoute = new TunnelRoute(sender,this,ctx);
    }

    public void route(JSONObject json) {
        String deviceId  = json.optString("deviceId", "");

        if (!deviceId.equals(this.deviceId)) {
            sendNack(connectionManager,json.optString("cmdId", ""),"deviceId Invalid");
            return;
        }

        String Type = json.optString("type", "");

        switch (Type) {

            case "media":
                if (is_params_set && streamRoute != null) {
                    streamRoute.route(connectionManager, json);
                }
                else {
                    sendNack(connectionManager,json.optString("cmdId", ""),"Params Not Set");

                }
                break;

            case "param":
                String cmd = json.optString("cmd", "");
                if (cmd.equals("get_params")) {
                    sendParams(json);
                }
                else if (cmd.equals("set_params")){
                    setParams(json);
                }

                break;

            case "tunnel":
                tunnelRoute.route(json);
                break;

            case "info":
                connectionManager.send(deviceInfo.getAllDetailsAsJson());
                break;

            default:
                break;
        }
    }

    private void sendParams(JSONObject node) {
        try {
            String cmdId = node.optString("cmdId", "");

            JSONObject payload = new JSONObject();
            payload.put("high_fps_support", high_fps_support);
            payload.put("is_streaming", is_streaming);
            payload.put("is_recording", is_recording);
            payload.put("stream_type", stream_type);
            payload.put("is_params_set", is_params_set);

            JSONObject res = new JSONObject();
            res.put("type", "data");
            res.put("cmd", "params");
            res.put("cmdId", cmdId);
            res.put("timestamp", System.currentTimeMillis());
            res.put("payload", payload);

            connectionManager.send(res);

        } catch (JSONException e) {
            logger.log(TAG + ": sendParams error " + e);
            Log.e(TAG, "sendParams error", e);
        }
    }
    private void setParams(JSONObject json){

        // get Stream mode form params
        StreamMode mode = StreamMode.fromString(json.optString("stream_type", null));

        if (mode == null) {
            sendNack(connectionManager,json.optString("cmdId", ""),"Mode Unknown / invalid");
            return;
        }

        if ( mode ==  StreamMode.HFH264 && !high_fps_support){
            sendNack(connectionManager,json.optString("cmdId", ""),"High Fps Not Supported");
            return;
        }

        stream_type = mode;

        // since params are being rest stop stream if active
        if (streamRoute == null)  {
            streamRoute.stopStream();
            streamRoute = null;
            streamRoute = new StreamRoute(connectionManager,this,ctx,mode);
        }
        streamRoute = null;
        is_params_set = true;
    }

    private static boolean hasHighFpsCamera() {
        try {
            CameraManager manager =
                    (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                return false;
            }

            for (String cameraId : manager.getCameraIdList()) {

                Object caps =HighFPSCameraController.getHighSpeedCapabilities(ctx, cameraId);

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

    void sendAck(ConnectionManager cm, String cmdId, String msg) {
        try {
            JSONObject res = new JSONObject();
            res.put("type", "ack");
            res.put("msg", msg);
            res.put("cmdId", cmdId);
            res.put("timestamp", System.currentTimeMillis());

            cm.send(res);
        } catch (Exception ignored) {}
    }

    void sendNack(ConnectionManager cm, String cmdId, String msg) {
        try {
            JSONObject res = new JSONObject();
            res.put("type", "nack");
            res.put("msg", msg);
            res.put("msg", msg);
            res.put("cmdId", cmdId);
            res.put("timestamp", System.currentTimeMillis());

            cm.send(res);
        } catch (Exception ignored) {}
    }
}
