package com.github.nikipo.ussoi.media.highFpsH264;

import static com.github.nikipo.ussoi.service.control.ConnRouter.sendAck;
import static com.github.nikipo.ussoi.storage.SaveInputFields.streamingUrl;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.media.enocders.StreamingEncoder;
import com.github.nikipo.ussoi.network.WebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class HighFPSFrameStreamController {

    private static final String TAG = "HighFPSStreamController";
    private static HighFPSFrameStreamController instance;
    private final Context context;
    private HighFPSCameraController highFPSCameraController;
    private LocalRecorder localRecorder;
    private StreamingEncoder streamingEncoder;
    private WebSocketHandler ws;
    private String currentCameraId = null;

    // Both HQ and LQ must use SAME size for high-speed
    private int videoWidth = 1280;
    private int videoHeight = 720;

    private int HQBitrate = 8_000_000;  // Higher bitrate for high FPS
    private int LQBitrate = 500_000;   //  bitrate for high FPS streaming

    private int targetFps = 60;  // 60, 120, or 240 depending on device
    private Size highSpeedSize;
    private volatile boolean streaming;
    private volatile boolean initialized;
    private volatile boolean isRecording;
    private static final int HEADER_SIZE = 1 + 8;
    private long basePtsUs = -1;
    private Thread streamThread;

    private HighFPSFrameStreamController(Context ctx) {
        context = ctx.getApplicationContext();
        highFPSCameraController = new HighFPSCameraController(context);
    }

    public static HighFPSFrameStreamController getInstance(Context ctx) {
        if (instance == null) {
            instance = new HighFPSFrameStreamController(ctx);
        }
        return instance;
    }

    public synchronized void init(String sessionKey) {
        streaming = false;
        initialized = false;
        isRecording = false;
        currentCameraId = cycleCameraId(currentCameraId);
        if (currentCameraId == null) {
            Log.e(TAG, "No camera available");
            return;
        }

        // Check if device supports high-speed video
        HighFPSCameraController.HighSpeedCapabilities caps = HighFPSCameraController.getHighSpeedCapabilities(context, currentCameraId);

        if (caps == null || !caps.supported) {
            Log.e(TAG, "Device does not support high-speed video!");
            return;
        }

        // Find best high-speed size
        highSpeedSize = findBestHighSpeedSize(currentCameraId, videoWidth, videoHeight);

        if (highSpeedSize == null) {
            Log.e(TAG, "No suitable high-speed size found");
            return;
        }

        videoWidth = highSpeedSize.getWidth();
        videoHeight = highSpeedSize.getHeight();

        Log.d(TAG, "High-speed config: " + videoWidth + "x" + videoHeight + " @ " + targetFps + " FPS");

        ws = new WebSocketHandler(context, new WebSocketHandler.MessageCallback() {
            public void onOpen() {
            }

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
        ws.setupConnection(streamingUrl, sessionKey);

        initialized = true;

        try {
            // BOTH encoders use SAME size for high-speed
            streamingEncoder = new StreamingEncoder();
            Surface lqSurface = streamingEncoder.prepare(videoWidth, videoHeight, targetFps, LQBitrate);

            localRecorder = new LocalRecorder(context);
            Surface hqSurface = localRecorder.prepare(videoWidth, videoHeight, targetFps, HQBitrate);

            highFPSCameraController.attachHQSurface(hqSurface);
            highFPSCameraController.attachLQSurface(lqSurface);

        } catch (IOException e) {
            Log.e(TAG, "Error initializing encoders", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Find best available high-speed size
     */
    private Size findBestHighSpeedSize(String cameraId, int preferredWidth, int preferredHeight) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size[] highSpeedSizes = map.getHighSpeedVideoSizes();

            if (highSpeedSizes == null || highSpeedSizes.length == 0) {
                return null;
            }

            Size bestSize = null;
            int bestScore = -1;

            for (Size size : highSpeedSizes) {
                // Check if this size supports our target FPS
                Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
                boolean supportsFps = false;

                for (Range<Integer> range : fpsRanges) {
                    if (range.getUpper() >= targetFps) {
                        supportsFps = true;
                        break;
                    }
                }

                if (!supportsFps) continue;

                // Score based on proximity to preferred size
                int widthDiff = Math.abs(size.getWidth() - preferredWidth);
                int heightDiff = Math.abs(size.getHeight() - preferredHeight);
                int score = -(widthDiff + heightDiff);

                if (score > bestScore) {
                    bestScore = score;
                    bestSize = size;
                }
            }

            Log.d(TAG, "Selected high-speed size: " + bestSize);
            return bestSize;

        } catch (Exception e) {
            Log.e(TAG, "Error finding high-speed size", e);
            return null;
        }
    }

    /**
     * Start streaming at high FPS
     */
    public void startStreaming() {
        if (!initialized) {
            Log.e(TAG, "Not initialized");
            return;
        }

        highFPSCameraController.start(currentCameraId, highSpeedSize, targetFps);
        streamingEncoder.start();

        streamThread = new Thread(this::drainEncoder, "StreamDrain");
        streamThread.start();
        streaming = true;
        Log.d(TAG, "Started high-speed streaming");
    }

    private void drainEncoder() {
        while (streaming) {
            StreamingEncoder.EncodedFrame frame =
                    streamingEncoder.dequeue();

            if (frame == null) continue;

            byte[] packet = buildPacket(frame);
            ws.connSendPayloadBytes(packet);
        }
    }

    private byte[] buildPacket(StreamingEncoder.EncodedFrame frame) {

        if (basePtsUs < 0) {
            basePtsUs = frame.ptsUs;
        }

        long pts = frame.ptsUs - basePtsUs;
        ByteBuffer src = frame.data.duplicate();

        byte[] data = new byte[HEADER_SIZE + src.remaining()];
        int i = 0;

        data[i++] = (byte) (frame.keyFrame ? 1 : 0);

        for (int b = 7; b >= 0; b--) {
            data[i++] = (byte) (pts >> (b * 8));
        }

        src.get(data, i, src.remaining());
        return data;
    }

    /**
     * Start recording at high FPS
     */
    public void startRecording(int reqId) {
        if (!initialized && !streaming) {
            sendAck("nack", reqId, "Streaming and Camera Not Init");
            return;
        }
        if (localRecorder != null) {
            localRecorder.start();
            Log.d(TAG, "Started high-speed recording");
        }
        isRecording = true;
    }

    public void toggleVideo(boolean video, int reqId) {
        if (highFPSCameraController != null) {
            sendAck("ack", reqId, "Play/Pause Cmd");
            if (video) {
                streamingEncoder.resumeStreaming();
            } else {
                streamingEncoder.pauseStreaming();
            }
        }
    }

    public synchronized void toggleCamera(int reqId) {
        if (isRecording) {
            sendAck("ack", reqId, "Recording Active");
            return;
        }

        currentCameraId = cycleCameraId(currentCameraId);
        if (currentCameraId == null) return;

        if (streaming) {
            restartService();
        }
    }

    private String cycleCameraId(String currentCameraId) {
        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return null;

            List<String> highFpsCameraIds = new ArrayList<>();

            for (String cameraId : manager.getCameraIdList()) {
                if (HighFPSCameraController
                        .getHighSpeedCapabilities(context, cameraId) != null) {

                    highFpsCameraIds.add(cameraId);
                }
            }

            if (highFpsCameraIds.isEmpty()) {
                Log.w(TAG, "No camera supports high-FPS");
                return null;
            }

            // First selection
            if (currentCameraId == null) {
                return highFpsCameraIds.get(0);
            }

            int index = highFpsCameraIds.indexOf(currentCameraId);

            // If current not found or last → wrap
            if (index < 0 || index == highFpsCameraIds.size() - 1) {
                return highFpsCameraIds.get(0);
            }

            // Normal cycle
            return highFpsCameraIds.get(index + 1);

        } catch (Exception e) {
            Log.e(TAG, "Error selecting high-FPS camera", e);
            return null;
        }
    }


    public void setTargetFPS(int fps, int reqId) {
        if (!streaming) {
            sendAck("nack", reqId, "Streaming Disabled");
            return;
        }
        if (isRecording) {
            sendAck("nack", reqId, "Recording Active");
            return;
        }
        this.targetFps = fps;
        sendAck("ack", reqId, "Recording Started");
    }

    public synchronized void changeCaptureFormat(int width, int height, int fps, int reqId) {
        if (!initialized) {
            sendAck("nack", reqId, "!! Controller not initialized");
            Log.w(TAG, "Cannot change format: Controller not initialized.");
            return;
        }
        if (isRecording) {
            sendAck("nack", reqId, "Recording active");
            return;
        }

        highSpeedSize = findBestHighSpeedSize(currentCameraId, width, height);

        if (highSpeedSize == null) {
            Log.e(TAG, "No suitable high-speed size found");
            sendAck("nack", reqId, "No suitable high-speed size found");
            return;
        }

        videoWidth = highSpeedSize.getWidth();
        videoHeight = highSpeedSize.getHeight();

        restartService();
        sendAck("ack", reqId, "High FPS Stream Resolution Changed ");
    }

    public boolean isRecordingActive() {
        return localRecorder.isRecordingActive();
    }

    private void restartService() {

        streaming = false;
        isRecording = false;

        Thread t = streamThread;
        streamThread = null;

        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }

        synchronized (this) {

            streaming = false;

            highFPSCameraController.stop();

            if (streamingEncoder != null) {
                streamingEncoder.stop();
                streamingEncoder.release();
            }

            if (localRecorder != null) {
                if (isRecording) {
                    localRecorder.stop();
                }
                localRecorder.release();
                localRecorder = null;
            }

            highFPSCameraController = new HighFPSCameraController(context);

            try {
                streamingEncoder = new StreamingEncoder();

                Surface lqSurface = streamingEncoder.prepare(videoWidth, videoHeight, targetFps, LQBitrate);

                localRecorder = new LocalRecorder(context);
                Surface hqSurface = localRecorder.prepare(videoWidth, videoHeight, targetFps, HQBitrate);

                highFPSCameraController.attachHQSurface(hqSurface);
                highFPSCameraController.attachLQSurface(lqSurface);

            } catch (IOException e) {
//                logger.log(TAG + e);
                throw new RuntimeException(e);
            }


            basePtsUs = -1;
            streaming = true;
            initialized = true;
            isRecording = false;

            highFPSCameraController.start(currentCameraId, highSpeedSize, targetFps);
            streamingEncoder.start();

            streamThread = new Thread(this::drainEncoder, "StreamDrain");
            streamThread.start();
        }
    }

    public synchronized void stop() {
        streaming = false;
        initialized = false;
        isRecording = false;
        if (streamThread != null) {
            try {
                streamThread.join();
            } catch (InterruptedException e) {
//                logger.log(TAG + " join interrupted " + e);
            }
            streamThread = null;
        }

        if (highFPSCameraController != null) {
            highFPSCameraController.stop();
        }

        if (streamingEncoder != null) {
            streamingEncoder.stop();
            streamingEncoder.release();
            streamingEncoder = null;
        }

        if (localRecorder != null) {
            localRecorder.stop();
            localRecorder.release();
            localRecorder = null;
        }

        Log.d(TAG, "Stopped");
    }


    public void ChangeFrameInterval(int frameIntervalMs) {
    }

    public void setVideoBitrate(int bitrate, int reqId) {
        if (initialized && streaming && streamingEncoder != null) {
            LQBitrate = bitrate;
            streamingEncoder.setBitrate(bitrate);
            sendAck("ack", reqId, "Stream Bitrate Cmd ");
        }
    }
}
