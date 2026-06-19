package com.github.nikipo.ussoi.media;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;

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
    // Must be called before any other API.
    void init(Context ctx) throws CameraAccessException;

    // Safe to call multiple times.
    void close();

    // Requires valid camera ID and stream resolution.
    void StartStream();

    // Streaming must be active.
    void stopStream();

    // Streaming must be stopped.
   // Resolution/FPS must be supported by selected camera.
    void SetStreamResolution(int width, int height, int fps);
    void SetStreamBitrate(int bitrate);
    boolean IsStreaming();

    // Streaming must be active.
    void StreamMute(boolean mute);

    // Requires valid recording resolution.
    void StartRecording();

    // Recording must be stopped.
// Resolution/FPS must be supported by selected camera.
    void SetRecordingResolution(int width, int height, int fps);

    void SetRecordingBitrate(int bitrate);

    boolean IsRecording();

    // Recording must be active.
    void StopRecording();

    // Recording must not be active.
    void SwitchCamera(int camId);

    // Host implementation required.
    void RotateCamera();

    // Host implementation required.
    void FlipCamera();
}

