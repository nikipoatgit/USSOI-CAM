package com.github.nikipo.ussoi.media.hfh264;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.media.CameraControl;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.media.enocders.StreamingEncoder;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.storage.logs.Logging;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file HighFpsH264Media
 * @attention Copyright (c) 2026
 * All rights reserved.
 * <p>
 * This software is licensed under the terms described in the LICENSE file
 * located in the root directory of this project.
 * If no LICENSE file is present, this software is provided "AS IS",
 * without warranty of any kind, express or implied.
 * <p>
 * *****************************************************************************
 *
 */
public class HighFpsH264Media implements Media, CameraControl {

    private static final String TAG         = "HighFpsH264Media";
    private static final int    HEADER_SIZE = 1 + 8; // keyFrame flag + 8-byte PTS

    private Context           context;
    private Logging           logger;
    private SharedPreferences preferences;
    private WebSocketHandler  webSocketHandler;

    private HighSpeedCameraHelper highSpeedCameraHelper;

    // ── Encoder config ────────────────────────────────────────────────────────
    private int videoWidth       = 1280;
    private int videoHeight      = 720;
    private int videoFps         = 60;
    private int lqWidth          = 1280;
    private int lqHeight         = 720;
    private int streamBitrateBps = 100_000;
    private int recordBitrateBps = 2_000_000;

    private Size           currentSize;
    private Range<Integer> currentFpsRange;

    // ── Camera ────────────────────────────────────────────────────────────────
    private String                  currentCameraId = null;
    private HighFPSCameraController cameraController;

    // ── GL renderer ───────────────────────────────────────────────────────────
    private GlRenderer glRenderer;

    // ── Encoders + surfaces ───────────────────────────────────────────────────
    private LocalRecorder    localRecorder;
    private StreamingEncoder streamingEncoder;
    private Surface          hqSurface;
    private Surface          lqSurface;

    // ── Drain thread ──────────────────────────────────────────────────────────
    private Thread           drainThread;
    private volatile boolean drainRunning = false;
    private long             basePtsUs    = -1;

    // ─────────────────────────────────────────────────────────────────────────
    // Media — lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void init(Context ctx) {

        context = ctx.getApplicationContext();

        logger = Logging.getInstance(context);

        preferences =
                SaveInputFields.getInstance(context)
                        .get_shared_pref();

        highSpeedCameraHelper =
                new HighSpeedCameraHelper(context);

        currentCameraId =
                highSpeedCameraHelper.cycleCameraId(currentCameraId);

        if (currentCameraId == null)
            throw new RuntimeException("No high speed camera");

