package com.github.nikipo.ussoi.media.highFpsH264;

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
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file HighFPSCameraController
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

/*
 * High-speed camera controller for 60 / 120 / 240 FPS capture via
 * {@link CameraDevice#createConstrainedHighSpeedCaptureSession}.
 *
 * CRITICAL REQUIREMENTS enforced by the Camera2 high-speed API:
 *  - Both surfaces MUST be the SAME size.
 *  - Maximum 2 surfaces per session.
 *  - Only CONTROL_AE_MODE_ON is permitted; manual sensor controls are rejected.
 *  - Capture requests MUST be submitted via
 *    {@link CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}
 *    and {@link CameraConstrainedHighSpeedCaptureSession#setRepeatingBurst}.
 */
public final class HighFPSCameraController {

    private static final String TAG = "HighFPSCamera";

    private final Context context;

    private CameraDevice cameraDevice;
    private CameraConstrainedHighSpeedCaptureSession captureSession;

    private Surface hqSurface;
    private Surface lqSurface;

    private HandlerThread  cameraThread;
    private Handler        cameraHandler;
    private Range<Integer> targetRange = null;

    private List<CaptureRequest> hqOnlyRequestList;
    private List<CaptureRequest> hqLqRequestList;

    /**
     * Tracks whether the LQ surface is currently included in the repeating burst.
     * Starts false — streaming begins only after {@link #start} configures the session.
     */
    private volatile boolean isStreaming = false;

    public HighFPSCameraController(Context ctx) {
        context = ctx.getApplicationContext();
    }

    // -------------------------------------------------------------------------
    // Surface attachment
    // -------------------------------------------------------------------------

    public synchronized void attachHQSurface(Surface surface) {
        this.hqSurface = surface;
    }

    public synchronized void attachLQSurface(Surface surface) {
        this.lqSurface = surface;
    }

    // -------------------------------------------------------------------------
    // Static capability query (used by HighSpeedCameraHelper)
    // -------------------------------------------------------------------------

