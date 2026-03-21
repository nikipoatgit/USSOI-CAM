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
public class StreamRoute {
    private static final String TAG = "StreamRoute";

    private ConnectionManager connectionManager;
    private Router router;
    private Context ctx;
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
        switch (streamMode){
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
                break;
        }
    }

    public void stopStream() {
        media.stop();
    }

    public boolean isStreaming(){
        return media.IsStreaming();
    }
    public boolean isRecording(){
        return media.IsRecording();
    }
    public void route(JSONObject json) {
        try {
            String cmd   = json.optString("cmd", "");
            String cmdId = json.optString("cmdId", "");

            switch (cmd) {

                case "start_stream":
                    media.StartStream();
                    break;

                case "stop_stream":
                    // todo check stop thing if it is possible to restart
                    media.stop();
                    break;

                case "start_recording":
                    media.StartRecording();
                    break;

                case "stop_recording":
                    media.StopRecording();
                    break;

                case "get_res":
                    // payload = media.SupportedResolutions();
                    /*
                    * {"type":"data","cmd":"res","cmdId":"c9020","timestamp":1710000101,"deviceId":"abc","payload":{"cameraId":"0","mode":"high_speed","resolutions":[{"width":1920,"height":1080,"fpsRanges":[{"min":120,"max":120},{"min":60,"max":120}]},{"width":1280,"height":720,"fpsRanges":[{"min":240,"max":240},{"min":120,"max":240},{"min":60,"max":240}]}]}}
                    *
                    * send this   connectionManager.send(json obj);
                    * */

                    break;

                case "set_stream_res":
                    JSONObject params1 = json.optJSONObject("params1");
                    if (params1 != null) {
                         if (params1.has("width")) {
                             int width = params1.optInt("width", -1);
                             int height = params1.optInt("height", -1);
                             int fps = params1.optInt("fps", -1);

                             // Resolution change (requires all 3)
                             if (width > 0 && height > 0 && fps > 0) {
                                 media.SetStreamResolution(width, height, fps);
                             }
                         }
                         if (params1.has("bitrate")){
                             int bitrate = params1.optInt("bitrate", -1);
                             // Bitrate in bps
                             if (bitrate > 0) {
                                 media.SetStreamBitrate(bitrate);
                             }
                         }
                    }
                    break;

                case "set_record_res":
                    JSONObject params2 = json.optJSONObject("params");
                    if (params2 != null) {
                        if (params2.has("width")) {
                            int width = params2.optInt("width", -1);
                            int height = params2.optInt("height", -1);
                            int fps = params2.optInt("fps", -1);

                            // Resolution change (requires all 3)
                            if (width > 0 && height > 0 && fps > 0) {
                                media.SetRecordingResolution(width, height, fps);
                            }
                        }
                        if (params2.has("bitrate")){
                            int bitrate = params2.optInt("bitrate", -1);
                            // Bitrate in bps
                            if (bitrate > 0) {
                                media.SetRecordingBitrate(bitrate);
                            }
                        }
                    }
                    break;

                case "play":
                    media.StreamMute(false);
                    break;

                case "pause":
                    media.StreamMute(true);
                    break;

                case "mute":
                    if (streamMode == StreamMode.WebRtc){
                        WebRtcMedia wm = (WebRtcMedia) media;
                        wm.AudioMute();
                    }
                    break;

                case "flip":
                    media.FlipCamera();
                    break;

                case "rotate":
                    media.RotateCamera();
                    break;

                case "switch":
                    media.SwitchCamera();
                    break;

                case "webrtc_ice":
                    JSONObject params4 = json.optJSONObject("params");
                    if (params4 == null){
                        break;
                    }
                    WebRtcMedia wm1 = (WebRtcMedia) media;
                    wm1.SetIce(params4);
                    break;

                case "webrtc_offer":
                    if (streamMode == StreamMode.WebRtc){
                        JSONObject params3 = json.optJSONObject("params");
                        if (params3 == null){
                            break;
                        }
                        WebRtcMedia wm2 = (WebRtcMedia) media;
                        wm2.setSdp(params3);
                    }
                    break;

                default:
                    router.sendNack(connectionManager, cmdId, cmd);
                    break;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
