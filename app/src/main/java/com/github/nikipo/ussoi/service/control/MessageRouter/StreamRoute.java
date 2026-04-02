package com.github.nikipo.ussoi.service.control.MessageRouter;

import android.content.Context;

import com.github.nikipo.ussoi.media.CameraControl;
import com.github.nikipo.ussoi.media.HFH264.HighFpsH264Media;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.h264.H264Media;
import com.github.nikipo.ussoi.media.webrtc.WebRtcMedia;
import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.tunnel.Tunnel;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file StreamRoute
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
public class StreamRoute  {
    private static final String TAG = "StreamRoute";

    private final ConnectionManager connectionManager;
    private final Router router;
    private final Context ctx;
    StreamMode streamMode;
    private Media media;

    public StreamRoute(ConnectionManager connectionManager, Router router, Context ctx, StreamMode streamMode) {
        this.connectionManager = connectionManager;
        this.router = router;
        this.ctx = ctx;
        this.streamMode = streamMode;
        setStreamMode();
        media.init(ctx);
    }

    private void setStreamMode() {
        switch (streamMode) {
            case H264:
                media = new H264Media();
                break;
            case HFH264:
                media = new HighFpsH264Media();
                break;
            case WebRtc:
                media = new WebRtcMedia(connectionManager);
                break;
            case None:
            default:
                media = new Media() {
                    @Override public void init(Context ctx) {}
                    @Override public void stop() {}
                    @Override public short StartStream()                              { return 0; }
                    @Override public short SetStreamResolution(int w, int h, int f)  { return 0; }
                    @Override public short SetStreamBitrate(int b)                   { return 0; }
                    @Override public boolean IsStreaming()                            { return false; }
                    @Override public void StreamMute(boolean mute)                   {}
                    @Override public short StartRecording()                           { return 0; }
                    @Override public short SetRecordingResolution(int w, int h, int f){ return 0; }
                    @Override public short SetRecordingBitrate(int b)                { return 0; }
                    @Override public boolean IsRecording()                            { return false; }
                    @Override public void StopRecording()                             {}
                    @Override public short SwitchCamera()                             { return 0; }
                    @Override public short RotateCamera()                             { return 0; }
                    @Override public short FlipCamera()                               { return 0; }
                    @Override public JSONObject SupportedResolutions()                { return null; }
                };
                break;
        }
    }

    // ─── Public state queries ─────────────────────────────────────────────────

    public void stopStream()    { media.stop(); }
    public boolean isStreaming()  { return media.IsStreaming(); }
    public boolean isRecording()  { return media.IsRecording(); }

    // ─── Command dispatcher ───────────────────────────────────────────────────