        // currentSize intentionally left null until SetStreamResolution() is called.
        // StartStream() will return -3 if resolution has not been configured yet.
    }
    /**
     * Tears down the entire pipeline.
     * After this call the object must not be used again — call init() to
     * create a fresh instance instead.
     *
     * Order: drain → streaming encoder → recording encoder → GL → camera → WS
     */
    @Override
    public void close() {

        stopStream();

        highSpeedCameraHelper = null;

        context = null;
    }
    @Override
    public synchronized String stopStream() {

        StringBuilder errors = new StringBuilder();

        drainRunning = false;

        try {
            stopDrainThread();
        } catch (Exception e) {
            errors.append("drain ");
            Log.w(TAG, "Failed to stop drain thread", e);
        }

        try {
            if (cameraController != null) {
                cameraController.stop();
                cameraController = null;
            }
        } catch (Exception e) {
            errors.append("camera ");
            Log.w(TAG, "Failed to stop camera", e);
        }

        try {
            if (glRenderer != null) {
                glRenderer.release();
                glRenderer = null;
            }
        } catch (Exception e) {
            errors.append("renderer ");
            Log.w(TAG, "Failed to release renderer", e);
        }

        try {
            if (streamingEncoder != null) {
                streamingEncoder.stop();
                streamingEncoder.release();
                streamingEncoder = null;
            }
        } catch (Exception e) {
            errors.append("streamEncoder ");
            Log.w(TAG, "Failed to stop streaming encoder", e);
        }

        try {
            if (localRecorder != null) {

                if (localRecorder.isRecordingActive())
                    localRecorder.stop();

                localRecorder.release();
                localRecorder = null;
            }
        } catch (Exception e) {
            errors.append("recorder ");
            Log.w(TAG, "Failed to stop recorder", e);
        }

        try {
            if (webSocketHandler != null) {
                webSocketHandler.close();
                webSocketHandler = null;
            }
        } catch (Exception e) {
            errors.append("websocket ");
            Log.w(TAG, "Failed to close websocket", e);
        }

        hqSurface = null;
        lqSurface = null;
        basePtsUs = -1;

        return errors.length() == 0 ? null : errors.toString().trim();
    }

    @Override
    public synchronized String StartStream() {

        if (drainRunning)
            return "Already streaming";

        if (currentSize == null)
            return "Resolution not set";

        try {

            webSocketHandler =
                    new WebSocketHandler(
                            context,
                            new WebSocketHandler.MessageCallback() {

                                @Override
                                public void onOpen() {}

                                @Override
                                public void onPayloadReceivedText(String p) {}

                                @Override
                                public void onPayloadReceivedByte(byte[] p) {}

                                @Override
                                public void onClosed() {}

                                @Override
                                public void onError(String e) {}
                            });

            webSocketHandler.setupConnection(
                    KEY_stream_api_path,
                    preferences.getString(
                            KEY_Session_KEY,
                            "123"));

            localRecorder =
                    new LocalRecorder(context);

            hqSurface =
                    localRecorder.prepare(
                            videoWidth,
                            videoHeight,
                            currentFpsRange.getUpper(),
                            recordBitrateBps);

            if (hqSurface == null)
                return "Recorder failed";

            streamingEncoder =
                    new StreamingEncoder();

            lqSurface =
                    streamingEncoder.prepare(
                            lqWidth,
                            lqHeight,
                            currentFpsRange.getUpper(),
                            streamBitrateBps);

            if (lqSurface == null)
                return "Encoder failed";

            glRenderer =
                    new GlRenderer.Builder(
                            videoWidth,
                            videoHeight,
                            lqWidth,
                            lqHeight)
                            .scaleMode(
                                    GlRenderer.ScaleMode.CROP)
                            .contextLostCallback(
                                    this::onGlContextLost)
                            .build(context);

            glRenderer.init(
                    hqSurface,
                    lqSurface);

            cameraController =
                    new HighFPSCameraController(context);

            cameraController.attachCameraSurface(
                    glRenderer.getCameraSurface());

            cameraController.start(
                    currentCameraId,
                    currentSize,
                    currentFpsRange.getUpper());

            streamingEncoder.start();

            basePtsUs = -1;

            drainRunning = true;

            drainThread =
                    new Thread(
                            this::drainLoop,
                            "HighFpsStreamDrain");

            drainThread.start();

            return null;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "StartStream failed",
                    e);

            stopStream();

            return "Pipeline failed";
        }
    }
    @Override
    public String SetStreamResolution(int width, int height, int fps) {

        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SetStreamResolution — stop recording first");
            return "Recording active";
        }

        Size resolved = highSpeedCameraHelper.getBestHighSpeedSize(
                currentCameraId, width, height, fps);

        if (resolved == null) {
            logger.log(TAG + ": SetStreamResolution — no suitable high-speed size");
            return "Unsupported resolution";
        }

        boolean changed =
                !resolved.equals(currentSize)
                        || fps != videoFps;

        videoFps = fps;

        // Both stream and record use the same hardware-resolved size.
        videoWidth = resolved.getWidth();
        videoHeight = resolved.getHeight();
        lqWidth = videoWidth;
        lqHeight = videoHeight;

        if (changed && (streamingEncoder != null || localRecorder != null)) {

            String error = restartFullPipeline(resolved);

            if (error != null)
                return error;
        }

        currentSize = resolved;

        currentFpsRange =
                highSpeedCameraHelper.getBestHighSpeedFpsRange(
                        currentCameraId,
                        currentSize,
                        videoFps);

        if (currentFpsRange == null)
            return "FPS unavailable";

        return null;
    }

    @Override
    public String SetStreamBitrate(int bitrate) {

        streamBitrateBps = bitrate;

        try {

            if (streamingEncoder != null && drainRunning) {
                streamingEncoder.setBitrate(bitrate);
            }

            return null;

        } catch (Exception e) {

            Log.e(TAG, "SetStreamBitrate failed", e);

            return "Bitrate failed";
        }
    }

    @Override
    public boolean IsStreaming() {
        return drainRunning;
    }

    @Override
    public void StreamMute(boolean mute) {
        if (streamingEncoder == null) return;
        if (mute) {
            streamingEncoder.pauseStreaming();
            cameraController.pauseStreaming();
        } else {
            cameraController.resumeStreaming();
            streamingEncoder.resumeStreaming();
        }
    }

    @Override
    public String StartRecording() {

        if (localRecorder == null)
            return "Recorder unavailable";

        if (localRecorder.isRecordingActive())
            return null;

        try {

            localRecorder.start();

            return null;

        } catch (Exception e) {

            Log.e(TAG, "StartRecording failed", e);

            return "Recorder failed";
        }
    }

    @Override
    public String SetRecordingResolution(int width, int height, int fps) {

        logger.log(TAG + ": SetRecordingResolution — not supported in high-speed mode; "
                + "use SetStreamResolution (drives both stream and record)");

        return "Not supported";
    }

    @Override
    public String SetRecordingBitrate(int bitrate) {

        if (localRecorder != null && localRecorder.isRecordingActive()) {

            logger.log(TAG + ": SetRecordingBitrate — cannot change while recording");

            return "Recording active";
        }

        recordBitrateBps = bitrate;

        return null;
    }

    @Override
    public boolean IsRecording() {
        return localRecorder != null && localRecorder.isRecordingActive();
    }

    @Override
    public String StopRecording() {

        try {

            if (localRecorder != null && localRecorder.isRecordingActive()) {
                localRecorder.stop();
            }

            return null;

        } catch (Exception e) {

            Log.e(TAG, "StopRecording failed", e);

            return "Stop failed";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Media — camera
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String SwitchCamera(int camId) {

        if (localRecorder != null && localRecorder.isRecordingActive()) {

            logger.log(TAG + ": SwitchCamera — stop recording first");

            return "Recording active";
        }

        if (highSpeedCameraHelper == null)
            return "Camera unavailable";

        try {

            boolean wasStreaming = drainRunning;

            if (wasStreaming) {
                stopDrainThread();

                if (streamingEncoder != null)
                    streamingEncoder.stop();
            }

            if (cameraController != null)
                cameraController.stop();

            highSpeedCameraHelper.invalidateCameraCache();

            currentCameraId =
                    highSpeedCameraHelper.cycleCameraId(currentCameraId);

            return SetStreamResolution(
                    videoWidth,
                    videoHeight,
                    videoFps);

        } catch (Exception e) {

            Log.e(TAG, "SwitchCamera failed", e);

            return "Camera switch failed";
        }
    }
    @Override
    public String RotateCamera() {
        return null; // implemented on host side
    }
    @Override
    public short FlipCamera() {
        return 0; // implemented on host side
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CameraControl — all no-ops / warnings in high-speed mode
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void setExposureCompensation(int value) {
        Log.w(TAG, "setExposureCompensation: not supported in high-speed mode");
    }

    @Override public void enableAutoExposure() { /* always on — no-op */ }

    @Override
    public void disableAutoExposure() {
        Log.w(TAG, "disableAutoExposure: not supported in high-speed mode");
    }

    @Override
    public void setManualExposure(long exposureTimeNs, int iso) {
        Log.w(TAG, "setManualExposure: not supported in high-speed mode");
    }

    @Override
    public void setManualFocus(float diopters) {
        Log.w(TAG, "setManualFocus: not supported in high-speed mode");
    }

    @Override public void enableAutoFocus() { /* always on — no-op */ }

    @Override public void apply() { /* no pending manual controls — no-op */ }

    private String restartFullPipeline(Size newHqSize) {

        boolean wasStreaming =
                drainRunning;

        boolean wasRecording =
                localRecorder != null
                        && localRecorder.isRecordingActive();

        try {

            if (wasStreaming) {
                stopDrainThread();

                if (streamingEncoder != null)
                    streamingEncoder.stop();
            }

            // Tear down in reverse init order

            if (glRenderer != null) {
                glRenderer.release();
                glRenderer = null;
            }

            if (cameraController != null) {
                cameraController.stop();
                cameraController = null;
            }

            if (streamingEncoder != null) {
                streamingEncoder.release();
                streamingEncoder = null;
            }

            if (localRecorder != null) {

                if (wasRecording)
                    localRecorder.stop();

                localRecorder.release();
                localRecorder = null;
            }

            currentSize = newHqSize;

            videoWidth = newHqSize.getWidth();
            videoHeight = newHqSize.getHeight();

            // Stream and record always share the same resolved size.

            lqWidth = videoWidth;
            lqHeight = videoHeight;

            currentFpsRange =
                    highSpeedCameraHelper.getBestHighSpeedFpsRange(
                            currentCameraId,
                            currentSize,
                            videoFps);

            if (currentFpsRange == null)
                return "FPS unavailable";

            localRecorder =
                    new LocalRecorder(context);

            hqSurface =
                    localRecorder.prepare(
                            videoWidth,
                            videoHeight,
                            currentFpsRange.getUpper(),
                            recordBitrateBps);

            if (hqSurface == null)
                return "Recorder failed";

            streamingEncoder =
                    new StreamingEncoder();

            lqSurface =
                    streamingEncoder.prepare(
                            lqWidth,
                            lqHeight,
                            currentFpsRange.getUpper(),
                            streamBitrateBps);

            if (lqSurface == null)
                return "Encoder failed";

            glRenderer =
                    new GlRenderer.Builder(
                            videoWidth,
                            videoHeight,
                            lqWidth,
                            lqHeight)
                            .scaleMode(
                                    GlRenderer.ScaleMode.CROP)
                            .contextLostCallback(
                                    this::onGlContextLost)
                            .build(context);

            glRenderer.init(
                    hqSurface,
                    lqSurface);

            cameraController =
                    new HighFPSCameraController(context);

            cameraController.attachCameraSurface(
                    glRenderer.getCameraSurface());

            cameraController.start(
                    currentCameraId,
                    currentSize,
                    currentFpsRange.getUpper());

            if (wasRecording)
                localRecorder.start();

            if (wasStreaming) {

                String error = StartStream();

                if (error != null)
                    return error;
            }

            return null;

        } catch (IOException e) {

            logger.log(
                    TAG + ": restartFullPipeline encoder prepare failed: "
                            + Log.getStackTraceString(e));

            return "Encoder failed";

        } catch (Exception e) {

            logger.log(
                    TAG + ": restartFullPipeline failed: "
                            + Log.getStackTraceString(e));

            return "Pipeline failed";
        }
    }
    private short restartLqOnly() {
        boolean wasStreaming = drainRunning;
        if (wasStreaming) { stopDrainThread(); if (streamingEncoder != null) streamingEncoder.stop(); }

        if (glRenderer       != null) { glRenderer.release();       glRenderer       = null; }
        if (streamingEncoder != null) { streamingEncoder.release(); streamingEncoder = null; }

        try {
            streamingEncoder = new StreamingEncoder();
            lqSurface        = streamingEncoder.prepare(
                    lqWidth, lqHeight,
                    currentFpsRange.getUpper(),
                    streamBitrateBps);
        } catch (IOException e) {
            logger.log(TAG + ": restartLqOnly encoder prepare failed: "
                    + Log.getStackTraceString(e));
            return -4;
        }

        // Rebuild GlRenderer with the same hqSurface + new lqSurface/size.
        // The camera session continues uninterrupted.
        glRenderer = new GlRenderer.Builder(videoWidth, videoHeight, lqWidth, lqHeight)
                .scaleMode(GlRenderer.ScaleMode.CROP)
                .contextLostCallback(this::onGlContextLost)
                .build(context);
        glRenderer.init(hqSurface, lqSurface);

        cameraController.updateCameraSurface(glRenderer.getCameraSurface());

        if (wasStreaming) StartStream();
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private — GlRenderer context-loss recovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by GlRenderer (on a background thread) when the EGL context is
     * lost (screen-off, GPU reset, encoder surface destruction).
     *
     * Recovery:
     *  1. Stop drain thread.
     *  2. Stop camera (holds reference to the dead SurfaceTexture).
     *  3. Full pipeline restart.
     *  4. Restore recording + streaming state.
     */
    private void onGlContextLost() {
        Log.w(TAG, "GlRenderer context lost — restarting pipeline");

        boolean wasStreaming = drainRunning;
        boolean wasRecording = localRecorder != null && localRecorder.isRecordingActive();

        if (wasStreaming) stopDrainThread();

        if (cameraController != null) { cameraController.stop(); cameraController = null; }

        // GlRenderer has already torn itself down; just clear the reference.
        glRenderer = null;

        String result = restartFullPipeline(currentSize);
        if (result != null) {
            logger.log(TAG + ": onGlContextLost — pipeline restart failed: " + result);
            return;
        }

        // restartFullPipeline() restores wasStreaming automatically.
        // wasRecording is also restored inside restartFullPipeline().
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private — drain loop
    // ─────────────────────────────────────────────────────────────────────────

    private void drainLoop() {
        while (drainRunning) {
            StreamingEncoder.EncodedFrame frame = streamingEncoder.dequeue();
            if (frame == null) continue;
            if (webSocketHandler != null) {
                // Allow ~3 seconds of stream data in WS queue; drop if behind.
                long maxQueueBytes = ((long) streamBitrateBps * 3L) >> 3;
                if (webSocketHandler.getPendingBytes() < maxQueueBytes) {
                    webSocketHandler.sendBytes(buildPacket(frame));
                }
            }
        }
    }

    private byte[] buildPacket(StreamingEncoder.EncodedFrame frame) {
        if (basePtsUs < 0) basePtsUs = frame.ptsUs;

        long pts       = frame.ptsUs - basePtsUs;
        ByteBuffer src = frame.data.duplicate();
        byte[] data    = new byte[HEADER_SIZE + src.remaining()];
        int i          = 0;

        data[i++] = (byte) (frame.keyFrame ? 1 : 0);
        for (int b = 7; b >= 0; b--) {
            data[i++] = (byte) (pts >> (b * 8));
        }
        src.get(data, i, src.remaining());
        return data;
    }

    private void stopDrainThread() {
        drainRunning = false;
        if (drainThread != null) {
            drainThread.interrupt();
            try { drainThread.join(500); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
    }
}