package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.ui.UssoiStrings.*;

import android.content.Context;import android.util.Log;

import com.github.nikipo.ussoi.media.HFH264.HighFpsH264Media;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.h264.H264Media;
import com.github.nikipo.ussoi.media.webrtc.WebRtcMedia;
import com.github.nikipo.ussoi.service.control.ConnectionManager;

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
                    @Override public void stopStream() {}
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
                    @Override public short SwitchCamera(int camId)                             { return 0; }
                    @Override public short RotateCamera()                             { return 0; }
                    @Override public short FlipCamera()                               { return 0; }
                };
                break;
        }
    }

    public void stopStream()    { media.stopStream(); }
    public boolean isStreaming()  { return media.IsStreaming(); }
    public boolean isRecording()  { return media.IsRecording(); }

    // TODO use string maro to all lister sentences
    public void route(JSONObject json) {
        String cmd   = json.optString("cmd", "");
        String cmdId = json.optString("cmdId", "");

        try {
            switch (cmd) {

                case START_STREAM: {
                    short r = media.StartStream();

                    if (r == 0) router.sendResponse(connectionManager, cmdId, cmd,null);
                    else if (r == -1) router.sendError(connectionManager, cmdId, cmd, "Stream Encoder / peer connection Null");
                    else if (r == -2) router.sendError(connectionManager, cmdId, cmd, "Stream Already Running");
                    else if (r == -3) router.sendError(connectionManager, cmdId, cmd, "Stream Resolution Not Set");
                    else if (r == -4) router.sendError(connectionManager, cmdId, cmd, "Initialization Failed");
                    else if (r == -5) router.sendError(connectionManager, cmdId, cmd, "Stream And Record Resolution Required");


                    break;
                }

                case STOP_STREAM: {
                    if (media.IsRecording())  {
                        router.sendError(connectionManager, cmdId, cmd, "Recording Active");
                       break;
                    }
                    media.stopStream();
                    router.sendResponse(connectionManager, cmdId, cmd,null);
                    break;
                }

                case START_RECORDING: {
                    short r = media.StartRecording();
                    if (r == 0) router.sendResponse(connectionManager, cmd, cmdId,null);
                    else if (r == -1) router.sendError(connectionManager, cmdId, cmd, "Local Encoder Null");
                    else if (r == -2) router.sendError(connectionManager, cmdId, cmd, "Recording Already Running");
                    break;
                }

                case STOP_RECORDING: {
                    media.StopRecording();
                    router.sendResponse(connectionManager, cmd,cmdId,null);
                    break;
                }

                case SWITCH: {
                    JSONObject param = json.optJSONObject(PARAM);
                    if (param == null) break;

                    short r = media.SwitchCamera(param.optInt(CAM_ID,0));
                    if (r == 0) router.sendResponse(connectionManager, cmd,cmdId,null);
                    else if (r == -1) router.sendError(connectionManager, cmdId, cmd, "Recording Active");
                    else if (r == -2) router.sendError(connectionManager, cmdId, cmd, "Internal Error");
                    break;
                }

                case SET_RECORD_RES: {
                    JSONObject param = json.optJSONObject(PARAM);
                    if (param == null)  break;

                    if (param.has(RES)) {
                        JSONObject res = param.optJSONObject(RES);
                        if (res == null)  break;

                        int width  = res.optInt(WIDTH,  -1);
                        int height = res.optInt(HEIGHT, -1);
                        int fps    = res.optInt(FPS,    -1);

                        if (width < 1 || height < 1 || fps < 1) {
                            router.sendError(connectionManager, cmdId, cmd, "Invalid Resolution");
                            break;
                        }
                        short result = media.SetRecordingResolution(width, height, fps);

                        if (result != 0) {

                            String errorMsg;

                            switch (result) {
                                case -1:
                                    errorMsg = "Cannot change resolution recording active";
                                    break;

                                case -3:
                                    errorMsg = "Requested resolution not supported";
                                    break;

                                case -4:
                                    errorMsg = "Media system is not initialized";
                                    break;

                                default:
                                    errorMsg = "Unknown resolution configuration error";
                                    break;
                            }

                            router.sendError(connectionManager, cmdId, cmd, errorMsg);
                        }
                        else router.sendResponse(connectionManager,cmd,cmdId,null);
                    }
                    else if (param.has(BITRATE)) {
                        int bitrate = param.optInt(BITRATE, -1);
                        if (bitrate < 100) {
                            router.sendError(connectionManager, cmdId, cmd, "Invalid Record Bitrate");
                            break;
                        }
                        short r = media.SetRecordingBitrate(bitrate);
                        if (r == -4) router.sendError(connectionManager, cmdId, cmd, "Initialization Failed");
                        else router.sendResponse(connectionManager,cmd,cmdId,null);
                    }
                    break;
                }

                case SET_STREAM_RES: {
                    JSONObject param = json.optJSONObject(PARAM);
                    if (param == null)  break;

                    if (param.has(RES)) {
                        JSONObject res = param.optJSONObject(RES);
                        if (res == null)  break;

                        int width  = res.optInt(WIDTH,  -1);
                        int height = res.optInt(HEIGHT, -1);
                        int fps    = res.optInt(FPS,    -1);

                        Log.d(TAG,"SetRecordingResolution width :"+width+" height :"+height+" fps :"+fps);

                        if (width < 1 || height < 1 || fps < 1) {
                            router.sendError(connectionManager, cmdId, cmd, "Invalid Resolution");
                            break;
                        }

                        short r = media.SetStreamResolution(width, height, fps);

                        if (r != 0) {
                            String errorMsg;
                            switch (r) {
                                case -1:
                                    errorMsg = "Cannot change stream resolution while recording is active";
                                    break;
                                case -2:
                                    errorMsg = "Streaming encoder is unavailable";
                                    break;
                                case -3:
                                    errorMsg = "Requested stream resolution or FPS is not supported";
                                    break;
                                case -4:
                                    errorMsg = "Media system is not initialized";
                                    break;
                                case -5:
                                    errorMsg = "Failed to initialize streaming encoder";
                                    break;
                                default:
                                    errorMsg = "Unknown stream configuration error";
                                    break;
                            }
                            router.sendError(connectionManager, cmdId, cmd, errorMsg);
                        } else {
                            router.sendResponse(connectionManager, cmd, cmdId, null);
                        }
                        break;
                    }

                    else if (param.has(BITRATE)) {
                        int bitrate = param.optInt(BITRATE, -1);
                        if (bitrate < 100) {
                            router.sendError(connectionManager, cmdId, cmd, "Invalid Stream Bitrate");
                            break;
                        }
                        short r = media.SetStreamBitrate(bitrate);
                        if (r == -4) router.sendError(connectionManager, cmdId, cmd, "Initialization Failed");
                        else  router.sendResponse(connectionManager,cmd,cmdId,null);
                        break;
                    }
                    else {
                        router.sendError(connectionManager, cmdId, cmd, "Invalid Request");
                    }
                    break;
                }

                case WEBRTC_SDP: {
                    if (!(media instanceof WebRtcMedia)) {
                        router.sendError(connectionManager, cmdId, cmd, "Not in WebRTC mode");
                        break;
                    }
                    if (streamMode != StreamMode.WebRtc) {
                        router.sendError(connectionManager, cmdId, cmd, "Not in WebRTC mode");
                        router.sendError(connectionManager, cmdId, cmd, "Stream Mode Out of sync");
                        break;
                    }
                    JSONObject params = json.optJSONObject(PARAM);
                    if (params == null) {
                        router.sendError(connectionManager, cmdId, cmd, "Missing SDP params");
                        break;
                    }

                    ((WebRtcMedia) media).setSdp(params);
                    // No immediate ACK — WebRtcMedia sends the answer asynchronously.
                    // WebRtcMedia must send:
                    // { "type":"ack","cmd":"webrtc_offer","cmdId":"...","userId":"...","sdp":"<answer>" }
                    break;
                }

                case WEBRTC_ICE: {
                    if (streamMode != StreamMode.WebRtc) {
                        router.sendError(connectionManager, cmdId, cmd, "Not in WebRTC mode");
                        break;
                    }
                    JSONObject params = json.optJSONObject(PARAM);
                    if (params == null) {
                        router.sendError(connectionManager, cmdId, cmd, "Missing ICE params");
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


                case "play": {
                    media.StreamMute(false);
                    break;
                }

                case "pause": {
                    media.StreamMute(true);
                    break;
                }

                case "mute": {
                    if (streamMode == StreamMode.WebRtc) {
                        ((WebRtcMedia) media).AudioMute();
                    } else {
                        router.sendError(connectionManager, cmdId, cmd, "Audio mute only supported in WebRTC mode");
                    }
                    break;
                }

                // ── Camera controls ───────────────────────────────────────────

                case "flip": {
                    short r = media.FlipCamera();
                    break;
                }

                case "rotate": {
                    short r = media.RotateCamera();
                    break;
                }

                default:
                    router.sendError(connectionManager, cmdId, cmd, "[Android] Unknown command: " + cmd);
                    break;
            }

        } catch (Exception e) {
            // LOG TODO
            e.printStackTrace();
        }
    }
}
