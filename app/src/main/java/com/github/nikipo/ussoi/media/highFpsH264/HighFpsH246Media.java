package com.github.nikipo.ussoi.media.highFpsH264;

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
import com.github.nikipo.ussoi.network.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.storage.logs.Logging;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file HighFpsH246Media
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

public class HighFpsH246Media implements Media, CameraControl {

    private static final String TAG         = "HighFpsH246Media";
    private static final int    HEADER_SIZE = 1 + 8; // keyFrame flag + 8-byte PTS

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private Context               context;
    private Logging               logger;
    private SharedPreferences     preferences;
    private WebSocketHandler      webSocketHandler;
    private HighSpeedCameraHelper cameraHelper;

    // -------------------------------------------------------------------------
    // Config — HQ and LQ share the same size (high-speed API requirement)
    // -------------------------------------------------------------------------

    private int videoWidth       = 1280;
    private int videoHeight      = 720;
    private int videoFps         = 60;
    private int streamBitrateBps = 500_000;
    private int recordBitrateBps = 8_000_000;

    private Size           currentSize;
    private Range<Integer> currentFpsRange;

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    private String                  currentCameraId = null;
    private HighFPSCameraController cameraController;

    // -------------------------------------------------------------------------
    // Encoders + surfaces
    // -------------------------------------------------------------------------

    private LocalRecorder    localRecorder;
    private StreamingEncoder streamingEncoder;
    private Surface          hqSurface;
    private Surface          lqSurface;

    // -------------------------------------------------------------------------
    // Drain thread
    // -------------------------------------------------------------------------

    private Thread           drainThread;
    private volatile boolean drainRunning = false;
    private long             basePtsUs    = -1;

    // =========================================================================
    // Media — lifecycle
    // =========================================================================

    @Override
    public void init(Context ctx) {
        context      = ctx.getApplicationContext();
        logger       = Logging.getInstance(context);
        preferences  = SaveInputFields.getInstance(context).get_shared_pref();
        cameraHelper = new HighSpeedCameraHelper(context);

        // Select a high-speed capable camera
        currentCameraId = cameraHelper.cycleCameraId(currentCameraId);
        if (currentCameraId == null) {
            logger.log(TAG + ": no high-speed camera available");
            return;
        }

        // Resolve hardware size (HQ == LQ, constrained by the high-speed API)
        currentSize = cameraHelper.getBestHighSpeedSize(
                currentCameraId, videoWidth, videoHeight, videoFps);
        if (currentSize == null) {
            logger.log(TAG + ": no suitable high-speed size found");
            return;
        }
        videoWidth  = currentSize.getWidth();
        videoHeight = currentSize.getHeight();

        currentFpsRange = cameraHelper.getBestHighSpeedFpsRange(
                currentCameraId, currentSize, videoFps);

        Log.d(TAG, "High-speed config: " + videoWidth + "x" + videoHeight
                + " @ " + currentFpsRange + " FPS");

        // WebSocket
        webSocketHandler = new WebSocketHandler(context, new WebSocketHandler.MessageCallback() {
            @Override public void onOpen()                        {}
            @Override public void onPayloadReceivedText(String p) {}
            @Override public void onPayloadReceivedByte(byte[] p) {}
            @Override public void onClosed()                      {}
            @Override public void onError(String e)               {}
        });
        webSocketHandler.setupConnection(
                KEY_stream_api_path,
                preferences.getString(KEY_Session_KEY, "123"));

        // Encoders — both prepared at the same resolved size
        try {
            localRecorder = new LocalRecorder(context);
            hqSurface     = localRecorder.prepare(
                    videoWidth, videoHeight,
                    currentFpsRange.getUpper(),
                    recordBitrateBps);

            streamingEncoder = new StreamingEncoder();
            lqSurface        = streamingEncoder.prepare(
                    videoWidth, videoHeight,
                    currentFpsRange.getUpper(),
                    streamBitrateBps);

        } catch (IOException e) {
            logger.log(TAG + ": encoder prepare failed: " + Log.getStackTraceString(e));
            throw new RuntimeException(e);
        }

        // Camera
        cameraController = new HighFPSCameraController(context);
        cameraController.attachHQSurface(hqSurface);
        cameraController.attachLQSurface(lqSurface);
        cameraController.start(currentCameraId, currentSize, currentFpsRange.getUpper());
    }

    @Override
    public void stop() {
        if (drainRunning) {
            stopDrainThread();
            if (streamingEncoder != null) streamingEncoder.stop();
        }

        if (streamingEncoder != null) { streamingEncoder.release(); streamingEncoder = null; }
        if (localRecorder    != null) {
            if (localRecorder.isRecordingActive()) localRecorder.stop();
            localRecorder.release();
            localRecorder = null;
        }

        if (cameraController != null) { cameraController.stop(); cameraController = null; }
        if (webSocketHandler != null) { webSocketHandler.closeConnection(); webSocketHandler = null; }

        hqSurface = null;
        lqSurface = null;
        basePtsUs = -1;
    }

    // =========================================================================
    // Media — streaming
    // =========================================================================

    @Override
    public short StartStream() {
        if (streamingEncoder == null) return -1;
        if (drainRunning)             return 0; // already streaming

        streamingEncoder.start();
        cameraController.resumeStreaming();

        basePtsUs    = -1;
        drainRunning = true;
        drainThread  = new Thread(this::drainLoop, "HighFpsStreamDrain");
        drainThread.setDaemon(true);
        drainThread.start();

        return 0;
    }

