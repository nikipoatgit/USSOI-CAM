package com.github.nikipo.ussoi.media.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.Arrays;

public final class CameraController {

    private static final String TAG = "CameraController";

    private final Context context;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;

    private Surface hqSurface;
    private Surface lqSurface;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Range<Integer> targetRange = null;
    private CaptureRequest hqOnlyRequest;
    private CaptureRequest hqLqRequest;

    private volatile boolean isStreaming = false;

    private boolean        supportsManualExposure = false;
    private boolean        supportsManualFocus    = false;
    private Range<Long>    exposureTimeRange      = null;
    private Range<Integer> isoRange               = null;
    private Range<Integer> aeCompRange            = null;
    private float          minFocusDistance       = 0f;
    private int[]          availableAfModes       = null;

    private int   aeMode         = CameraMetadata.CONTROL_AE_MODE_ON;
    private int   exposureComp   = 0;
    private long  exposureTimeNs = 0L;
    private int   iso            = 0;
    private int   afMode         = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    private float focusDistance  = 0f;


    public CameraController(Context ctx) {
        context = ctx.getApplicationContext();
    }

    public synchronized void attachHQSurface(Surface surface) {
        this.hqSurface = surface;
    }

    public synchronized void attachLQSurface(Surface surface) {
        this.lqSurface = surface;
    }

