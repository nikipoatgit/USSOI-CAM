package com.github.nikipo.ussoi.media.h264;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;
import static com.github.nikipo.ussoi.ui.UssoiStrings.EMPTY;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.media.CameraControl;
import com.github.nikipo.ussoi.media.camera.CameraHelper;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.camera.CameraController;
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
 * @file H264Media
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
public class H264Media implements Media, CameraControl {
    private static final String TAG = "H264Media";
    private SharedPreferences preferences;
    private WebSocketHandler webSocketHandler;
    private CameraHelper cameraHelper;
    private Logging logger;
    private Context context;
    private volatile long basePtsUs = -1;

    // Stream config (LQ)
    private Size StreamRes;
    private int streamFps = 20;
    private int streamBitratebps = 500_000;

    // Record config (HQ)
    private Size recordRes;
    private int recordFps = 20;
    private int recordBitratebps = 5_000_000;

    private final Object lock = new Object();
    private Thread drainThread;
    private volatile boolean drainRunning = false;

    // Camera being used
    private String currentCameraId = null;

    // Encoders
    private LocalRecorder localRecorder;
    private StreamingEncoder streamingEncoder;

    // Surfaces
    private Surface hqSurface = null; // LocalRecorder input surface
    private Surface lqSurface = null; // StreamingEncoder input surface

    // camera2 abstraction
    private CameraController cameraController;
    private boolean initilised = false;
    private ByteBuffer packetBuffer = ByteBuffer.allocateDirect(1024 * 1024);

    @Override
    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        logger = Logging.getInstance(context);
        preferences = SaveInputFields.getInstance(context).get_shared_pref();
        cameraHelper = new CameraHelper(context);

        try {
            String[] ids = cameraHelper.getCameraIdList();
            if (ids == null || ids.length == 0) {
                return;
            }
            currentCameraId = ids[0];
        } catch (CameraAccessException e) {
            return;
        }

