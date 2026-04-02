package com.github.nikipo.ussoi.media.webrtc;

import android.content.Context;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import com.github.nikipo.ussoi.media.camera.CameraHelper;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;

import org.webrtc.Camera2Capturer;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;

import java.io.IOException;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file WebRtcCameraSource
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
public final class WebRtcCameraSource {

    private static final String TAG = "WebRtcCameraSource";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Context      context;
    private final CameraHelper cameraHelper;

    private String         currentCameraId = null;
    private int            videoWidth;
    private int            videoHeight;
    private int            videoFps;
    private int            recordBitrateBps;

    private Camera2Capturer capturer;
    private LocalRecorder   localRecorder;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param context        Application context.
     * @param width          Desired capture width.
     * @param height         Desired capture height.
     * @param fps            Desired capture frame rate.
     * @param recordBitrate  Bitrate (bps) for local recording.
     */
    public WebRtcCameraSource(Context context,
                              int width, int height, int fps,
                              int recordBitrate) {
        this.context         = context.getApplicationContext();
        this.videoWidth      = width;
        this.videoHeight     = height;
        this.videoFps        = fps;
        this.recordBitrateBps = recordBitrate;
        this.cameraHelper    = new CameraHelper(context);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Selects a camera, builds the {@link Camera2Capturer}, and starts capturing
     * into the given {@link VideoSource}.
     *
     * @param videoSource         Target WebRTC video source.
     * @param surfaceTextureHelper EGL-backed helper provided by
     *                             {@link WebRtcPeerConnection#getSurfaceTextureHelper()}.
     */
    public synchronized void start(VideoSource videoSource,
                                   SurfaceTextureHelper surfaceTextureHelper) {
        // Select first available camera
        currentCameraId = cameraHelper.cycleCameraId(null);
        if (currentCameraId == null) {
            Log.e(TAG, "No camera available");
            return;
        }

        // Resolve best supported size
        Size resolved = cameraHelper.getHardwareSupportedResolution(
                currentCameraId, videoWidth, videoHeight);
        if (resolved != null) {
            videoWidth  = resolved.getWidth();
            videoHeight = resolved.getHeight();
        }

        // Resolve best FPS range
        Range<Integer> fpsRange = cameraHelper.getOptimalFpsRange(
                currentCameraId, videoWidth, videoHeight, videoFps);
        videoFps = fpsRange.getUpper();

        Log.d(TAG, "Camera source config: " + videoWidth + "x" + videoHeight + " @" + videoFps);

        // Build Camera2Capturer with the resolved camera ID
        Camera2Enumerator enumerator = new Camera2Enumerator(context);
        capturer = new Camera2Capturer(context, currentCameraId, null);

        capturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        capturer.startCapture(videoWidth, videoHeight, videoFps);

        Log.d(TAG, "Camera capturer started: " + currentCameraId);
    }

    /**
     * Stops the capturer and releases camera resources.
     * Does NOT stop recording — call {@link #stopRecording()} / {@link #releaseRecorder()} first.
     */
    public synchronized void stop() {
        if (capturer != null) {
            capturer.stopCapture();
            capturer.dispose();
            capturer = null;
        }
    }

    // -------------------------------------------------------------------------
    // Camera switching
    // -------------------------------------------------------------------------

    /**
     * Cycles to the next available camera.
     * If recording is active this returns {@code false} — stop recording first.
     *
     * @return {@code true} if the switch succeeded.
     */
    public synchronized boolean switchCamera() {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            Log.w(TAG, "switchCamera: stop recording first");
            return false;
        }
        if (capturer == null) return false;

        cameraHelper.invalidateCameraCache();
        currentCameraId = cameraHelper.cycleCameraId(currentCameraId);
        capturer.switchCamera(null); // WebRTC handles the Camera2 re-open internally
        Log.d(TAG, "Switched to camera: " + currentCameraId);
        return true;
    }

    // -------------------------------------------------------------------------
    // Resolution / FPS change
    // -------------------------------------------------------------------------

    /**
     * Changes the capture resolution and FPS at runtime.
     * Uses {@link Camera2Capturer#changeCaptureFormat} — no camera restart needed.
     *
     * @return {@code false} if recording is active (stop it first).
     */
    public synchronized boolean changeCaptureFormat(int width, int height, int fps) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            Log.w(TAG, "changeCaptureFormat: stop recording first");
            return false;
        }
        if (capturer == null) return false;

        Size resolved = cameraHelper.getHardwareSupportedResolution(
                currentCameraId, width, height);
        if (resolved != null) {
            width  = resolved.getWidth();
            height = resolved.getHeight();
        }

        Range<Integer> fpsRange = cameraHelper.getOptimalFpsRange(
                currentCameraId, width, height, fps);
        fps = fpsRange.getUpper();

        videoWidth  = width;
        videoHeight = height;
        videoFps    = fps;

        capturer.changeCaptureFormat(videoWidth, videoHeight, videoFps);
        Log.d(TAG, "Capture format changed: " + videoWidth + "x" + videoHeight + " @" + videoFps);
        return true;
    }

    // -------------------------------------------------------------------------
    // Local recording (reuses LocalRecorder from H264Media)
    // -------------------------------------------------------------------------

    /**
     * Prepares the local recorder.
     * Must be called before {@link #startRecording()}.
     * The recorder surface is independent of the WebRTC video track — Camera2
     * sends frames to both simultaneously via multi-surface capture.
     *
     * <p><b>Note:</b> Because WebRTC's {@link Camera2Capturer} owns the Camera2
     * session, local recording here uses {@link android.media.MediaRecorder} with
     * {@link android.view.Surface} mode, but it cannot share the same Camera2
     * session. A production implementation should either:
     * <ul>
     *   <li>Use a custom {@link Camera2Capturer} subclass that adds the recorder
     *       surface to the capture session, OR</li>
     *   <li>Record from the WebRTC video source using a
     *       {@link org.webrtc.VideoSink} + MediaMuxer pipeline.</li>
     * </ul>
     * This method sets up the recorder surface for the second approach and is
     * intentionally left as a hook for that integration.
     *
     * @return Prepared {@link LocalRecorder}, or {@code null} on failure.
     */
    public synchronized LocalRecorder prepareRecorder() {
        try {
            localRecorder = new LocalRecorder(context);
            // TODO MAKE sure this match actual frame width and height
            localRecorder.prepare(videoWidth, videoHeight, videoFps, recordBitrateBps);
            return localRecorder;
        } catch (IOException e) {
            Log.e(TAG, "prepareRecorder failed", e);
            localRecorder = null;
            return null;
        }
    }

    /** Starts local recording if the recorder is prepared and not yet started. */
    public synchronized boolean startRecording() {
        if (localRecorder == null || localRecorder.isRecordingActive()) return false;
        localRecorder.start();
        return true;
    }

    /** Stops local recording. */
    public synchronized void stopRecording() {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            localRecorder.stop();
        }
    }

    /** Releases the local recorder resources. */
    public synchronized void releaseRecorder() {
        if (localRecorder != null) {
            localRecorder.release();
            localRecorder = null;
        }
    }

    /** @return {@code true} if local recording is currently active. */
    public synchronized boolean isRecordingActive() {
        return localRecorder != null && localRecorder.isRecordingActive();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String  getCurrentCameraId() { return currentCameraId; }
    public int     getWidth()           { return videoWidth;  }
    public int     getHeight()          { return videoHeight; }
    public int     getFps()             { return videoFps;    }
    public CameraHelper getCameraHelper() { return cameraHelper; }
}
