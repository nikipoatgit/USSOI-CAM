package com.github.nikipo.ussoi.media.HFH264;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.media.CameraControl;
import com.github.nikipo.ussoi.media.camera.GlRenderer;
import com.github.nikipo.ussoi.media.camera.HighSpeedCameraHelper;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.camera.HighFPSCameraController;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.media.enocders.StreamingEncoder;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.storage.logs.Logging;

import org.json.JSONObject;

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
 * PIPELINE
 * ────────
 *
 *   Camera (constrained high-speed — ONE surface only)
 *         │
 *         ▼
 *   GlRenderer  (SurfaceTexture → OpenGL ES → two EGLSurfaces)
 *    ┌────┴──────────────────┐
 *    ▼                       ▼
 *  HQ EGLSurface          LQ EGLSurface
 *  (videoWidth × videoHeight)  (lqWidth × lqHeight, CROP by default)
 *    │                       │
 *    ▼                       ▼
 *  LocalRecorder          StreamingEncoder
 *  (MediaRecorder/muxer)  (MediaCodec → drain thread → WebSocket)
 *
 * External behaviour is identical to the previous dual-surface design.
 * The GlRenderer is transparent to all callers of Media / CameraControl.
 */
public class HighFpsH264Media implements Media, CameraControl {

    private static final String TAG = "HighFpsH246Media";
    private static final int    HEADER_SIZE = 1 + 8; // keyFrame flag + 8-byte PTS

    private Context               context;
    private Logging               logger;
    private SharedPreferences     preferences;
    private WebSocketHandler      webSocketHandler;
    private HighSpeedCameraHelper highSpeedCameraHelper;

    // ── Encoder config ────────────────────────────────────────────────────────
    // HQ size = hardware-resolved high-speed size (recording).
    // LQ size = streaming resolution; starts identical to HQ — GlRenderer
    //           handles downscale via GL if they later diverge.
    // Both default to 720p so the initial pipeline is symmetric.
    private int videoWidth       = 1280;   // HQ width  (resolved from hardware)
    private int videoHeight      = 720;    // HQ height (resolved from hardware)
    private int videoFps         = 60;
    private int lqWidth          = 1280;   // LQ streaming width  (matches HQ at init)
    private int lqHeight         = 720;    // LQ streaming height (matches HQ at init)
    private int streamBitrateBps = 500_000;    //  0.5 Mbps streaming
    private int recordBitrateBps = 5_000_000;  // 5 Mbps recording

    private Size           currentSize;
    private Range<Integer> currentFpsRange;

    // ── Camera ────────────────────────────────────────────────────────────────
    private String                  currentCameraId = null;
    private HighFPSCameraController cameraController;

    // ── GL renderer ───────────────────────────────────────────────────────────
    // Owns the SurfaceTexture the camera writes into, and renders each frame
    // to hqSurface and lqSurface via OpenGL ES.
    private GlRenderer glRenderer;

    // ── Encoders + surfaces ───────────────────────────────────────────────────
    private LocalRecorder    localRecorder;
    private StreamingEncoder streamingEncoder;
    private Surface          hqSurface;   // MediaRecorder input surface (HQ)
    private Surface          lqSurface;   // MediaCodec  input surface (LQ)

    // ── Drain thread ──────────────────────────────────────────────────────────
    private Thread           drainThread;
    private volatile boolean drainRunning = false;
    private long             basePtsUs    = -1;

    // ── Media — lifecycle ─────────────────────────────────────────────────────