        initilised = true;
    }

    /**
     * Tears down encoders and camera session.
     * NOTE: intentionally does NOT touch webSocketHandler — callers that
     * want to keep the WS connection alive (e.g. SetStreamResolution,
     * SwitchCamera) rely on this guarantee.
     */
    private void initSurfaces() {
        basePtsUs = -1;

        synchronized (lock) {
            if (streamingEncoder != null) {
                streamingEncoder.release();
                streamingEncoder = null;
            }
            if (localRecorder != null) {
                localRecorder.release();
                localRecorder = null;
            }
        }

        hqSurface = null;
        lqSurface = null;

        synchronized (lock) {
            if (cameraController != null) {
                cameraController.stop();
                cameraController = null;
            }
        }
    }

    /**
     * Builds encoders, surfaces, and the camera session.
     * Returns true on error, false on success (keeps the original convention).
     */
    private boolean initEncoders() {
        try {
            Log.d(TAG, "initEncoders: START");

            if (StreamRes == null || recordRes == null) return true;

            streamingEncoder = new StreamingEncoder();
            localRecorder = new LocalRecorder(context);

            lqSurface = streamingEncoder.prepare(
                    StreamRes.getWidth(),
                    StreamRes.getHeight(),
                    streamFps,
                    streamBitratebps
            );
            if (lqSurface == null) return true;

            hqSurface = localRecorder.prepare(
                    recordRes.getWidth(),
                    recordRes.getHeight(),
                    recordFps,
                    recordBitratebps
            );
            if (hqSurface == null) return true;

            Log.d(TAG, "StreamRes: " + StreamRes.getWidth() + "x" + StreamRes.getHeight()
                    + " @" + streamFps + " bitrate=" + streamBitratebps);
            Log.d(TAG, "RecordRes: " + recordRes.getWidth() + "x" + recordRes.getHeight()
                    + " @" + recordFps + " bitrate=" + recordBitratebps);

            int fpsQueryFps = Math.max(recordFps, streamFps);

            cameraController = new CameraController(context);
            cameraController.attachLQSurface(lqSurface);
            if (hqSurface != null) cameraController.attachHQSurface(hqSurface);

            Range<Integer> fpsRange = cameraHelper.getOptimalFpsRange(currentCameraId, fpsQueryFps);
            if (fpsRange == null) return true;

            cameraController.start(currentCameraId, fpsRange);

        } catch (IOException e) {
            Log.e(TAG, "initEncoders failed", e);
            return true;
        }
        return false;
    }

    /**
     * Starts the encoder, resumes camera to the LQ surface, and spins up the
     * drain thread that pulls encoded frames and sends them over WebSocket.
     * Assumes the WS connection and encoder pipeline are already in place.
     */
    private void startDrainLoop() {
        streamingEncoder.start();
        cameraController.resumeStreaming();

        drainRunning = true;
        drainThread = new Thread(() -> {
            while (drainRunning) {
                StreamingEncoder.EncodedFrame frame = streamingEncoder.dequeue();
                if (frame != null && webSocketHandler != null) {
                    webSocketHandler.sendBytes(buildPacket(frame));
                }
            }
        }, "StreamDrainThread");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    // Kept for legacy callers — delegates to stopStream().
    public void close() {
        stopStream();

        synchronized (lock) {
            if (webSocketHandler != null) {
                webSocketHandler.close();
                webSocketHandler = null;
            }
        }
    }

    @Override
    public short StartStream() {
        if (drainRunning) return -2;   // already streaming
        if (StreamRes == null)  return -3;

        // Connect WebSocket
        webSocketHandler = new WebSocketHandler(context, new WebSocketHandler.MessageCallback() {
            public void onOpen() {}
            @Override public void onPayloadReceivedText(String payload) {}
            @Override public void onPayloadReceivedByte(byte[] payload) {}
            @Override public void onClosed() {}
            @Override public void onError(String e) {}
        });
        webSocketHandler.setupConnection(KEY_stream_api_path,
                preferences.getString(KEY_Session_KEY, EMPTY));

        initSurfaces();
        if (initEncoders()) return -5;

        startDrainLoop();
        return 0;
    }

    @Override
    public void stopStream() {
        synchronized (lock) {
            if (streamingEncoder != null) {
                stopDrainThread();
                streamingEncoder.stop();
                streamingEncoder.release();
                streamingEncoder = null;
            }
            if (localRecorder != null) {
                if (localRecorder.isRecordingActive()) localRecorder.stop();
                localRecorder.release();
                localRecorder = null;
            }
        }

        synchronized (lock) {
            if (cameraController != null) {
                cameraController.stop();
                cameraController = null;
            }
        }

        hqSurface = null;
        lqSurface = null;

        synchronized (lock) {
            if (webSocketHandler != null) {
                webSocketHandler.close();
                webSocketHandler = null;
            }
        }
    }

    // Packet layout:
    // Offset  Size  Description
    // 0       1     Keyframe flag (1 = keyframe, 0 = delta)
    // 1       8     Relative PTS in microseconds (big-endian)
    // 9       N     Encoded H.264 frame data
    private byte[] buildPacket(StreamingEncoder.EncodedFrame frame) {
        if (basePtsUs < 0)
            basePtsUs = frame.ptsUs;

        long pts = frame.ptsUs - basePtsUs;
        ByteBuffer src = frame.data.duplicate();
        byte[] data = new byte[1 + 8 + src.remaining()];
        int i = 0;

        data[i++] = (byte) (frame.keyFrame ? 1 : 0);
        for (int b = 7; b >= 0; b--) {
            data[i++] = (byte) (pts >> (b * 8));
        }
        src.get(data, i, src.remaining());
        return data;
    }

    /**
     * Changes stream resolution. Cannot be called while recording is active
     * because both encoders share the same camera session and surfaces must be
     * rebuilt from scratch.
     *
     * If streaming was active, the drain loop is stopped, the pipeline is
     * rebuilt, and streaming resumes automatically. The existing WebSocket
     * connection is reused — no reconnect occurs.
     */
    @Override
    public short SetStreamResolution(int width, int height, int fps) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) return -1;
        if (!cameraHelper.checkForExactSupportedResolution(currentCameraId, width, height, fps)) return -3;

        StreamRes = new Size(width, height);
        streamFps = fps;

        boolean wasStreaming = drainRunning;

        // Stop only the drain loop; initSurfaces() handles encoder + camera teardown.
        if (wasStreaming) stopDrainThread();

        initSurfaces();
        if (initEncoders()) return -5;

        // Resume streaming on the new pipeline. WS connection is still live.
        if (wasStreaming) startDrainLoop();

        return 0;
    }

    @Override
    public short SetStreamBitrate(int bitrate) {
        if (!initilised) return -4;
        streamBitratebps = bitrate;
        if (streamingEncoder != null && drainRunning) {
            streamingEncoder.setBitrate(bitrate);
            return 0;
        }
        return -1;
    }

    @Override
    public boolean IsStreaming() {
        return drainRunning;
    }

    @Override
    public void StreamMute(boolean mute) {
        if (!initilised) return;
        // Guard: pipeline may not be up if stream was never started.
        if (streamingEncoder == null || cameraController == null) return;

        if (mute) {
            cameraController.pauseStreaming();
            streamingEncoder.pauseStreaming();
        } else {
            cameraController.resumeStreaming();
            streamingEncoder.resumeStreaming();
        }
    }

    /**
     * Starts recording.
     *
     * - If the pipeline is already up (e.g. streaming is active), the
     *   localRecorder is already prepared — just start it.
     * - If the pipeline is not yet up, build it first, then start recording.
     */
    @Override
    public short StartRecording() {
        if (!initilised) return -4;
        if (recordRes == null)  return -3;

        if (localRecorder != null) {
            if (localRecorder.isRecordingActive()) return -2; // already recording
            localRecorder.start();
            return 0;
        }

        // Pipeline not yet created (no stream running) — build it now.
        initSurfaces();
        if (initEncoders()) return -5;

        localRecorder.start();
        return 0;
    }

    /**
     * Changes recording resolution. Cannot be called while recording is active
     * because surfaces must be rebuilt. Does not restart anything automatically
     * — the caller must start a new recording session after this call.
     */
    @Override
    public short SetRecordingResolution(int width, int height, int fps) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) return -1;
        if (!cameraHelper.checkForExactSupportedResolution(currentCameraId, width, height, fps)) return -3;

        recordRes = new Size(width, height);
        recordFps = fps;
        return 0;
    }

    @Override
    public short SetRecordingBitrate(int bitrate) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) return -1;
        recordBitratebps = bitrate;
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

    /**
     * Switches to a different camera by index.
     *
     * Cannot be called while recording is active (requires pipeline rebuild).
     * If streaming is currently active, the pipeline is rebuilt on the new
     * camera and streaming resumes automatically. The WS connection is reused.
     */
    @Override
    public short SwitchCamera(int camIndex) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) return -2;

        try {
            String[] ids = cameraHelper.getCameraIdList();
            if (ids == null || ids.length == 0) return -1;
            if (camIndex < 0 || camIndex >= ids.length) return -1;

            currentCameraId = ids[camIndex];

            // If streaming, restart the pipeline on the new camera.
            // WS connection is reused — no reconnect needed.
            if (drainRunning) {
                stopDrainThread();
                initSurfaces();
                if (initEncoders()) return -5;
                startDrainLoop();
            }

            return 0;

        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public short RotateCamera() {
        // Implemented on the host side.
        return 0;
    }

    @Override
    public short FlipCamera() {
        // Implemented on the host side.
        return 0;
    }

    private void stopDrainThread() {
        drainRunning = false;
        if (drainThread != null) {
            drainThread.interrupt();
            try { drainThread.join(500); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
    }

    @Override
    public void setExposureCompensation(int value) { cameraController.setExposureCompensation(value); }

    @Override
    public void enableAutoExposure() { cameraController.enableAutoExposure(); }

    @Override
    public void disableAutoExposure() { cameraController.disableAutoExposure(); }

    @Override
    public void setManualExposure(long exposureTimeNs, int iso) { cameraController.setManualExposure(exposureTimeNs, iso); }

    @Override
    public void setManualFocus(float diopters) { cameraController.setManualFocus(diopters); }

    @Override
    public void enableAutoFocus() { cameraController.enableAutoFocus(); }

    @Override
    public void apply() { cameraController.apply(); }
}