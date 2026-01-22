package com.github.nikipo.ussoi.HighFpsH264;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.Arrays;
import java.util.List;

/**
 * High FPS Camera Controller for 60/120 FPS capture
 *
 * CRITICAL REQUIREMENTS:
 * - Both surfaces MUST be the SAME size
 * - Maximum 2 surfaces allowed
 * - Use createConstrainedHighSpeedCaptureSession
 */
public final class HighFPSCameraController {

    private static final String TAG = "HighFPSCamera";

    private final Context context;

    private CameraDevice cameraDevice;
    private CameraConstrainedHighSpeedCaptureSession captureSession;

    private Surface hqSurface;
    private Surface lqSurface;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Range<Integer> targetRange = null;

    private List<CaptureRequest> hqOnlyRequestList;
    private List<CaptureRequest> hqLqRequestList;
    private volatile boolean isStreaming = true;

    // High-speed video configuration
    private Size highSpeedSize;

    public HighFPSCameraController(Context ctx) {
        context = ctx.getApplicationContext();
    }

    public void attachHQSurface(Surface surface) {
        this.hqSurface = surface;
    }

    public void attachLQSurface(Surface surface) {
        this.lqSurface = surface;
    }

    /**
     * Get available high-speed sizes and FPS ranges for a camera
     */
    public static HighSpeedCapabilities getHighSpeedCapabilities(Context ctx, String cameraId) {
        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                return null;
            }

            Size[] highSpeedSizes = map.getHighSpeedVideoSizes();

            HighSpeedCapabilities caps = new HighSpeedCapabilities();

            if (highSpeedSizes != null && highSpeedSizes.length > 0) {
                for (Size size : highSpeedSizes) {
                    Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
                    Log.d(TAG, "Size: " + size.getWidth() + "x" + size.getHeight());
                    for (Range<Integer> range : fpsRanges) {
                        Log.d(TAG, "  FPS Range: " + range.getLower() + "-" + range.getUpper());
                    }
                }
                caps.sizes = highSpeedSizes;
                caps.supported = true;
            } else {
                Log.w(TAG, "Device does not support high-speed video");
                caps.supported = false;
            }

            return caps;

        } catch (Exception e) {
            Log.e(TAG, "Error checking high-speed capabilities", e);
            return null;
        }
    }

    /**
     * Find the best high-speed FPS range for a given size and target FPS
     */
    private Range<Integer> findBestHighSpeedRange(String cameraId, Size size, int targetFps) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);

            Range<Integer> bestRange = null;
            int bestScore = -1;

            for (Range<Integer> range : fpsRanges) {
                int min = range.getLower();
                int max = range.getUpper();

                int score = 0;

                // Prefer fixed ranges (min == max)
                if (min == max) {
                    score += 1000;
                }

                // Prefer exact match to target
                if (max == targetFps) {
                    score += 500;
                } else {
                    score -= Math.abs(max - targetFps);
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestRange = range;
                }
            }

            Log.d(TAG, "Selected high-speed range: " + bestRange + " for size " + size);
            return bestRange;

        } catch (Exception e) {
            Log.e(TAG, "Error finding high-speed range", e);
            return new Range<>(60, 60); // Fallback
        }
    }

    public void start(String cameraId, Size size, int targetFps) {
        this.highSpeedSize = size;
        this.targetRange = findBestHighSpeedRange(cameraId, size, targetFps);

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            startThread();
            manager.openCamera(cameraId, cameraCallback, cameraHandler);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createHighSpeedSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: " + error);
            camera.close();
        }
    };

    private void createHighSpeedSession() {
        try {
            // CRITICAL: Both surfaces for high-speed session
            List<Surface> surfaces = Arrays.asList(hqSurface, lqSurface);

            // Use createConstrainedHighSpeedCaptureSession for high FPS
            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = (CameraConstrainedHighSpeedCaptureSession) session;

                            try {
                                // ---------- HQ + LQ (streaming ON) ----------
                                CaptureRequest.Builder hqLqBuilder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                hqLqBuilder.addTarget(hqSurface);
                                hqLqBuilder.addTarget(lqSurface);
                                applyHighSpeedParams(hqLqBuilder);

                                // Create high-speed request list (REQUIRED for high FPS)
                                hqLqRequestList = captureSession.createHighSpeedRequestList(
                                        hqLqBuilder.build());

                                // ---------- HQ only (streaming PAUSED) ----------
                                CaptureRequest.Builder hqOnlyBuilder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                hqOnlyBuilder.addTarget(hqSurface);
                                applyHighSpeedParams(hqOnlyBuilder);

                                hqOnlyRequestList = captureSession.createHighSpeedRequestList(
                                        hqOnlyBuilder.build());

                                // Start with streaming ON
                                captureSession.setRepeatingBurst(
                                        hqLqRequestList,
                                        null,
                                        cameraHandler
                                );

                                isStreaming = true;
                                Log.d(TAG, "High-speed session started at " + targetRange + " FPS");

                            } catch (CameraAccessException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "High-speed session configuration failed");
                            throw new RuntimeException("High-speed session failed");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void applyHighSpeedParams(CaptureRequest.Builder builder) {
        // Set FPS range
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange);

        // Auto mode
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        // For high-speed, auto exposure should be ON
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
    }

    public void pauseStreaming() {
        if (captureSession == null || hqOnlyRequestList == null || !isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingBurst(
                    hqOnlyRequestList,
                    null,
                    cameraHandler
            );
            isStreaming = false;
            Log.d(TAG, "Streaming paused (HQ only)");
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error pausing streaming", e);
        }
    }

    public void resumeStreaming() {
        if (captureSession == null || hqLqRequestList == null || isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingBurst(
                    hqLqRequestList,
                    null,
                    cameraHandler
            );
            isStreaming = true;
            Log.d(TAG, "Streaming resumed (HQ + LQ)");
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error resuming streaming", e);
        }
    }

    public synchronized void stop() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
    }

    private void startThread() {
        cameraThread = new HandlerThread("HighSpeedCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    /**
     * Helper class to store high-speed capabilities
     */
    public static class HighSpeedCapabilities {
        public boolean supported;
        public Size[] sizes;
    }
}