package com.github.nikipo.ussoi.HighFpsH264;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.H264.LocalRecorder;
import com.github.nikipo.ussoi.H264.StreamingEncoder;

import java.io.IOException;


//HighSpeedCapabilities caps =
//        HighFPSCameraController.getHighSpeedCapabilities(context, cameraId);
//
//if (caps.supported) {
//        // Common sizes: 1280x720@120fps, 1920x1080@60fps
//        }

//HighFPSFrameStreamController controller = new HighFPSFrameStreamController(context);
//controller.setTargetFPS(60);  // or 120
//controller.init();
//controller.startStreaming();
//controller.startRecording();  // Both at same 720p resolution
public final class HighFPSFrameStreamController {

    private static final String TAG = "HighFPSStreamController";

    private final Context context;
    private HighFPSCameraController cameraController;
    private LocalRecorder localRecorder;
    private StreamingEncoder streamingEncoder;

    private String currentCameraId = null;

    // Both HQ and LQ must use SAME size for high-speed
    private int videoWidth  = 1280;
    private int videoHeight = 720;

    private int HQBitrate = 8_000_000;  // Higher bitrate for high FPS
    private int LQBitrate = 500_000;   //  bitrate for high FPS streaming

    private int targetFps = 60;  // 60, 120, or 240 depending on device
    private Size highSpeedSize;

    public HighFPSFrameStreamController(Context ctx) {
        context = ctx.getApplicationContext();
        cameraController = new HighFPSCameraController(context);
    }

    /**
     * Initialize with high-speed configuration
     */
    public synchronized void init() {
        currentCameraId = getCameraId();
        if (currentCameraId == null) {
            Log.e(TAG, "No camera available");
            return;
        }

        // Check if device supports high-speed video
        HighFPSCameraController.HighSpeedCapabilities caps =
                HighFPSCameraController.getHighSpeedCapabilities(context, currentCameraId);

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

        try {
            // BOTH encoders use SAME size for high-speed
            streamingEncoder = new StreamingEncoder();
            Surface lqSurface = streamingEncoder.prepare(
                    videoWidth, videoHeight, targetFps, LQBitrate
            );

            localRecorder = new LocalRecorder(context);
            Surface hqSurface = localRecorder.prepare(
                    videoWidth, videoHeight, targetFps, HQBitrate
            );

            cameraController.attachHQSurface(hqSurface);
            cameraController.attachLQSurface(lqSurface);

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
        if (highSpeedSize == null) {
            Log.e(TAG, "Not initialized");
            return;
        }

        cameraController.start(currentCameraId, highSpeedSize, targetFps);
        streamingEncoder.start();

        Log.d(TAG, "Started high-speed streaming");
    }

    /**
     * Start recording at high FPS
     */
    public void startRecording() {
        if (localRecorder != null) {
            localRecorder.start();
            Log.d(TAG, "Started high-speed recording");
        }
    }

    public void pauseStreaming() {
        cameraController.pauseStreaming();
    }

    public void resumeStreaming() {
        cameraController.resumeStreaming();
    }

    public void setTargetFPS(int fps) {
        this.targetFps = fps;
        // Reinitialize to apply new FPS
    }

    public synchronized void stop() {
        if (cameraController != null) {
            cameraController.stop();
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

    private String getCameraId() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            return (ids.length > 0) ? ids[0] : null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera ID", e);
            return null;
        }
    }
}