    /**
     * Routes an incoming server command to the correct Media call.
     * Every case sends an ACK or NACK back to the server.
     *
     * Incoming format:
     * { "cmd":"<cmd>", "cmdId":"<uuid>", "deviceId":"...", ...extra fields... }
     *
     * ACK format  : { "type":"ack",  "cmd":"<cmd>", "cmdId":"<uuid>" }
     * NACK format : { "type":"nack", "cmd":"<cmd>", "cmdId":"<uuid>", "error":"<reason>" }
     * Data ACK    : { "type":"ack",  "cmd":"<cmd>", "cmdId":"<uuid>", "params":{...} }
     */
    public void route(JSONObject json) {
        String cmd   = json.optString("cmd", "");
        String cmdId = json.optString("cmdId", "");

        try {
            switch (cmd) {

                // ── Stream ────────────────────────────────────────────────────

                case "start_stream": {
                    short r = media.StartStream();
                    if (r == 0) router.sendAck(connectionManager, cmdId, cmd);
                    else        router.sendNack(connectionManager, cmdId, cmd, "Start stream failed: " + r);
                    break;
                }

                case "stop_stream": {
                    media.stop();
                    router.sendAck(connectionManager, cmdId, cmd);
                    break;
                }

                // ── Recording ─────────────────────────────────────────────────

                case "start_recording": {
                    short r = media.StartRecording();
                    if (r == 0) router.sendAck(connectionManager, cmdId, cmd);
                    else        router.sendNack(connectionManager, cmdId, cmd, "Start recording failed: " + r);
                    break;
                }

                case "stop_recording": {
                    media.StopRecording();
                    router.sendAck(connectionManager, cmdId, cmd);
                    break;
                }

                // ── Playback controls ─────────────────────────────────────────

                case "play": {
                    media.StreamMute(false);
                    router.sendAck(connectionManager, cmdId, cmd);
                    break;
                }

                case "pause": {
                    media.StreamMute(true);
                    router.sendAck(connectionManager, cmdId, cmd);
                    break;
                }

                case "mute": {
                    if (streamMode == StreamMode.WebRtc) {
                        ((WebRtcMedia) media).AudioMute();
                        router.sendAck(connectionManager, cmdId, cmd);
                    } else {
                        router.sendNack(connectionManager, cmdId, cmd, "Audio mute only supported in WebRTC mode");
                    }
                    break;
                }

                // ── Camera controls ───────────────────────────────────────────

                case "flip": {
                    short r = media.FlipCamera();
                    if (r == 0) router.sendAck(connectionManager, cmdId, cmd);
                    else        router.sendNack(connectionManager, cmdId, cmd, "Flip failed: " + r);
                    break;
                }

                case "rotate": {
                    short r = media.RotateCamera();
                    if (r == 0) router.sendAck(connectionManager, cmdId, cmd);
                    else        router.sendNack(connectionManager, cmdId, cmd, "Rotate failed: " + r);
                    break;
                }

                case "switch": {
                    short r = media.SwitchCamera();
                    if (r == 0) router.sendAck(connectionManager, cmdId, cmd);
                    else        router.sendNack(connectionManager, cmdId, cmd, "Camera switch failed: " + r);
                    break;
                }

                // ── Resolution / bitrate setters ──────────────────────────────

                case "set_stream_res": {
                    // FIX: was "params1" (typo) — corrected to "params"
                    JSONObject params = json.optJSONObject("params");
                    if (params == null) {
                        router.sendNack(connectionManager, cmdId, cmd, "Missing params object");
                        break;
                    }
                    boolean ok = true;
                    if (params.has("width")) {
                        int width  = params.optInt("width",  -1);
                        int height = params.optInt("height", -1);
                        int fps    = params.optInt("fps",    -1);
                        if (width > 0 && height > 0 && fps > 0) {
                            if (media.SetStreamResolution(width, height, fps) != 0) ok = false;
                        }
                    }
                    if (params.has("bitrate")) {
                        int bitrate = params.optInt("bitrate", -1);
                        if (bitrate > 0) {
                            if (media.SetStreamBitrate(bitrate) != 0) ok = false;
                        }
                    }
                    if (ok) router.sendAck(connectionManager, cmdId, cmd);
                    else    router.sendNack(connectionManager, cmdId, cmd, "Failed to apply stream resolution/bitrate");
                    break;
                }

                case "set_record_res": {
                    JSONObject params = json.optJSONObject("params");
                    if (params == null) {
                        router.sendNack(connectionManager, cmdId, cmd, "Missing params object");
                        break;
                    }
                    boolean ok = true;
                    if (params.has("width")) {
                        int width  = params.optInt("width",  -1);
                        int height = params.optInt("height", -1);
                        int fps    = params.optInt("fps",    -1);
                        if (width > 0 && height > 0 && fps > 0) {
                            if (media.SetRecordingResolution(width, height, fps) != 0) ok = false;
                        }
                    }
                    if (params.has("bitrate")) {
                        int bitrate = params.optInt("bitrate", -1);
                        if (bitrate > 0) {
                            if (media.SetRecordingBitrate(bitrate) != 0) ok = false;
                        }
                    }
                    if (ok) router.sendAck(connectionManager, cmdId, cmd);
                    else    router.sendNack(connectionManager, cmdId, cmd, "Failed to apply record resolution/bitrate");
                    break;
                }

                // ── Resolution getters (data responses) ───────────────────────

                case "get_res":
                case "get_stream_res":
                case "get_record_res": {
                    JSONObject resolutions = media.SupportedResolutions();
                    JSONObject res = new JSONObject();
                    res.put("type",  "ack");
                    res.put("cmd",   cmd);
                    res.put("cmdId", cmdId);
                    if (resolutions != null) res.put("params", resolutions);
                    connectionManager.send(res);
                    break;
                }

                // ── WebRTC ────────────────────────────────────────────────────

                case "webrtc_offer": {
                    if (streamMode != StreamMode.WebRtc) {
                        router.sendNack(connectionManager, cmdId, cmd, "Not in WebRTC mode");
                        break;
                    }
                    JSONObject params = json.optJSONObject("params");
                    if (params == null) {
                        router.sendNack(connectionManager, cmdId, cmd, "Missing SDP params");
                        break;
                    }
                    // Echo cmdId and userId into params so WebRtcMedia can include
                    // them in its async answer, allowing the server to route the
                    // SDP answer back to the correct user via sendToUser(userId).
                    params.put("cmdId",  cmdId);
                    params.put("userId", json.optString("userId", ""));
                    ((WebRtcMedia) media).setSdp(params);
                    // No immediate ACK — WebRtcMedia sends the answer asynchronously.
                    // WebRtcMedia must send:
                    // { "type":"ack","cmd":"webrtc_offer","cmdId":"...","userId":"...","sdp":"<answer>" }
                    break;
                }

                case "webrtc_ice": {
                    if (streamMode != StreamMode.WebRtc) {
                        router.sendNack(connectionManager, cmdId, cmd, "Not in WebRTC mode");
                        break;
                    }
                    JSONObject params = json.optJSONObject("params");
                    if (params == null) {
                        router.sendNack(connectionManager, cmdId, cmd, "Missing ICE params");
                        break;
                    }
                    params.put("cmdId",  cmdId);
                    params.put("userId", json.optString("userId", ""));
                    ((WebRtcMedia) media).SetIce(params);
                    // No immediate ACK — WebRtcMedia sends ICE candidates asynchronously.
                    // WebRtcMedia must send:
                    // { "type":"ack","cmd":"webrtc_ice","cmdId":"...","userId":"...","candidate":"..." }
                    break;
                }

                default:
                    router.sendNack(connectionManager, cmdId, cmd, "Unknown stream command: " + cmd);
                    break;
            }

        } catch (Exception e) {
            router.sendNack(connectionManager, cmdId, cmd, "Internal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
