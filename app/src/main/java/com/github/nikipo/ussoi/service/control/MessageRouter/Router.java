package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.isHighSpeedSupported;
import static com.github.nikipo.ussoi.media.utility.CameraHelper.buildAllCamerasJson;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Device_Id;
import static com.github.nikipo.ussoi.ui.UssoiStrings.*;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.system.deviceInfo.DeviceInfoDynamic;
import com.github.nikipo.ussoi.system.deviceInfo.DeviceInfoStatic;

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
public class Router{
    private static final String TAG = "Router";
    private static Context context;
    private StreamRoute streamRoute;
    private TunnelRoute tunnelRoute;
    private ConnectionManager connectionManager;
    private static Logging logger;
    private DeviceInfoDynamic deviceInfoDynamic;
    private DeviceInfoStatic deviceInfoStatic;

    // global state variables
    private boolean is_params_set;
    private final boolean high_fps_support;
    private StreamMode streamMode = StreamMode.None;
    private String deviceId;
    private JSONObject camRes;

    public Router(ConnectionManager sender, Context context) {
        Router.context = context;
        connectionManager = sender;
        deviceInfoDynamic = new DeviceInfoDynamic(context);
        deviceInfoStatic = new DeviceInfoStatic(context);

        try {
            camRes = buildAllCamerasJson((CameraManager) context.getSystemService(Context.CAMERA_SERVICE));
        }
        catch (Exception e){
            sendError(connectionManager,CMD_ID,CMD,"Device Failed To build Camera Resolutions");
            e.printStackTrace();
            // TODO log
        }

        logger = Logging.getInstance(Router.context);

        deviceId = SaveInputFields.getInstance(Router.context).get_shared_pref().getString(KEY_Device_Id, EMPTY);

        is_params_set = false;
        high_fps_support = hasHighFpsCamera();

        tunnelRoute = new TunnelRoute(sender, this, Router.context);
        streamRoute = new StreamRoute(connectionManager, this, Router.context, StreamMode.None);
    }


    public void route(JSONObject json) {
        Log.d(TAG,json.toString());
        String cmd   = json.optString(CMD, EMPTY);
        String cmdId = json.optString(CMD_ID, EMPTY);

        switch (cmd) {
            case GET_PARAMS:
                sendParams();
                break;

            case SET_PARAMS:
                setParams(json);
                break;

            case GET_RES:
                sendResponse(connectionManager,CMD_ID,GET_RES,camRes);
                break;

            case GET_TUNNELS:
                sendResponse(connectionManager,CMD_ID,GET_TUNNELS,tunnelRoute.getTunnels());
                break;

            case START_TUNNEL:
            case STOP_TUNNEL:
                tunnelRoute.route(json);
                break;

            case DEVICE_INFO:
                sendResponse(connectionManager,cmdId,cmd, deviceInfoDynamic.buildJsonPacket());
                break;

            case DEVICE_IDENTITY:
                sendResponse(connectionManager,cmdId,cmd, deviceInfoStatic.getAll());
                break;

            //Stop commands no paramsSet guard
            case STOP_STREAM:
            case STOP_RECORDING:
                streamRoute.route(json);
                break;

            case START_RECORDING:
            case START_STREAM:
            case PLAY:
            case PAUSE:
            case MUTE:
            case FLIP:
            case ROTATE:
            case SWITCH:
            case SET_RECORD_RES:
            case SET_STREAM_RES:
            case WEBRTC_ICE:
            case WEBRTC_SDP:
                if (!is_params_set) {
                    sendError(connectionManager, cmdId, cmd, "Params not set");
                    return;
                }
                streamRoute.route(json);
                break;

            default:
                sendError(connectionManager, cmdId, cmd, "Unknown command");
                break;
        }
    }

    private void sendParams() {
        try {
            JSONObject data = new JSONObject();
            data.put(HF_SUPPORT, high_fps_support);
            data.put(STREAM_MODE, streamMode.name());
            data.put(PARAMS_SET, is_params_set);

            sendResponse(connectionManager,CMD_ID,GET_PARAMS,data);

        } catch (Exception e) {
            e.printStackTrace();
            sendError(connectionManager, CMD_ID, GET_PARAMS, "Internal error building params");
        }
    }

    private void setParams(JSONObject json) {
        String cmdId = json.optString(CMD_ID, EMPTY);
        // Todo Implement this
        String telem_rate = json.optString(TELEMETRY_RATE, EMPTY);

        JSONObject param = json.optJSONObject("param");
        StreamMode mode = null;

        if (param != null) {
            mode = StreamMode.fromString(
                    param.optString(STREAM_MODE, EMPTY)
            );
        }

        if (mode == null) {
            sendError(connectionManager, cmdId, SET_PARAMS, "Invalid Stream Mode");
            return;
        }

        if (mode == StreamMode.HFH264 && !high_fps_support) {
            sendError(connectionManager, cmdId, SET_PARAMS, "High FPS not supported on this device");
            return;
        }

        streamMode = mode;

        // Stop any running stream before switching mode
        if (streamRoute != null) {
            if (streamRoute.isRecording() || streamRoute.isStreaming()) {
                String reason = streamRoute.isRecording()? "Recording Active Can't Switch": "Stream Active Can't Switch";
                sendError(connectionManager, cmdId, SET_PARAMS, reason);
                return;
            }
            streamRoute.closeStream();
        }

        streamRoute = new StreamRoute(connectionManager, this, context, streamMode);
        is_params_set = true;

        sendResponse(connectionManager,cmdId,SET_PARAMS ,null);

        // make sure parameters are updated on user side
        sendParams();
    }

    private static boolean hasHighFpsCamera() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return false;

            for (String cameraId : manager.getCameraIdList()) {
                if (isHighSpeedSupported(context,cameraId)) {
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


    void sendResponse(ConnectionManager cm, String cmdId, String cmd, JSONObject data) {
        try {
            JSONObject res = new JSONObject();
            res.put(TYPE, RESPONSE);
            res.put(CMD, cmd);
            res.put(CMD_ID, cmdId);
            res.put(STATUS, STATUS_OK);
            if (data != null){
                res.put(DATA, data);
            }
            cm.send(res);
        } catch (Exception e) {
            e.printStackTrace();
            // TODO LOG
        }
    }

    void sendError(ConnectionManager cm, String cmdId, String cmd, String error) {
        try {
            JSONObject res = new JSONObject();
            res.put(TYPE,  ERROR);
            res.put(CMD,   cmd);
            res.put(CMD_ID, cmdId);
            res.put(ERROR, error);
            cm.send(res);
        } catch (Exception e) {
            e.printStackTrace();
            // TODO LOG
        }
    }


    public void stop() {
        if (tunnelRoute != null) tunnelRoute.stopTunnel();
        if (streamRoute != null) streamRoute.closeStream();
    }

    public char getTunnelAndStreamStatus() {
        int status = 0;

        boolean tunnelRunning =
                tunnelRoute != null && tunnelRoute.isTunnelRunning();

        boolean streaming =
                streamRoute != null && streamRoute.isStreaming();

        boolean recording =
                streamRoute != null && streamRoute.isRecording();

        if (tunnelRunning) status |= 1;
        if (streaming)     status |= 1 << 1;
        if (recording)     status |= 1 << 2;

        return "0123456789ABCDEF".charAt(status & 0x0F);
    }
}