    @Override
    public void init(Context ctx) {
        context      = ctx.getApplicationContext();
        logger       = Logging.getInstance(context);
        preferences  = SaveInputFields.getInstance(context).get_shared_pref();
        highSpeedCameraHelper = new HighSpeedCameraHelper(context);

        // Select a high-speed capable camera
        currentCameraId = highSpeedCameraHelper.cycleCameraId(currentCameraId);
        if (currentCameraId == null ) {
            logger.log(TAG + ": no high-speed camera available");
            return;
        }

        // Resolve hardware size from the high-speed camera API
        currentSize = highSpeedCameraHelper.getBestHighSpeedSize(
                currentCameraId, videoWidth, videoHeight, videoFps);
        if (currentSize == null) {
            logger.log(TAG + ": no suitable high-speed size found");
            return;
        }
        videoWidth  = currentSize.getWidth();
        videoHeight = currentSize.getHeight();

        // LQ starts at the same hardware-resolved size as HQ.
        // SetStreamResolution() can lower it independently later.
        lqWidth  = videoWidth;
        lqHeight = videoHeight;

        currentFpsRange = highSpeedCameraHelper.getBestHighSpeedFpsRange(
                currentCameraId, currentSize, videoFps);

        Log.d(TAG, "High-speed config: " + videoWidth + "x" + videoHeight
                + " @ " + currentFpsRange + " FPS"
                + "  LQ=" + lqWidth + "x" + lqHeight);

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

        // Encoders
        // HQ encoder: full hardware-resolved size (for recording)
        // LQ encoder: independent streaming size   (GlRenderer downscales in GL)
        try {
            localRecorder = new LocalRecorder(context);
            hqSurface     = localRecorder.prepare(
                    videoWidth, videoHeight,
                    currentFpsRange.getUpper(),
                    recordBitrateBps);

            streamingEncoder = new StreamingEncoder();
            lqSurface        = streamingEncoder.prepare(
                    lqWidth, lqHeight,
                    currentFpsRange.getUpper(),
                    streamBitrateBps);

        } catch (IOException e) {
            logger.log(TAG + ": encoder prepare failed: " + Log.getStackTraceString(e));
            throw new RuntimeException(e);
        }

        // GlRenderer — sits between camera and both encoder surfaces.
        // The camera session only sees ONE surface (the SurfaceTexture inside
        // GlRenderer), which eliminates the "same surface type" crash.
        glRenderer = new GlRenderer.Builder(videoWidth, videoHeight, lqWidth, lqHeight)
                .scaleMode(GlRenderer.ScaleMode.CROP)
                .contextLostCallback(this::onGlContextLost)
                .build(context);
        glRenderer.init(hqSurface, lqSurface);

        // Camera — attach ONLY the GlRenderer's camera surface (one surface only)
        cameraController = new HighFPSCameraController(context);
        cameraController.attachCameraSurface(glRenderer.getCameraSurface());
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

        // Release GlRenderer AFTER encoders are stopped (encoder surfaces must
        // outlive the EGLSurfaces that wrap them)
        if (glRenderer != null) { glRenderer.release(); glRenderer = null; }

        if (cameraController != null) { cameraController.stop(); cameraController = null; }
        if (webSocketHandler != null) { webSocketHandler.closeConnection(); webSocketHandler = null; }

        hqSurface = null;
        lqSurface = null;
        basePtsUs = -1;
    }

