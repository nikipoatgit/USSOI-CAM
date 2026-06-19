package com.github.nikipo.ussoi.media.hfh264;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HFCameraController {
    private static final String TAG = "HighFPSCameraController";

    private final CameraManager cameraManager;
    private final String cameraId;
    private final StreamConfigurationMap streamConfigMap;

    private CameraDevice cameraDevice;
    private CameraConstrainedHighSpeedCaptureSession highSpeedSession;
    private Surface previewSurface;
    private Surface encoderSurface;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Size captureSize;
    private Range<Integer> targetFps;

    public HFCameraController(Context context, String cameraId) throws CameraAccessException {
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.cameraId = cameraId;

        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean supportsHighSpeed = false;
        if (caps != null) {
            for (int c : caps) {
                if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) {
                    supportsHighSpeed = true;
                    break;
                }
            }
        }
        if (!supportsHighSpeed) {
            throw new IllegalStateException("Camera " + cameraId + " does not support CONSTRAINED_HIGH_SPEED_VIDEO");
        }

        this.streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (this.streamConfigMap == null) {
            throw new IllegalStateException("No StreamConfigurationMap for camera " + cameraId);
        }

        startBackgroundThread();
    }

    private void startBackgroundThread() {
        cameraThread = new HandlerThread("HighSpeedCamThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    public void openCamera() throws CameraAccessException {
        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraDevice = camera;
                maybeStartSession();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                close();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, "Camera encountered error: " + error);
                close();
            }
        }, cameraHandler);
    }

    public void setConfig(Size size, Range<Integer> fps) {
        Size[] highSpeedSizes = streamConfigMap.getHighSpeedVideoSizes();
        boolean sizeSupported = false;
        for (Size s : highSpeedSizes) {
            if (s.equals(size)) {
                sizeSupported = true;
                break;
            }
        }
        if (!sizeSupported) {
            throw new IllegalArgumentException("Size " + size + " not in supported high-speed sizes: " + Arrays.toString(highSpeedSizes));
        }

        Range<Integer>[] fpsRangesForSize = streamConfigMap.getHighSpeedVideoFpsRangesFor(size);
        boolean fpsSupported = false;
        for (Range<Integer> r : fpsRangesForSize) {
            if (r.equals(fps)) {
                fpsSupported = true;
                break;
            }
        }
        if (!fpsSupported) {
            throw new IllegalArgumentException("FPS range " + fps + " not supported for size " + size + ". Supported: " + Arrays.toString(fpsRangesForSize));
        }

        this.captureSize = size;
        this.targetFps = fps;

        Log.d(TAG, "setConfig() size=" + size + " fps=" + fps);
    }

    public void updatePreviewSurface(Surface surface) {
        cameraHandler.post(() -> {
            if (surface == previewSurface) return;
            previewSurface = surface;
            maybeStartSession();
        });
    }

    public void updateEncoderSurface(Surface surface) {
        cameraHandler.post(() -> {
            if (surface == encoderSurface) return;
            encoderSurface = surface;
            maybeStartSession();
        });
    }

    private void maybeStartSession() {
        if (cameraDevice == null) return;
        if (captureSize == null || targetFps == null) return;
        if (previewSurface == null && encoderSurface == null) return;
        startHighSpeedSession();
    }

    private void startHighSpeedSession() {
        if (cameraDevice == null) return;

        List<Surface> outputs = new ArrayList<>(2);
        if (previewSurface != null) outputs.add(previewSurface);
        if (encoderSurface != null) outputs.add(encoderSurface);

        if (outputs.isEmpty() || outputs.size() > 2) return;

        try {
            if (highSpeedSession != null) {
                highSpeedSession.close();
                highSpeedSession = null;
            }

            cameraDevice.createConstrainedHighSpeedCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    highSpeedSession = (CameraConstrainedHighSpeedCaptureSession) session;
                    startRepeatingBurst(outputs);
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "High speed configuration failed.");
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating high speed session", e);
        }
    }

    private void startRepeatingBurst(List<Surface> outputs) {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            for (Surface s : outputs) {
                builder.addTarget(s);
            }
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps);

            List<CaptureRequest> highSpeedRequests = highSpeedSession.createHighSpeedRequestList(builder.build());
            highSpeedSession.setRepeatingBurst(highSpeedRequests, null, cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "Failed starting high speed burst requests", e);
        }
    }

    public void close() {
        if (highSpeedSession != null) {
            highSpeedSession.close();
            highSpeedSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
                cameraThread = null;
                cameraHandler = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}