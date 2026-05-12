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
    private volatile long  basePtsUs    = -1;
    private static final int    HEADER_SIZE = 1 + 8; // keyFrame flag + 8-byte PTS

    // Stream config (LQ)
    private Size StreamRes ;
    private int streamFps = 20;
    private int streamBitratebps = 500_000;

    // Record config (HQ)
    private Size recordRes ;
    private int recordFps = 20;
    private int recordBitratebps =  5_000_000;
    private final Object lock = new Object();
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
    private boolean initilised = false ;
    private ByteBuffer packetBuffer = ByteBuffer.allocateDirect(1024 * 1024);

    @Override
    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        logger = Logging.getInstance(context);
        preferences = SaveInputFields.getInstance(context).get_shared_pref();
        cameraHelper = new CameraHelper(context);
    }

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

            Log.d(TAG, "StreamRes: " + StreamRes.getWidth() + "x" + StreamRes.getHeight() +
                    " @" + streamFps + " bitrate=" + streamBitratebps);

            Log.d(TAG, "RecordRes: " + recordRes.getWidth() + "x" + recordRes.getHeight() +
                    " @" + recordFps + " bitrate=" + recordBitratebps);

            // Pick the dominant for FPS range selection
            int fpsQueryFps = Math.max(recordFps, streamFps);

            cameraController = new CameraController(context);
            cameraController.attachLQSurface(lqSurface);
            if (hqSurface != null) cameraController.attachHQSurface(hqSurface);

            Range<Integer> fpsRange = cameraHelper.getOptimalFpsRange(currentCameraId, fpsQueryFps);

            if (fpsRange == null) return true;

            cameraController.start(currentCameraId,fpsRange);

        } catch (IOException e) {
            Log.e(TAG, "initEncoders failed", e);
            return true;
        }
        return false;
    }

    // in prev version it was decided to use close instead of stopStream this method is kept for legacy purposes
    public void close() {
       stopStream();

        synchronized (lock) {
            // Disconnect WS
            if (webSocketHandler != null) {
                webSocketHandler.close();
                webSocketHandler = null;
            }
        }

    }

    @Override
    public short StartStream() {
        if (drainRunning)  return -2; // already streaming
        if (StreamRes == null) return -3;

        // check if any camera exist
        try {
            String[] ids = cameraHelper.getCameraIdList();
            if (ids == null || ids.length == 0) {
                // LOG todo
                return -4;
            }
            currentCameraId = ids[0];
        } catch (CameraAccessException e) {
            //LOG TODO
            return -4;
        }


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

        webSocketHandler.setupConnection(KEY_stream_api_path,preferences.getString(KEY_Session_KEY,EMPTY));

        initilised = true;

        initSurfaces();
        if (initEncoders()) return -5;

        // Start encoder
        streamingEncoder.start();

        // Resume camera feed to LQ surface
        cameraController.resumeStreaming();

        // Start drain thread — pulls encoded frames and sends over WS
        drainRunning = true;
        drainThread  = new Thread(() -> {
            while (drainRunning) {
                StreamingEncoder.EncodedFrame frame = streamingEncoder.dequeue();
                if (frame != null && webSocketHandler != null) {
                    webSocketHandler.sendBytes(buildPacket(frame));
                }
            }
        }, "StreamDrainThread");
        drainThread.setDaemon(true);
        drainThread.start();

        return 0;
    }

    @Override
    public void stopStream() {
        synchronized (lock) {
            // Stop streaming if active
            if (streamingEncoder != null) {
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
        }

        synchronized (lock) {
            // Stop camera
            if (cameraController != null) {
                cameraController.stop();
                cameraController = null;
            }
        }

        hqSurface = null;
        lqSurface = null;

        synchronized (lock) {
            // Disconnect WS
            if (webSocketHandler != null) {
                webSocketHandler.close();
                webSocketHandler = null;
            }
        }
    }

    // Offset   Size    Description
    //------   ----    ------------------------
    //0        1       Keyframe flag
    //1        8       Relative timestamp (PTS)
    //9        N       Encoded video frame data
    private byte[] buildPacket(StreamingEncoder.EncodedFrame frame) {

        if (basePtsUs < 0)
            basePtsUs = frame.ptsUs;

        long pts = frame.ptsUs - basePtsUs;

        ByteBuffer src = frame.data.duplicate();

        byte[] data = new byte[1 + 8 + src.remaining()];

        int i = 0;

        data[i++] = (byte) (frame.keyFrame ? 1 : 0);

        for (int b = 7; b >= 0; b--) {
            data[i++] = (byte)(pts >> (b * 8));
        }

        src.get(data, i, src.remaining());

        return data;
    }


    // not possible when recording active , require full camera restart and surface attach
    @Override
    public short SetStreamResolution(int width, int height, int fps) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) return -1;
        if (!cameraHelper.checkForExactSupportedResolution(currentCameraId,width,height,fps)) return -3;


        StreamRes = new Size(width,height);
        streamFps = fps;

        // Tear down streaming encoder
        boolean wasStreaming = drainRunning;
        if (wasStreaming) {
            stopDrainThread();
            streamingEncoder.stop();
        }

        initSurfaces();
        if (initEncoders()) return -5;

        if (wasStreaming) StartStream();

        return 0;
    }

    // On the Fly change To Encoder
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
        if (!initilised) return -4;
        if (localRecorder == null)               return -1;
        if (localRecorder.isRecordingActive())   return -2; // already recording
        if(recordRes == null) return -3;

        initSurfaces();
        if (initEncoders()) return -5;

        localRecorder.start();
        return 0;
    }

    // not possible when recording active , require full camera restart and surface re attach
    @Override
    public short SetRecordingResolution(int width, int height, int fps) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) return -1;
        if (!cameraHelper.checkForExactSupportedResolution(currentCameraId,width,height,fps)) return -3;


        recordRes = new Size(width,height);
        recordFps = fps;
        return 0;
    }

    @Override
    public short SetRecordingBitrate(int bitrate) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) {
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
    public short SwitchCamera(int camIndex) {
        if (!initilised) return -4;
        if (localRecorder != null && localRecorder.isRecordingActive()) {
            return -2;
        }

        try {
            String[] ids = cameraHelper.getCameraIdList();

            if (ids == null || ids.length == 0) return -1;
            if (camIndex < 0 || camIndex >= ids.length) return -1;

            currentCameraId = ids[camIndex];

            return 0;

        } catch (Exception e) {
            return -1;
        }
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
