package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Device_Id;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import com.github.nikipo.ussoi.media.camera.HighFPSCameraController;
import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.system.deviceInfo.DeviceInfoDynamic;
import com.github.nikipo.ussoi.system.deviceInfo.DeviceInfoStatic;

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
public class Router{
    private static final String TAG = "Router";
    private static Context ctx;
    private StreamRoute streamRoute;
    private TunnelRoute tunnelRoute;
    private ConnectionManager connectionManager;
    private static Logging logger;
    private DeviceInfoDynamic deviceInfoDynamic;
    private DeviceInfoStatic deviceInfoStatic;

    // global state variables
    private boolean is_params_set;
    private boolean high_fps_support;
    private StreamMode stream_type;
    private String deviceId;

    public Router(ConnectionManager sender, Context context) {
        this.ctx = context;
        connectionManager = sender;
        deviceInfoDynamic = new DeviceInfoDynamic(context);
        deviceInfoStatic = new DeviceInfoStatic(context);
        logger = Logging.getInstance(ctx);

        deviceId = SaveInputFields.getInstance(ctx).get_shared_pref().getString(KEY_Device_Id, "123");
        is_params_set = false;
        high_fps_support = hasHighFpsCamera();
        tunnelRoute = new TunnelRoute(sender, this, ctx);
        streamRoute = new StreamRoute(connectionManager, this, ctx, StreamMode.None);
    }

    /**
     * Main entry point. Routes incoming server commands to the correct handler
     * based on the "cmd" field. All messages must carry a matching "deviceId".
     *
     * Expected incoming format:
     * {
     *   "cmd":      "<command_name>",
     *   "cmdId":    "<uuid>",
     *   "deviceId": "<device_id>",
     *   ... optional extra fields per command ...
     * }
     */
    public void route(JSONObject json) {
        Log.d(TAG,json.toString());
        String incomingDeviceId = json.optString("deviceId", "");
        String cmd   = json.optString("cmd", "");
        String cmdId = json.optString("cmdId", "");

//        // ── Device-ID guard ───────────────────────────────────────────────────
//        if (!incomingDeviceId.equals(this.deviceId)) {
//            sendNack(connectionManager, cmdId, cmd, "Invalid deviceId");
//            return;
//        }

        switch (cmd) {

            // ── Param commands ────────────────────────────────────────────────
            case "get_params":
                sendParams(json);
                break;

            case "set_params":
                setParams(json);
                break;

            // ── Tunnel commands ───────────────────────────────────────────────
            case "start_tunnel":
            case "stop_tunnel":
            case "get_tunnels":
                tunnelRoute.route(json);
                break;

            // ── Device-info commands (server-initiated queries) ────────────────
            case "stats":
            case "identity":
                handleDeviceInfo(json);
                break;

            // ── Stop commands (no paramsSet guard — safe to always handle) ─────
            case "stop_stream":
            case "stop_recording":
                streamRoute.route(json);
                break;

            // ── Media / stream commands (require paramsSet) ───────────────────
            case "start_stream":
            case "start_recording":
            case "play":
            case "pause":
            case "mute":
            case "flip":
            case "rotate":
            case "switch":
            case "set_stream_res":
            case "get_stream_res":
            case "set_record_res":
            case "get_record_res":
            case "get_res":
            case "webrtc_offer":
            case "webrtc_ice":
                if (!is_params_set) {
                    sendNack(connectionManager, cmdId, cmd, "Params not set");
                    return;
                }
                streamRoute.route(json);
                break;

            default:
                sendNack(connectionManager, cmdId, cmd, "Unknown command");
                break;
        }
    }

// ─── Param handlers ───────────────────────────────────────────────────────

    /**
     * Responds to get_params with current device capabilities and state.
     *
     * Sends:
     * { "type":"ack", "cmd":"get_params", "cmdId":"...",
     *   "params": { "high_fps_support":bool, "stream_type":"...", "is_params_set":bool } }
     */
    private void sendParams(JSONObject node) {
        String cmdId = node.optString("cmdId", "");
        try {
            JSONObject params = new JSONObject();
            params.put("high_fps_support", high_fps_support);
            params.put("stream_type", stream_type != null ? stream_type.name() : StreamMode.None.name());
            params.put("is_params_set", is_params_set);

            JSONObject res = new JSONObject();
            res.put("type",   "ack");
            res.put("cmd",    "get_params");
            res.put("cmdId",  cmdId);
            res.put("params", params);

            connectionManager.send(res);
        } catch (JSONException e) {
            logger.log(TAG + ": sendParams error " + e);
            Log.e(TAG, "sendParams error", e);
            sendNack(connectionManager, cmdId, "get_params", "Internal error building params");
        }
    }