    public synchronized void start(String cameraId, Range<Integer> fpsRange) {
        this.targetRange = fpsRange;

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "CAMERA permission not granted");
                return;
            }

            startThread();

            manager.openCamera(cameraId, cameraCallback, cameraHandler);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final CameraDevice.StateCallback cameraCallback =
            new CameraDevice.StateCallback() {

                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    synchronized (CameraController.this) {
                        cameraDevice = camera;
                        createSession();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Camera disconnected");
                    synchronized (CameraController.this) {
                        camera.close();
                        cameraDevice = null;
                    }
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    synchronized (CameraController.this) {
                        camera.close();
                        cameraDevice = null;
                    }
                }
            };

    private void createSession() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraDevice.getId());

            Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            supportsManualExposure = (hwLevel != null)
                    && hwLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;

            if (supportsManualExposure) {
                exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                isoRange          = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            }

            aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);

            Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minFocus != null && minFocus > 0f) {
                supportsManualFocus = true;
                minFocusDistance    = minFocus;
            }

            availableAfModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

            afMode = bestAutoAfMode();

            cameraDevice.createCaptureSession(Arrays.asList(hqSurface, lqSurface), new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            synchronized (CameraController.this) {
                                captureSession = session;
                                try {
                                    buildRequests();

                                    captureSession.setRepeatingRequest(
                                            hqLqRequest, null, cameraHandler);

                                    isStreaming = true;

                                } catch (CameraAccessException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "CaptureSession configuration failed");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void applyParams(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange);
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);

        if (aeMode == CameraMetadata.CONTROL_AE_MODE_ON) {
            if (aeCompRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        clamp(exposureComp, aeCompRange.getLower(), aeCompRange.getUpper()));
            }
        } else {
            if (supportsManualExposure && exposureTimeRange != null && isoRange != null) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                        clampLong(exposureTimeNs,
                                exposureTimeRange.getLower(),
                                exposureTimeRange.getUpper()));
                builder.set(CaptureRequest.SENSOR_SENSITIVITY,
                        clamp(iso, isoRange.getLower(), isoRange.getUpper()));
            }
        }

        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode);

        if (afMode == CameraMetadata.CONTROL_AF_MODE_OFF && supportsManualFocus) {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE,
                    clampFloat(focusDistance, 0f, minFocusDistance));
        }
    }

    private void buildRequests() throws CameraAccessException {
        CaptureRequest.Builder hqLqBuilder =cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        hqLqBuilder.addTarget(hqSurface);
        hqLqBuilder.addTarget(lqSurface);
        applyParams(hqLqBuilder);
        hqLqRequest = hqLqBuilder.build();

        CaptureRequest.Builder hqOnlyBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        hqOnlyBuilder.addTarget(hqSurface);
        applyParams(hqOnlyBuilder);
        hqOnlyRequest = hqOnlyBuilder.build();
    }

    public synchronized void pauseStreaming() {
        if (captureSession == null || hqOnlyRequest == null || !isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingRequest(hqOnlyRequest, null, cameraHandler);
            isStreaming = false;
        } catch (CameraAccessException ignored) {}
    }

    public synchronized void resumeStreaming() {
        if (captureSession == null || hqLqRequest == null || isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingRequest(hqLqRequest, null, cameraHandler);
            isStreaming = true;
        } catch (CameraAccessException ignored) {}
    }

    public synchronized void stop() {
        isStreaming = false;

        if (captureSession != null) { captureSession.close();  captureSession = null; }
        if (cameraDevice   != null) { cameraDevice.close();    cameraDevice   = null; }
        if (cameraThread   != null) { cameraThread.quitSafely(); cameraThread = null; cameraHandler = null; }

        hqOnlyRequest = null;
        hqLqRequest   = null;
    }

    private void startThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    /**
     * Sets exposure compensation steps applied on top of AE.
     * Only effective when AE is ON. Clamped to device range at apply() time.
     * Call apply() to push to the camera.
     */
    public synchronized void setExposureCompensation(int value) {
        exposureComp = value;
    }

    /**
     * Switches AE back to automatic.
     * Call apply() to push to the camera.
     */
    public synchronized void enableAutoExposure() {
        aeMode = CameraMetadata.CONTROL_AE_MODE_ON;
    }

    /**
     * Switches AE off so manual shutter/ISO values are used.
     * No-op on LEGACY hardware.
     * Call apply() to push to the camera.
     */
    public synchronized void disableAutoExposure() {
        if (!supportsManualExposure) {
            Log.w(TAG, "disableAutoExposure: not supported on LEGACY hardware");
            return;
        }
        aeMode = CameraMetadata.CONTROL_AE_MODE_OFF;
    }

    /**
     * Sets manual shutter speed (ns) and ISO.
     * Requires disableAutoExposure() first — ignored if AE is still ON.
     * Values clamped to hardware ranges at apply() time.
     * No-op on LEGACY hardware.
     * Call apply() to push to the camera.
     */
    public synchronized void setManualExposure(long exposureTimeNs, int iso) {
        if (!supportsManualExposure) {
            Log.w(TAG, "setManualExposure: not supported on LEGACY hardware");
            return;
        }
        this.exposureTimeNs = exposureTimeNs;
        this.iso            = iso;
    }

    /**
     * Sets manual focus distance in diopters (0 = infinity, max = closest point).
     * Automatically sets AF mode to OFF.
     * No-op on fixed-focus devices.
     * Call apply() to push to the camera.
     */
    public synchronized void setManualFocus(float diopters) {
        if (!supportsManualFocus) {
            Log.w(TAG, "setManualFocus: not supported on fixed-focus devices");
            return;
        }
        afMode        = CameraMetadata.CONTROL_AF_MODE_OFF;
        focusDistance = diopters;
    }

    /**
     * Switches AF back to the best continuous mode the device supports
     * (CONTINUOUS_VIDEO → CONTINUOUS_PICTURE → AUTO).
     * Call apply() to push to the camera.
     */
    public synchronized void enableAutoFocus() {
        afMode = bestAutoAfMode();
    }

    /**
     * Rebuilds both capture requests from current control state and immediately
     * pushes the active one to the camera session.
     * All control setters are pending until apply() is called.
     */
    public synchronized void apply() {
        if (captureSession == null || cameraDevice == null) {
            Log.w(TAG, "apply() called before session is ready — ignored");
            return;
        }
        try {
            buildRequests();
            captureSession.stopRepeating();
            captureSession.setRepeatingRequest(
                    isStreaming ? hqLqRequest : hqOnlyRequest,
                    null,
                    cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "apply() failed", e);
        }
    }


    /** Picks best AF mode for video from what the device actually supports. */
    private int bestAutoAfMode() {
        if (availableAfModes == null) return CameraMetadata.CONTROL_AF_MODE_AUTO;
        int[] preference = {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CameraMetadata.CONTROL_AF_MODE_AUTO
        };
        for (int preferred : preference) {
            for (int available : availableAfModes) {
                if (available == preferred) return preferred;
            }
        }
        return CameraMetadata.CONTROL_AF_MODE_AUTO;
    }

    private static int   clamp     (int   v, int   lo, int   hi) { return Math.max(lo, Math.min(hi, v)); }
    private static long  clampLong (long  v, long  lo, long  hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clampFloat(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}