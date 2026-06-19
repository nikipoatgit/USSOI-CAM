package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.ui.UssoiStrings.STREAM_H264;
import static com.github.nikipo.ussoi.ui.UssoiStrings.STREAM_HFH264;
import static com.github.nikipo.ussoi.ui.UssoiStrings.STREAM_NONE;
import static com.github.nikipo.ussoi.ui.UssoiStrings.STREAM_WEBRTC;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file StreamMode
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
public enum StreamMode {
    WebRtc,
    H264,
    HFH264,
    None;

    public static StreamMode fromString(String value) {
        if (value == null) return null;
        switch (value) {
            case STREAM_WEBRTC: return WebRtc;
            case STREAM_H264: return H264;
            case STREAM_HFH264: return HFH264;
            case STREAM_NONE: return None;
            default : return null;
        }
    }
}
