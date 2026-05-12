package com.github.nikipo.ussoi.media;

import android.content.Context;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file Media
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
public interface Media {
    // todo implement close

    // sets up context and initial variable
    void init(Context ctx);

    //tears down every thing
    void stopStream();


    // creates every thing form factory
    short StartStream();
    short SetStreamResolution(int width, int height, int fps);
    short SetStreamBitrate(int bitrate);
    boolean IsStreaming();
    void StreamMute(boolean mute);

    // Stream methods
    short StartRecording();
    short SetRecordingResolution(int width, int height, int fps);
    short SetRecordingBitrate(int bitrate);
    boolean IsRecording();
    void StopRecording();

    // utility
    short SwitchCamera(int camId);
    short RotateCamera();
    short FlipCamera();
}

