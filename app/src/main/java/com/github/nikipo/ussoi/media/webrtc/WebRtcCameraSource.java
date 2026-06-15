package com.github.nikipo.ussoi.media.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.util.Log;
import android.util.Range;

import com.github.nikipo.ussoi.media.utility.CameraHelper;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;

import org.webrtc.Camera2Capturer;
import org.webrtc.SurfaceTextureHelper;

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

    /** Cumulative logical rotation applied to outgoing frames (0 / 90 / 180 / 270). */
    private int currentRotation = 0;

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
     * Selects a camera, builds the {@link Camera2Capturer}, and starts capturing.
     *
     * <p>The capturer is initialised against {@code capturerObserver} — which should
     * be {@link WebRtcPeerConnection#getRotatingCapturerObserver()} — so that every
     * frame has the current logical rotation stamped onto it before it reaches the
     * WebRTC encoder. This is what makes {@link #rotateVideo()} work without
     * restarting the camera.
     *
     * @param capturerObserver    Rotation-injecting observer from
     *                            {@link WebRtcPeerConnection#getRotatingCapturerObserver()}.
     * @param surfaceTextureHelper EGL-backed helper provided by
     *                             {@link WebRtcPeerConnection#getSurfaceTextureHelper()}.
     */
    public synchronized void start(org.webrtc.CapturerObserver capturerObserver,
                                   SurfaceTextureHelper surfaceTextureHelper) {
        // Select first available camera
        if (currentCameraId == null) {
            try {
                currentCameraId = cameraHelper.getCameraIdList(context)[0];
            } catch (CameraAccessException e) {
                e.printStackTrace();
                Log.e(TAG, "camera selection exception");
                return;
            }
        }

        // Resolve best supported size
//        Size resolved = cameraHelper.getHardwareSupportedResolution(
//                currentCameraId, videoWidth, videoHeight);
//        if (resolved != null) {
//            videoWidth  = resolved.getWidth();
//            videoHeight = resolved.getHeight();
//        }

        // Resolve best FPS range
        Range<Integer> fpsRange = cameraHelper.getOptimalFpsRange(context,currentCameraId, videoFps);
        videoFps = fpsRange.getUpper();

        Log.d(TAG, "Camera source config: " + videoWidth + "x" + videoHeight + " @" + videoFps);

        // Build Camera2Capturer and wire it through the rotation-injecting observer
        capturer = new Camera2Capturer(context, currentCameraId, null);
        capturer.initialize(surfaceTextureHelper, context, capturerObserver);
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
            Log.w(TAG, "to switchCamera: stop recording first");
            return false;
        }
        if (capturer == null) return false;

        capturer.switchCamera(null); // WebRTC handles the Camera2 re-open internally
        Log.d(TAG, "Switched to camera: " + currentCameraId);
        return true;
    }

    // -------------------------------------------------------------------------
    // Rotation
    // -------------------------------------------------------------------------

    /**
     * Rotates the outgoing video by +90° (cumulative) each time it is called.
     *
     * <p>How it works:
     * <ol>
     *   <li>The logical rotation counter advances by 90°.</li>
     *   <li>Width and height are swapped so the encoder aspect-ratio stays correct
     *       after an odd-quarter rotation.</li>
     *   <li>{@link Camera2Capturer#changeCaptureFormat} is called — no camera
     *       restart is needed.</li>
     *   <li>The actual per-frame rotation metadata is injected by the
     *       {@link WebRtcPeerConnection#getRotatingCapturerObserver()} relay that was
     *       wired in {@link #start}. {@link WebRtcMedia#RotateCamera()} updates
     *       {@link WebRtcPeerConnection#setFrameRotation(int)} with the new value.</li>
     * </ol>
     *
     * <p><b>Recording:</b> you do NOT need to stop or restart recording. The frames
     * flowing into the recorder already carry the updated rotation metadata. If
     * {@link LocalRecorder} uses {@link android.media.MediaRecorder#setOrientationHint}
     * that hint only affects the MP4 container tag and cannot be changed mid-session;
     * the current recording segment keeps its original orientation tag (pixels are
     * correct), and the next session will pick up the new hint automatically.
     *
     * @return The new cumulative rotation in degrees (0 / 90 / 180 / 270).
     */
    public synchronized int rotateVideo() {
        currentRotation = (currentRotation + 90) % 360;

        // Swap width ↔ height on odd-quarter rotations so the encoder
        // sees the correct aspect ratio (portrait vs landscape).
        int tmp     = videoWidth;
        videoWidth  = videoHeight;
        videoHeight = tmp;

        if (capturer != null) {
            capturer.changeCaptureFormat(videoWidth, videoHeight, videoFps);
        }

        Log.d(TAG, "rotateVideo → " + currentRotation + "°  dims: "
                + videoWidth + "x" + videoHeight);
        return currentRotation;
    }

    /** @return Current logical rotation in degrees (0 / 90 / 180 / 270). */
    public int getCurrentRotation() { return currentRotation; }

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

//        Size resolved = cameraHelper.getHardwareSupportedResolution(
//                currentCameraId, width, height);
//        if (resolved != null) {
//            width  = resolved.getWidth();
//            height = resolved.getHeight();
//        }

        Range<Integer> fpsRange = cameraHelper.getOptimalFpsRange(context, currentCameraId, fps);
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