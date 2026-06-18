package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.ui.UssoiStrings.*;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.util.Log;

import com.github.nikipo.ussoi.media.hfh264.HFH264Media;
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
    private final Context context;
    StreamMode streamMode;
    private Media media;

    public StreamRoute(ConnectionManager connectionManager, Router router, Context context, StreamMode streamMode) {
        this.connectionManager = connectionManager;
        this.router = router;
        this.context = context;
        this.streamMode = streamMode;
        setStreamMode();
        try {
            media.init(this.context);
        } catch (Exception e) {
            streamMode = StreamMode.None;
        }
    }

    private void setStreamMode() {
        switch (streamMode) {
            case H264:
                media = new H264Media();
                break;
            case HFH264:
                media = new HFH264Media();
                break;
            case WebRtc:
                media = new WebRtcMedia(connectionManager);
                break;
            case None:
            default:
                media = new Media() {
                    @Override
                    public void init(Context ctx) throws CameraAccessException {

                    }

                    @Override
                    public void close() {

                    }

                    @Override
                    public void StartStream() {

                    }

                    @Override
                    public void stopStream() {

                    }

                    @Override
                    public void SetStreamResolution(int width, int height, int fps) {

                    }

                    @Override
                    public void SetStreamBitrate(int bitrate) {

                    }

                    @Override
                    public boolean IsStreaming() {
                        return false;
                    }

                    @Override
                    public void StreamMute(boolean mute) {

                    }

                    @Override
                    public void StartRecording() {

                    }

                    @Override
                    public void SetRecordingResolution(int width, int height, int fps) {

                    }

                    @Override
                    public void SetRecordingBitrate(int bitrate) {

                    }

                    @Override
                    public boolean IsRecording() {
                        return false;
                    }

                    @Override
                    public void StopRecording() {

                    }

                    @Override
                    public void SwitchCamera(int camId) {

                    }

                    @Override
                    public void RotateCamera() {

                    }

                    @Override
                    public void FlipCamera() {

                    }
                };
                break;
        }
    }

    public void closeStream()    { media.close(); }
    public boolean isStreaming()  { return media.IsStreaming(); }
    public boolean isRecording()  { return media.IsRecording(); }

    // TODO use string maro to all lister sentences
    public void route(JSONObject json) {
        String cmd   = json.optString("cmd", "");
        String cmdId = json.optString("cmdId", "");

        try {
            switch (cmd) {

                case START_STREAM: {
                    media.StartStream();
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case STOP_STREAM: {
                    media.stopStream();
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case START_RECORDING: {
                    media.StartRecording();
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case STOP_RECORDING: {
                    media.StopRecording();
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case SET_STREAM_RES: {
                    JSONObject param = json.optJSONObject(PARAM);

                    if (param == null) {
                        router.sendError(connectionManager, cmdId, cmd, "Missing Params");
                        break;
                    }

                    if (param.has(RES)) {
                        JSONObject res = param.optJSONObject(RES);

                        if (res == null) {
                            router.sendError(connectionManager, cmdId, cmd, "Missing Resolution");
                            break;
                        }

                        int width  = res.optInt(WIDTH, -1);
                        int height = res.optInt(HEIGHT, -1);
                        int fps    = res.optInt(FPS, -1);

                        if (width < 1 || height < 1 || fps < 1 || fps > 1000) {
                            router.sendError(connectionManager, cmdId, cmd, "Invalid Resolution or Fps");
                            break;
                        }

                        media.SetStreamResolution(width, height, fps);
                        router.sendResponse(connectionManager, cmdId, cmd, null);
                    }
                    else if (param.has(BITRATE)) {

                        int bitrate = param.optInt(BITRATE, -1);

                        if (bitrate < 100) {
                            router.sendError(connectionManager, cmdId, cmd, "Invalid Stream Bitrate");
                            break;
                        }

                        media.SetStreamBitrate(bitrate);
                        router.sendResponse(connectionManager, cmdId, cmd, null);
                    }
                    else {
                        router.sendError(connectionManager, cmdId, cmd, "Invalid Request");
                    }

                    break;
                }

                case SET_RECORD_RES: {
                    JSONObject param = json.optJSONObject(PARAM);

                    if (param == null) {
                        router.sendError(connectionManager, cmdId, cmd, "Missing Params");
                        break;
                    }

                    if (param.has(RES)) {

                        JSONObject res = param.optJSONObject(RES);

                        if (res == null) {
                            router.sendError(connectionManager, cmdId, cmd, "Missing Resolution");
                            break;
                        }

                        int width  = res.optInt(WIDTH, -1);
                        int height = res.optInt(HEIGHT, -1);
                        int fps    = res.optInt(FPS, -1);

                        if (width < 1 || height < 1 || fps < 1) {
                            router.sendError(connectionManager, cmdId, cmd, "Invalid Resolution");
                            break;
                        }

                        media.SetRecordingResolution(width, height, fps);
                        router.sendResponse(connectionManager, cmdId, cmd, null);
                    }
                    else if (param.has(BITRATE)) {

                        int bitrate = param.optInt(BITRATE, -1);

                        if (bitrate < 100) {
                            router.sendError(connectionManager, cmdId, cmd,
                                    "Invalid Record Bitrate");
                            break;
                        }

                        media.SetRecordingBitrate(bitrate);
                        router.sendResponse(connectionManager, cmdId, cmd, null);
                    }
                    else {
                        router.sendError(connectionManager, cmdId, cmd,
                                "Invalid Request");
                    }

                    break;
                }

                case SWITCH: {
                    JSONObject param = json.optJSONObject(PARAM);

                    if (param == null) {
                        router.sendError(connectionManager, cmdId, cmd, "Missing Params");
                        break;
                    }

                    media.SwitchCamera(param.optInt(CAM_ID, 0));
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case PLAY: {
                    media.StreamMute(false);
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case PAUSE: {
                    media.StreamMute(true);
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case MUTE: {
                    if (streamMode != StreamMode.WebRtc) {
                        router.sendError(connectionManager, cmdId, cmd,
                                "Audio mute only supported in WebRTC mode");
                        break;
                    }
                    //todo

                    JSONObject param = json.optJSONObject(PARAM);

                    if (param == null) {
                        router.sendError(connectionManager, cmdId, cmd, "Missing Params");
                        break;
                    }

                    ((WebRtcMedia) media).AudioMute(param.optBoolean(STATE, false));
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case FLIP: {
                    media.FlipCamera();
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case ROTATE: {
                    media.RotateCamera();
                    router.sendResponse(connectionManager, cmdId, cmd, null);
                    break;
                }

                case WEBRTC_SDP: {
                    if (!(media instanceof WebRtcMedia)) {
                        router.sendError(connectionManager, cmdId, cmd,
                                "Not in WebRTC mode");
                        break;
                    }

                    JSONObject params = json.optJSONObject(PARAM);

                    if (params == null) {
                        router.sendError(connectionManager, cmdId, cmd,
                                "Missing SDP params");
                        break;
                    }

                    ((WebRtcMedia) media).setSdp(params);
                    break;
                }
                case WEBRTC_ICE: {
                    if (!(media instanceof WebRtcMedia)) {
                        router.sendError(connectionManager, cmdId, cmd,
                                "Not in WebRTC mode");
                        break;
                    }

                    JSONObject params = json.optJSONObject(PARAM);

                    if (params == null) {
                        router.sendError(connectionManager, cmdId, cmd,
                                "Missing ICE params");
                        break;
                    }

                    params.put("cmdId", cmdId);
                    params.put("userId", json.optString("userId", ""));

                    ((WebRtcMedia) media).SetIce(params);
                    break;
                }

                default:
                    router.sendError(connectionManager, cmdId, cmd, "[Android] Unknown command: " + cmd);
                    break;
            }

        }
        catch (Exception e) {
                Log.e(TAG, "Route Error", e);
                router.sendError(
                        connectionManager,
                        cmdId,
                        cmd,
                        e.getMessage() != null
                                ? e.getMessage()
                                : e.toString()
                );
        }
    }
}

