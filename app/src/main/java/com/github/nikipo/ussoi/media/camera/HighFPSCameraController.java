package com.github.nikipo.ussoi.media.camera;

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

import java.util.Collections;
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
 *
 * High-speed camera controller for 60 / 120 / 240 FPS capture via
 * {@link CameraDevice#createConstrainedHighSpeedCaptureSession}.
 *
 * SINGLE-SURFACE DESIGN
 * ─────────────────────
 * Previously this controller received two surfaces (HQ + LQ) and passed both
 * directly to the constrained high-speed session.  That caused:
 *
 *   IllegalArgumentException: The 2 output surfaces must have different type
 *
 * because both were MediaCodec input surfaces (same native consumer type).
 *
 * Now the controller receives exactly ONE surface — the SurfaceTexture surface
 * owned by GlRenderer.  GlRenderer fans the frames out to the HQ and LQ
 * encoder surfaces in OpenGL ES, completely outside the Camera2 session.
 *
 * EXTERNAL BEHAVIOUR PRESERVED
 * ─────────────────────────────
 * - start() / stop()                   — unchanged
 * - pauseStreaming() / resumeStreaming() — unchanged (HQ-only / HQ+LQ bursts
 *                                         are now equivalent; the GL renderer
 *                                         controls what encoders receive frames)
 * - getHighSpeedCapabilities()          — unchanged (static, no surface involved)
 * - HighSpeedCapabilities               — unchanged
 *
 * REMOVED
 * ───────
 * - attachHQSurface() / attachLQSurface() — replaced by attachCameraSurface()
 * - hqOnlyRequestList / hqLqRequestList   — only one request list needed now
 */
public final class HighFPSCameraController {

    private static final String TAG = "HighFPSCamera";

    private final Context context;

    private CameraDevice cameraDevice;
    private CameraConstrainedHighSpeedCaptureSession captureSession;

    // The single surface that the Camera2 session targets.
    // This is the SurfaceTexture surface from GlRenderer — NOT an encoder surface.
    private Surface cameraSurface;

    private HandlerThread  cameraThread;
    private Handler        cameraHandler;
    private Range<Integer> targetRange = null;

    // Only one request list — the camera always runs at full speed.
    // Streaming pause / resume is now handled by GlRenderer (it stops pushing
    // frames to the LQ encoder surface) rather than by swapping request lists.
    private List<CaptureRequest> requestList;

    private volatile boolean isRunning = false;

    public HighFPSCameraController(Context ctx) {
        context = ctx.getApplicationContext();
    }

    // ── Surface attachment ────────────────────────────────────────────────────

    /**
     * Attaches the single camera surface.
     * Must be called before {@link #start}.
     * This should be {@code glRenderer.getCameraSurface()}.
     */
    public synchronized void attachCameraSurface(Surface surface) {
        this.cameraSurface = surface;
    }

    /**
     * Hot-swaps the camera surface while the session is running.
     * Used by {@link com.github.nikipo.ussoi.media.HFH264.HighFpsH264Media}
     * when the GlRenderer is rebuilt for a LQ-only resolution change.
     *
     * The current capture session is stopped and restarted with the new surface.
     * The camera device is NOT closed — this avoids the ~300 ms open latency.
     */
    public synchronized void updateCameraSurface(Surface newSurface) {
        this.cameraSurface = newSurface;

        if (cameraDevice == null) return; // session not running; surface stored for next start()

        // Close the current session and recreate with the new surface
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        requestList = null;
        createHighSpeedSession();
    }

    // ── Static capability query ───────────────────────────────────────────────

    /**
     * Returns high-speed capabilities for the given camera, or {@code null} on error.
     * Unchanged from the original implementation.
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

    // ── Start ─────────────────────────────────────────────────────────────────

    /**
     * Opens the camera and creates a constrained high-speed session.
     * {@link #attachCameraSurface} must be called before this.
     *
     * @param cameraId  Camera ID to open, or {@code null} for first available.
     * @param size      Must match the size the HQ encoder was prepared with.
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

    // ── Stop ──────────────────────────────────────────────────────────────────

    public synchronized void stop() {
        isRunning = false;

        if (captureSession != null) { captureSession.close();  captureSession = null; }
        if (cameraDevice   != null) { cameraDevice.close();    cameraDevice   = null; }
        if (cameraThread   != null) {
            cameraThread.quitSafely();
            cameraThread  = null;
            cameraHandler = null;
        }

        requestList = null;
    }

    // ── Streaming pause / resume ──────────────────────────────────────────────
    //
    // With a single surface the camera always runs at full speed regardless.
    // GlRenderer is responsible for suppressing frames to the LQ encoder surface
    // when streaming is "paused" (the HQ encoder always receives frames).
    //
    // These methods are kept with the same signature so HighFpsH264Media compiles
    // unchanged.  They are no-ops here; the real work happens in
    // StreamingEncoder.pauseStreaming() / resumeStreaming() which GlRenderer
    // calls through to via HighFpsH264Media.StreamMute().

    /**
     * No-op — streaming pause is handled by StreamingEncoder / GlRenderer.
     * Kept for API compatibility.
     */
    public synchronized void pauseStreaming() {
        Log.d(TAG, "pauseStreaming — delegated to StreamingEncoder/GlRenderer");
    }

    /**
     * No-op — streaming resume is handled by StreamingEncoder / GlRenderer.
     * Kept for API compatibility.
     */
    public synchronized void resumeStreaming() {
        Log.d(TAG, "resumeStreaming — delegated to StreamingEncoder/GlRenderer");
    }

    // ── Camera callbacks ──────────────────────────────────────────────────────

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

    // ── Session creation ──────────────────────────────────────────────────────

    private void createHighSpeedSession() {
        try {
            // Single surface — the GlRenderer SurfaceTexture surface.
            // This satisfies the Camera2 high-speed API which requires surfaces
            // to be of different types when two are provided; here there is only
            // one so the type check never triggers.
            List<Surface> surfaces = Collections.singletonList(cameraSurface);

            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            synchronized (HighFPSCameraController.this) {
                                captureSession = (CameraConstrainedHighSpeedCaptureSession) session;
                                try {
                                    buildRequestList();
                                    captureSession.setRepeatingBurst(requestList, null, cameraHandler);
                                    isRunning = true;
                                    Log.d(TAG, "High-speed session started at "
                                            + targetRange + " FPS (single surface)");
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
     * Builds the single capture request list targeting the camera surface.
     * Must be called from within a synchronized block with an open session.
     */
    private void buildRequestList() throws CameraAccessException {
        CaptureRequest.Builder builder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(cameraSurface);
        applyHighSpeedParams(builder);
        requestList = captureSession.createHighSpeedRequestList(builder.build());
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

    // ── Private helpers ───────────────────────────────────────────────────────

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

    // ── Public inner class ────────────────────────────────────────────────────

    /** Carries the high-speed capabilities reported by the camera hardware. */
    public static class HighSpeedCapabilities {
        public boolean supported;
        public Size[]  sizes;
    }
}