    // ── Media — streaming ─────────────────────────────────────────────────────

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
     * Changes the streaming resolution and FPS.
     *
     * The requested (width, height) is used as the LQ streaming size directly;
     * GlRenderer downscales the HQ camera feed to it via GL.
     *
     * The HQ hardware size is re-resolved from the high-speed API for the new
     * FPS target — if it changes, the camera session and both encoders are fully
     * restarted.  If the HQ size is unchanged only the streaming encoder and
     * GlRenderer are rebuilt (camera keeps running).
     *
     * Returns -1 if recording is active (stop recording first).
     */
    @Override
    public short SetStreamResolution(int width, int height, int fps) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SetStreamResolution — stop recording first");
            return -1;
        }

        // Resolve the HQ hardware size for the new FPS target
        Size resolved = highSpeedCameraHelper.getBestHighSpeedSize(
                currentCameraId, videoWidth, videoHeight, fps);
        if (resolved == null) {
            logger.log(TAG + ": SetStreamResolution — no suitable high-speed size");
            return -2;
        }

        boolean hqChanged = !resolved.equals(currentSize) || fps != videoFps;

        // Store new LQ streaming dimensions and fps
        videoFps = fps;
        lqWidth  = width;
        lqHeight = height;

        if (hqChanged) {
            // Full pipeline restart (HQ hardware size or FPS changed)
            return restartFullPipeline(resolved);
        } else {
            // LQ-only restart (HQ size unchanged — camera keeps running)
            return restartLqOnly();
        }
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

    // ── Media — recording ─────────────────────────────────────────────────────

    @Override
    public short StartRecording() {
        if (localRecorder == null)             return -1;
        if (localRecorder.isRecordingActive()) return 0;
        localRecorder.start();
        return 0;
    }

    /**
     * Changes the recording (HQ) resolution.
     *
     * Allowed only when recording is not active — the MediaRecorder surface
     * cannot be reconfigured mid-recording.  The full pipeline is restarted
     * because the HQ encoder size is tied to the camera session size.
     *
     * Returns -1 if recording is currently active (call StopRecording first).
     * Returns -2 if no suitable high-speed size is found for the request.
     */
    @Override
    public short SetRecordingResolution(int width, int height, int fps) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SetRecordingResolution — stop recording first");
            return -1;
        }

        // Resolve the hardware size for the requested HQ dimensions + FPS
        Size resolved = highSpeedCameraHelper.getBestHighSpeedSize(
                currentCameraId, width, height, fps);
        if (resolved == null) {
            logger.log(TAG + ": SetRecordingResolution — no suitable high-speed size");
            return -2;
        }

        videoFps    = fps;
        videoWidth  = width;
        videoHeight = height;

        // Full pipeline restart — HQ encoder and camera session must be rebuilt
        return restartFullPipeline(resolved);
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

    // ── Media — camera ────────────────────────────────────────────────────────

    @Override
    public short SwitchCamera() {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + ": SwitchCamera — stop recording first");
            return -1;
        }
        if (highSpeedCameraHelper == null) return -2;

        boolean wasStreaming = drainRunning;
        if (wasStreaming) { stopDrainThread(); if (streamingEncoder != null) streamingEncoder.stop(); }
        if (cameraController != null) cameraController.stop();

        highSpeedCameraHelper.invalidateCameraCache();
        currentCameraId = highSpeedCameraHelper.cycleCameraId(currentCameraId);

        return SetStreamResolution(videoWidth, videoHeight, videoFps);
    }

    @Override
    public short RotateCamera() {
        return 0; // implemented on host side
    }

    @Override
    public short FlipCamera() {
        return 0; // implemented on host side
    }

    @Override
    public JSONObject SupportedResolutions() {
        return highSpeedCameraHelper.SupportedResolutions(currentCameraId);
    }

    // ── CameraControl — all no-ops / warnings in high-speed mode ─────────────

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

    // ── Private — pipeline restart helpers ───────────────────────────────────

    /**
     * Full restart: new HQ size → camera session + both encoders + GlRenderer
     * all torn down and rebuilt.
     */
    private short restartFullPipeline(Size newHqSize) {
        boolean wasStreaming = drainRunning;
        if (wasStreaming) { stopDrainThread(); streamingEncoder.stop(); }

        // Tear down in reverse init order
        if (glRenderer       != null) { glRenderer.release();       glRenderer       = null; }
        if (cameraController != null) { cameraController.stop();    cameraController = null; }
        if (streamingEncoder != null) { streamingEncoder.release(); streamingEncoder = null; }
        if (localRecorder    != null) {
            if (localRecorder.isRecordingActive()) localRecorder.stop();
            localRecorder.release();
            localRecorder = null;
        }

        currentSize = newHqSize;
        videoWidth  = newHqSize.getWidth();
        videoHeight = newHqSize.getHeight();
        currentFpsRange = highSpeedCameraHelper.getBestHighSpeedFpsRange(
                currentCameraId, currentSize, videoFps);

        try {
            localRecorder = new LocalRecorder(context);
            hqSurface     = localRecorder.prepare(
                    videoWidth, videoHeight,
                    currentFpsRange.getUpper(),
                    recordBitrateBps);

            streamingEncoder = new StreamingEncoder();
            lqSurface        = streamingEncoder.prepare(
                    lqWidth, lqHeight,
                    currentFpsRange.getUpper(),
                    streamBitrateBps);
        } catch (IOException e) {
            logger.log(TAG + ": restartFullPipeline encoder prepare failed: "
                    + Log.getStackTraceString(e));
            return -4;
        }

        glRenderer = new GlRenderer.Builder(videoWidth, videoHeight, lqWidth, lqHeight)
                .scaleMode(GlRenderer.ScaleMode.CROP)
                .contextLostCallback(this::onGlContextLost)
                .build(context);
        glRenderer.init(hqSurface, lqSurface);

        cameraController = new HighFPSCameraController(context);
        cameraController.attachCameraSurface(glRenderer.getCameraSurface());
        cameraController.start(currentCameraId, currentSize, currentFpsRange.getUpper());

        return 0;
    }

    /**
     * LQ-only restart: HQ size unchanged → camera and recording encoder keep
     * running.  Only the streaming encoder and GlRenderer LQ surface are
     * rebuilt.
     */
    private short restartLqOnly() {
        boolean wasStreaming = drainRunning;
        if (wasStreaming) { stopDrainThread(); streamingEncoder.stop(); }

        // Release old GL renderer and LQ encoder
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

        // Rebuild GlRenderer with same HQ surface but new LQ surface/size.
        // The camera session continues uninterrupted; we just swap what the
        // GlRenderer renders into.
        glRenderer = new GlRenderer.Builder(videoWidth, videoHeight, lqWidth, lqHeight)
                .scaleMode(GlRenderer.ScaleMode.CROP)
                .contextLostCallback(this::onGlContextLost)
                .build(context);
        glRenderer.init(hqSurface, lqSurface);

        // Update the single surface the camera is targeting
        cameraController.updateCameraSurface(glRenderer.getCameraSurface());

        if (wasStreaming) StartStream();
        return 0;
    }

    // ── Private — GlRenderer context-loss recovery ────────────────────────────

    /**
     * Called by GlRenderer (on a background thread) when the EGL context is
     * lost due to screen-off, GPU reset, or encoder surface destruction.
     *
     * Recovery strategy:
     *  1. Stop the drain thread (streaming encoder is no longer receiving frames).
     *  2. Stop the camera session (it was writing to the now-dead SurfaceTexture).
     *  3. Restart the full pipeline so a fresh EGL context is created.
     *
     * Recording state is preserved — if recording was active it will resume
     * after the pipeline is back up.
     */
    private void onGlContextLost() {
        Log.w(TAG, "GlRenderer context lost — restarting pipeline");

        boolean wasStreaming  = drainRunning;
        boolean wasRecording  = localRecorder != null && localRecorder.isRecordingActive();

        // Stop streaming drain immediately
        if (wasStreaming) stopDrainThread();

        // Stop camera (it holds a reference to the dead SurfaceTexture)
        if (cameraController != null) { cameraController.stop(); cameraController = null; }

        // glRenderer has already torn itself down; just null the reference
        glRenderer = null;

        // Restart full pipeline
        Size currentResolved = currentSize;
        short result = restartFullPipeline(currentResolved);
        if (result != 0) {
            logger.log(TAG + ": onGlContextLost — pipeline restart failed: " + result);
            return;
        }

        // Re-apply recording state
        if (wasRecording && localRecorder != null) localRecorder.start();

        // re-apply streaming state
        if (wasStreaming) StartStream();
    }

    // ── Private — drain loop ──────────────────────────────────────────────────

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