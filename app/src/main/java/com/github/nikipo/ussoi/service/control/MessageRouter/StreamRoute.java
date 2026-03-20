package com.github.nikipo.ussoi.service.control.MessageRouter;

import android.content.Context;

import com.github.nikipo.ussoi.media.CameraControl;
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

    public void route(ConnectionManager connectionManager, JSONObject json) {

    }
}
