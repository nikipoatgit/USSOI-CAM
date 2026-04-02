package com.github.nikipo.ussoi.media.h264;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;

import android.content.Context;
import android.content.SharedPreferences;
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

import org.json.JSONObject;

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
    private volatile long  basePtsUs    = -1;
    private static final int    HEADER_SIZE = 1 + 8; // keyFrame flag + 8-byte PTS

    // Stream config (LQ)
    private int streamWidth = 1920;
    private int streamHeight = 1080;
    private int streamFps = 20;
    private int streamBitratebps =500_000;

    // Record config (HQ)
    private int recordWidth = 1920;
    private int recordHeight = 1080;
    private int recordFps = 20;
    private int recordBitratebps =  5_000_000;
    private Range<Integer> currentFpsRange;

    // Drain thread for StreamingEncoder
    private Thread drainThread;
    private volatile boolean drainRunning = false;

    // camera Being Used
    private String currentCameraId = null;

    // Encoders
    private LocalRecorder localRecorder;
    private StreamingEncoder streamingEncoder;

    // Surfaces
    private Surface hqSurface = null; // LocalRecorder input surface
    private Surface lqSurface = null; // StreamingEncoder input surface

    // camera2 Abstraction
    private CameraController cameraController;

    @Override
    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        logger = Logging.getInstance(context);
        preferences = SaveInputFields.getInstance(context).get_shared_pref();
        cameraHelper = new CameraHelper(context);


        // Select a camera
        currentCameraId = cameraHelper.cycleCameraId(currentCameraId);
        if (currentCameraId == null){
            logger.log(TAG + " No camera available");
            return;
        }
        Size streamSize = cameraHelper.getHardwareSupportedResolution(currentCameraId, streamWidth, streamHeight);

        if (streamSize != null) {
            streamWidth = streamSize.getWidth();
            streamHeight = streamSize.getHeight();
        }

        Size recordSize = cameraHelper.getHardwareSupportedResolution(currentCameraId, recordWidth, recordHeight);
        if (recordSize != null) {
            recordWidth = recordSize.getWidth();
            recordHeight = recordSize.getHeight();
        }

        currentFpsRange  = cameraHelper.getOptimalFpsRange(currentCameraId, recordWidth, recordHeight, recordFps);


        // Connect WS
        webSocketHandler = new WebSocketHandler(context,new WebSocketHandler.MessageCallback() {
            public void onOpen() {}
            @Override
            public void onPayloadReceivedText(String payload) {
            }
            @Override
            public void onPayloadReceivedByte(byte[] payload) {
            }
            @Override
            public void onClosed() {
            }
            @Override
            public void onError(String e) {
            }
        });
        webSocketHandler.setupConnection(KEY_stream_api_path,preferences.getString(KEY_Session_KEY,"123"));

        // Set up Encoders
        try {
            // HQ MediaRecorder
            localRecorder = new LocalRecorder(context);
            hqSurface = localRecorder.prepare(
                    recordWidth, recordHeight,
                    currentFpsRange.getUpper(),
                    recordBitratebps);

            // LQ MediaCodec
            streamingEncoder = new StreamingEncoder();
            lqSurface = streamingEncoder.prepare(
                    streamWidth, streamHeight,
                    currentFpsRange.getUpper(),
                    streamBitratebps);

        } catch (IOException e) {
            logger.log(TAG + " Encoder prepare failed: " + Log.getStackTraceString(e));
            throw new RuntimeException(e);
        }

        // Attach surfaces to CameraController & start camera
        cameraController = new CameraController(context);
        cameraController.attachHQSurface(hqSurface);
        cameraController.attachLQSurface(lqSurface);
        cameraController.start(currentCameraId, currentFpsRange);
    }


    @Override
    public void stop() {
        // Stop streaming if active
        if (streamingEncoder != null && drainRunning) {
            stopDrainThread();
            streamingEncoder.stop();
            streamingEncoder.release();
            streamingEncoder = null;
        }

        // Stop recording if active
        if (localRecorder != null) {
            if (localRecorder.isRecordingActive()) localRecorder.stop();
            localRecorder.release();
            localRecorder = null;
        }

        // Stop camera
        if (cameraController != null) {
            cameraController.stop();
            cameraController = null;
        }

        // Disconnect WS
        if (webSocketHandler != null) {
            webSocketHandler.closeConnection();
            webSocketHandler = null;
        }

        hqSurface = null;
        lqSurface = null;
    }

    @Override
    public short StartStream() {
        if (streamingEncoder == null) return -1;
        if (drainRunning)            return 0; // already streaming

        // Start encoder
        streamingEncoder.start();

        // Resume camera feed to LQ surface
        cameraController.resumeStreaming();

        // Connect WebSocket
        // this method is private
        // webSocketHandler.connect();

        // Start drain thread — pulls encoded frames and sends over WS
        drainRunning = true;
        drainThread  = new Thread(() -> {
            while (drainRunning) {
                StreamingEncoder.EncodedFrame frame = streamingEncoder.dequeue();
                if (frame != null && webSocketHandler != null) {
                    webSocketHandler.connSendPayloadBytes(buildPacket(frame));
                }
            }
        }, "StreamDrainThread");
        drainThread.setDaemon(true);
        drainThread.start();

        return 0;
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


    // not possible when recording active , require full camera restart and surface attach
    @Override
    public short SetStreamResolution(int width, int height, int fps) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + " SetRecordingResolution: stop recording first");
            return -1;
        }

        streamWidth = width;
        streamHeight = height;
        streamFps = fps;

        if (streamingEncoder == null) return -3; // not yet started, values stored for next prepare

        boolean wasStreaming = drainRunning;

        // Tear down streaming encoder
        if (wasStreaming) {
            stopDrainThread();
            streamingEncoder.stop();
        }
        streamingEncoder.release();
        streamingEncoder = null;

        // Re-resolve hardware resolution
        Size resolved = cameraHelper.getHardwareSupportedResolution(currentCameraId, streamWidth, streamHeight);
        if (resolved != null) {
            streamWidth = resolved.getWidth();
            streamHeight = resolved.getHeight();
        }

        currentFpsRange = cameraHelper.getOptimalFpsRange(currentCameraId, streamWidth, streamHeight, streamFps);

        // Re-prepare encoder
        try {
            streamingEncoder = new StreamingEncoder();
            lqSurface = streamingEncoder.prepare(
                    streamWidth, streamHeight,
                    currentFpsRange.getUpper(),
                    streamBitratebps);
        } catch (IOException e) {
            logger.log(TAG + " SetStreamResolution re-prepare failed: " + Log.getStackTraceString(e));
            return -4;
        }

        // Restart camera session with new LQ surface
        cameraController.stop();
        cameraController = new CameraController(context);
        cameraController.attachHQSurface(hqSurface);
        cameraController.attachLQSurface(lqSurface);
        cameraController.start(currentCameraId, currentFpsRange);

        // Resume streaming if it was active
        if (wasStreaming) StartStream();

        return 0;
    }

    // On the Fly change To Encoder
    @Override
    public short SetStreamBitrate(int bitrate) {
        streamBitratebps = bitrate;
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

    @Override
    public short StartRecording() {
        if (localRecorder == null)               return -1;
        if (localRecorder.isRecordingActive())   return 0; // already recording
        localRecorder.start();
        return 0;
    }

    // not possible when recording active , require full camera restart and surface re attach
    @Override
    public short SetRecordingResolution(int width, int height, int fps) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + " SetRecordingResolution: stop recording first");
            return -1;
        }

        recordWidth = width;
        recordHeight = height;
        recordFps = fps;

        if (localRecorder == null) return 0; // values stored for next prepare

        // Tear down and re-prepare
        localRecorder.release();
        localRecorder = null;

        Size resolved = cameraHelper.getHardwareSupportedResolution(currentCameraId, recordWidth, recordHeight);
        if (resolved != null) {
            recordWidth = resolved.getWidth();
            recordHeight = resolved.getHeight();
        }

        try {
            localRecorder = new LocalRecorder(context);
            hqSurface = localRecorder.prepare(
                    recordWidth, recordHeight,
                    currentFpsRange.getUpper(),
                    recordBitratebps);
        } catch (IOException e) {
            logger.log(TAG + " SetRecordingResolution re-prepare failed: " + Log.getStackTraceString(e));
            return -1;
        }

        // Restart camera with new HQ surface
        cameraController.stop();
        cameraController = new CameraController(context);
        cameraController.attachHQSurface(hqSurface);
        cameraController.attachLQSurface(lqSurface);
        cameraController.start(currentCameraId, currentFpsRange);
        return 0;
    }

    @Override
    public short SetRecordingBitrate(int bitrate) {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + " SetRecordingBitrate: cannot change bitrate while recording");
            return -1;
        }
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

    @Override
    public short SwitchCamera() {
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            logger.log(TAG + " SetRecordingResolution: stop recording first");
            return -1;
        }
        if (cameraHelper == null) return -2;

        boolean wasStreaming  = drainRunning;

        // Pause / stop active sessions cleanly
        if (wasStreaming)  { stopDrainThread(); streamingEncoder.stop(); }
        if (cameraController != null) { cameraController.stop(); }

        // Invalidate cache and cycle camera
        cameraHelper.invalidateCameraCache();

        currentCameraId = cameraHelper.cycleCameraId(currentCameraId);

        return SetStreamResolution(streamWidth, streamHeight, streamFps);
    }

    @Override
    public short RotateCamera() {
        // this is implemented on host side
        return 0;
    }

    @Override
    public short FlipCamera() {
        // this is implemented on host side
        return 0;
    }

    @Override
    public JSONObject SupportedResolutions() {
        return cameraHelper.SupportedResolutions(currentCameraId);
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
