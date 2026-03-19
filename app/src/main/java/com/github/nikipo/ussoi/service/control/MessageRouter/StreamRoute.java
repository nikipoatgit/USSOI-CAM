package com.github.nikipo.ussoi.service.control.MessageRouter;

import android.content.Context;

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
    public StreamRoute(ConnectionManager connectionManager, Router router, Context ctx, StreamMode streamMode) {
    }

    public void stopStream() {
    }

    public void route(ConnectionManager connectionManager, JSONObject json) {

    }
}