    /**
     * Returns high-speed capabilities for the given camera, or {@code null} on error.
     */
    public static HighSpeedCapabilities getHighSpeedCapabilities(Context ctx, String cameraId) {
        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map  = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;

            Size[] highSpeedSizes = map.getHighSpeedVideoSizes();

            HighSpeedCapabilities caps = new HighSpeedCapabilities();

            if (highSpeedSizes != null && highSpeedSizes.length > 0) {
                for (Size size : highSpeedSizes) {
                    Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
                    Log.d(TAG, "High-speed size: " + size.getWidth() + "x" + size.getHeight());
                    for (Range<Integer> r : fpsRanges) {
                        Log.d(TAG, "  FPS range: " + r.getLower() + "-" + r.getUpper());
                    }
                }
                caps.sizes     = highSpeedSizes;
                caps.supported = true;
            } else {
                Log.w(TAG, "Device does not support high-speed video");
                caps.supported = false;
            }

            return caps;

        } catch (Exception e) {
            Log.e(TAG, "getHighSpeedCapabilities failed", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Start
    // -------------------------------------------------------------------------

    /**
     * Opens the camera and creates a constrained high-speed session.
     * Both surfaces must be attached before calling this.
     *
     * @param cameraId  Camera ID to open, or {@code null} for first available.
     * @param size      Must match the size both encoders were prepared with.
     * @param targetFps Desired FPS; the best available range is selected automatically.
     */
    public synchronized void start(String cameraId, Size size, int targetFps) {
        targetRange = findBestHighSpeedRange(cameraId, size, targetFps);

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "CAMERA permission not granted");
                return;
            }

            startThread();
            manager.openCamera(cameraId, cameraCallback, cameraHandler);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Stop
    // -------------------------------------------------------------------------

    public synchronized void stop() {
        isStreaming = false;

        if (captureSession != null) { captureSession.close();  captureSession = null; }
        if (cameraDevice   != null) { cameraDevice.close();    cameraDevice   = null; }
        if (cameraThread   != null) {
            cameraThread.quitSafely();
            cameraThread  = null;
            cameraHandler = null;
        }

        hqOnlyRequestList = null;
        hqLqRequestList   = null;
    }

    // -------------------------------------------------------------------------
    // Streaming pause / resume
    // -------------------------------------------------------------------------

    /**
     * Switches to HQ-only burst — LQ encoder stops receiving frames.
     * Recording continues unaffected.
     */
    public synchronized void pauseStreaming() {
        if (captureSession == null || hqOnlyRequestList == null || !isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingBurst(hqOnlyRequestList, null, cameraHandler);
            isStreaming = false;
            Log.d(TAG, "Streaming paused (HQ only)");
        } catch (CameraAccessException e) {
            Log.e(TAG, "pauseStreaming failed", e);
        }
    }

    /**
     * Switches back to HQ + LQ burst — LQ encoder resumes receiving frames.
     */
    public synchronized void resumeStreaming() {
        if (captureSession == null || hqLqRequestList == null || isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingBurst(hqLqRequestList, null, cameraHandler);
            isStreaming = true;
            Log.d(TAG, "Streaming resumed (HQ + LQ)");
        } catch (CameraAccessException e) {
            Log.e(TAG, "resumeStreaming failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Camera callbacks
    // -------------------------------------------------------------------------

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            synchronized (HighFPSCameraController.this) {
                cameraDevice = camera;
                createHighSpeedSession();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "Camera disconnected");
            synchronized (HighFPSCameraController.this) {
                camera.close();
                cameraDevice = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: " + error);
            synchronized (HighFPSCameraController.this) {
                camera.close();
                cameraDevice = null;
            }
        }
    };

    // -------------------------------------------------------------------------
    // Session creation
    // -------------------------------------------------------------------------

    private void createHighSpeedSession() {
        try {
            List<Surface> surfaces = Arrays.asList(hqSurface, lqSurface);

            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            synchronized (HighFPSCameraController.this) {
                                captureSession = (CameraConstrainedHighSpeedCaptureSession) session;
                                try {
                                    buildRequestLists();
                                    captureSession.setRepeatingBurst(hqLqRequestList, null, cameraHandler);
                                    isStreaming = true;
                                    Log.d(TAG, "High-speed session started at " + targetRange + " FPS");
                                } catch (CameraAccessException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "High-speed session configuration failed");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds both request lists from current state.
     * Must be called from within a synchronized block with an open session.
     */
    private void buildRequestLists() throws CameraAccessException {
        // HQ + LQ (streaming ON)
        CaptureRequest.Builder hqLqBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        hqLqBuilder.addTarget(hqSurface);
        hqLqBuilder.addTarget(lqSurface);
        applyHighSpeedParams(hqLqBuilder);
        hqLqRequestList = captureSession.createHighSpeedRequestList(hqLqBuilder.build());

        // HQ only (streaming PAUSED)
        CaptureRequest.Builder hqOnlyBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        hqOnlyBuilder.addTarget(hqSurface);
        applyHighSpeedParams(hqOnlyBuilder);
        hqOnlyRequestList = captureSession.createHighSpeedRequestList(hqOnlyBuilder.build());
    }

    /**
     * Applies only the parameters permitted by the constrained high-speed API.
     * Manual AE / AF controls are not allowed in this session type.
     */
    private void applyHighSpeedParams(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange);
        builder.set(CaptureRequest.CONTROL_MODE,    CameraMetadata.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Selects the best high-speed FPS range for the given size and target.
     *
     * Scoring (higher = better):
     *  +1000  fixed range (min == max) — stable exposure, no flicker
     *  +500   exact upper-bound match to target
     *  -diff  penalty proportional to distance from target
     */
    private Range<Integer> findBestHighSpeedRange(String cameraId, Size size, int targetFps) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map  = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);

            Range<Integer> bestRange = null;
            int bestScore = Integer.MIN_VALUE;

            for (Range<Integer> range : fpsRanges) {
                int score = 0;
                if (range.getLower().equals(range.getUpper())) score += 1000;
                if (range.getUpper() == targetFps)              score += 500;
                else                                            score -= Math.abs(range.getUpper() - targetFps);

                if (score > bestScore) {
                    bestScore = score;
                    bestRange = range;
                }
            }

            if (bestRange == null) bestRange = new Range<>(60, 60);

            Log.d(TAG, "Selected high-speed range: " + bestRange
                    + " for size " + size + " (target=" + targetFps + ")");
            return bestRange;

        } catch (Exception e) {
            Log.e(TAG, "findBestHighSpeedRange failed, using 60/60 fallback", e);
            return new Range<>(60, 60);
        }
    }

    private void startThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        cameraThread  = new HandlerThread("HighSpeedCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    // -------------------------------------------------------------------------
    // Public inner class
    // -------------------------------------------------------------------------

    /** Carries the high-speed capabilities reported by the camera hardware. */
    public static class HighSpeedCapabilities {
        public boolean supported;
        public Size[]  sizes;
    }
}