    /**
     * Changes resolution and FPS. Because HQ and LQ share one surface size,
     * this always restarts both encoders and the camera session.
     * Returns -1 if recording is active (stop recording first).
     */
    @Override
    public short SetStreamResolution(int width, int height, int fps) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SetStreamResolution — stop recording first");
            return -1;
        }

        videoWidth  = width;
        videoHeight = height;
        videoFps    = fps;

        if (streamingEncoder == null) return -3; // values stored for next init

        boolean wasStreaming = drainRunning;
        if (wasStreaming) {
            stopDrainThread();
            streamingEncoder.stop();
        }
        streamingEncoder.release();
        streamingEncoder = null;

        Size resolved = cameraHelper.getBestHighSpeedSize(
                currentCameraId, videoWidth, videoHeight, videoFps);
        if (resolved == null) {
            logger.log(TAG + ": SetStreamResolution — no suitable high-speed size");
            return -2;
        }
        currentSize = resolved;
        videoWidth  = resolved.getWidth();
        videoHeight = resolved.getHeight();

        currentFpsRange = cameraHelper.getBestHighSpeedFpsRange(
                currentCameraId, currentSize, videoFps);

        try {
            streamingEncoder = new StreamingEncoder();
            lqSurface        = streamingEncoder.prepare(
                    videoWidth, videoHeight,
                    currentFpsRange.getUpper(),
                    streamBitrateBps);
        } catch (IOException e) {
            logger.log(TAG + ": SetStreamResolution re-prepare failed: " + Log.getStackTraceString(e));
            return -4;
        }

        cameraController.stop();
        cameraController = new HighFPSCameraController(context);
        cameraController.attachHQSurface(hqSurface);
        cameraController.attachLQSurface(lqSurface);
        cameraController.start(currentCameraId, currentSize, currentFpsRange.getUpper());

        if (wasStreaming) StartStream();
        return 0;
    }

    @Override
    public short SetStreamBitrate(int bitrate) {
        streamBitrateBps = bitrate;
        if (streamingEncoder != null && drainRunning) {
            streamingEncoder.setBitrate(bitrate);
        }
        return 0;
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

    // =========================================================================
    // Media — recording
    // =========================================================================

    @Override
    public short StartRecording() {
        if (localRecorder == null)             return -1;
        if (localRecorder.isRecordingActive()) return 0; // already recording
        localRecorder.start();
        return 0;
    }

    /**
     * HQ and LQ share one surface size — changing recording resolution also
     * changes the stream resolution and restarts the camera session.
     * Returns -1 if recording is active.
     */
    @Override
    public short SetRecordingResolution(int width, int height, int fps) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SetRecordingResolution — stop recording first");
            return -1;
        }

        videoWidth  = width;
        videoHeight = height;
        videoFps    = fps;

        if (localRecorder == null) return 0; // values stored for next init

        localRecorder.release();
        localRecorder = null;

        Size resolved = cameraHelper.getBestHighSpeedSize(
                currentCameraId, videoWidth, videoHeight, videoFps);
        if (resolved == null) {
            logger.log(TAG + ": SetRecordingResolution — no suitable high-speed size");
            return -2;
        }
        currentSize = resolved;
        videoWidth  = resolved.getWidth();
        videoHeight = resolved.getHeight();

        currentFpsRange = cameraHelper.getBestHighSpeedFpsRange(
                currentCameraId, currentSize, videoFps);

        try {
            localRecorder = new LocalRecorder(context);
            hqSurface     = localRecorder.prepare(
                    videoWidth, videoHeight,
                    currentFpsRange.getUpper(),
                    recordBitrateBps);
        } catch (IOException e) {
            logger.log(TAG + ": SetRecordingResolution re-prepare failed: " + Log.getStackTraceString(e));
            return -3;
        }

        cameraController.stop();
        cameraController = new HighFPSCameraController(context);
        cameraController.attachHQSurface(hqSurface);
        cameraController.attachLQSurface(lqSurface);
        cameraController.start(currentCameraId, currentSize, currentFpsRange.getUpper());

        return 0;
    }

    @Override
    public short SetRecordingBitrate(int bitrate) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SetRecordingBitrate — cannot change while recording");
            return -1;
        }
        recordBitrateBps = bitrate;
        return 0;
    }

    @Override
    public boolean IsRecording() {
        return localRecorder != null && localRecorder.isRecordingActive();
    }

    @Override
    public void StopRecording() {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            localRecorder.stop();
        }
    }

    // =========================================================================
    // Media — camera
    // =========================================================================

    @Override
    public short SwitchCamera() {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SwitchCamera — stop recording first");
            return -1;
        }
        if (cameraHelper == null) return -2;

        boolean wasStreaming = drainRunning;
        if (wasStreaming) { stopDrainThread(); streamingEncoder.stop(); }
        if (cameraController != null) cameraController.stop();

        cameraHelper.invalidateCameraCache();
        currentCameraId = cameraHelper.cycleCameraId(currentCameraId);

        return SetStreamResolution(videoWidth, videoHeight, videoFps);
    }

    @Override
    public short RotateCamera() {
        // Implemented on host side
        return 0;
    }

    @Override
    public short FlipCamera() {
        // Implemented on host side
        return 0;
    }

    // =========================================================================
    // CameraControl
    //
    // The constrained high-speed session mandates CONTROL_AE_MODE_ON.
    // Manual exposure and focus controls are not permitted by the Camera2 API
    // in this mode and are logged + ignored.
    // =========================================================================

    @Override
    public void setExposureCompensation(int value) {
        Log.w(TAG, "setExposureCompensation: not supported in high-speed mode");
    }

    @Override public void enableAutoExposure()  { /* always on — no-op */ }

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

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void drainLoop() {
        while (drainRunning) {
            StreamingEncoder.EncodedFrame frame = streamingEncoder.dequeue();
            if (frame == null) continue;
            if (webSocketHandler != null) {
                webSocketHandler.connSendPayloadBytes(buildPacket(frame));
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