    /**
     * Applies the requested stream mode. Resets and recreates StreamRoute on change.
     *
     * Expected incoming fields: stream_type (string: "webrtc"|"h264"|"hfh264"|"none")
     */
    private void setParams(JSONObject json) {
        String cmdId = json.optString("cmdId", "");

        StreamMode mode = StreamMode.fromString(json.optString("stream_type", null));

        if (mode == null) {
            sendNack(connectionManager, cmdId, "set_params", "Unknown / invalid stream_type");
            return;
        }

        if (mode == StreamMode.HFH264 && !high_fps_support) {
            sendNack(connectionManager, cmdId, "set_params", "High FPS not supported on this device");
            return;
        }

        stream_type = mode;

        // Stop any running stream before switching mode
        if (streamRoute != null) {
            streamRoute.stopStream();
        }
        streamRoute = new StreamRoute(connectionManager, this, ctx, stream_type);
        is_params_set = true;

        sendAck(connectionManager, cmdId, "set_params");
    }

// ─── Device-info handler ──────────────────────────────────────────────────

    /**
     * Handles server-initiated device info queries.
     *
     * cmd="stats"    → dynamic info (CPU, mem, battery, …)
     * cmd="identity" → static info (model, OS version, …)
     *
     * Sends:
     * { "type":"ack", "cmd":"stats"|"identity", "cmdId":"...", "params":{...} }
     */
    private void handleDeviceInfo(JSONObject json) {
        String cmd   = json.optString("cmd", "");
        String cmdId = json.optString("cmdId", "");
        try {
            JSONObject res = new JSONObject();
            res.put("type",  "ack");
            res.put("cmd",   cmd);
            res.put("cmdId", cmdId);

            if (cmd.equals("stats")) {
                JSONObject params = new JSONObject();
                params.put("packet", deviceInfoDynamic.getPacket());
                res.put("params", params);
            } else if (cmd.equals("identity")) {
                res.put("params", deviceInfoStatic.getAll());
            }

            connectionManager.send(res);
        } catch (JSONException e) {
            sendNack(connectionManager, cmdId, cmd, "JSON error building device info");
        }
    }

// ─── Camera capability ────────────────────────────────────────────────────

    private static boolean hasHighFpsCamera() {
        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return false;

            for (String cameraId : manager.getCameraIdList()) {
                Object caps = HighFPSCameraController.getHighSpeedCapabilities(ctx, cameraId);
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

// ─── ACK / NACK helpers (package-visible for sub-routers) ─────────────────

    /**
     * Sends a success acknowledgement.
     *
     * Format: { "type":"ack", "cmd":"<cmd>", "cmdId":"<cmdId>" }
     */
    void sendAck(ConnectionManager cm, String cmdId, String cmd) {
        try {
            JSONObject res = new JSONObject();
            res.put("type",  "ack");
            res.put("cmd",   cmd);
            res.put("cmdId", cmdId);
            cm.send(res);
        } catch (Exception ignored) {}
    }

    /**
     * Sends a failure acknowledgement.
     *
     * Format: { "type":"nack", "cmd":"<cmd>", "cmdId":"<cmdId>", "error":"<reason>" }
     */
    void sendNack(ConnectionManager cm, String cmdId, String cmd, String error) {
        try {
            JSONObject res = new JSONObject();
            res.put("type",  "nack");
            res.put("cmd",   cmd);
            res.put("cmdId", cmdId);
            res.put("error", error);
            cm.send(res);
        } catch (Exception ignored) {}
    }

// ─── Lifecycle ────────────────────────────────────────────────────────────

    public void stop() {
        if (tunnelRoute != null) tunnelRoute.stopTunnel();
        if (streamRoute != null) streamRoute.stopStream();
    }

// ─── Telemetry status nibble ──────────────────────────────────────────────

    /**
     * Returns a single hex char encoding live status bits:
     *   bit 0 → any tunnel running
     *   bit 1 → stream active
     *   bit 2 → recording active
     */
    public char getStatusTelem() {
        int status = 0;
        if (tunnelRoute != null && tunnelRoute.isTunnelRunning()) status |= 1;
        if (streamRoute  != null && streamRoute.isStreaming())    status |= 1 << 1;
        if (streamRoute  != null && streamRoute.isRecording())    status |= 1 << 2;
        return "0123456789ABCDEF".charAt(status & 0x0F);
    }
}
