package com.github.nikipo.ussoi.media.hfh264;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;

import com.github.nikipo.ussoi.media.utility.HFCameraHelper;

import java.util.Collections;
import java.util.List;

public class HFCameraController {
    private static final String TAG = "HighFPSCameraController";

    private final CameraManager cameraManager;
    private final String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession highSpeedSession;
    private Surface currentSurface;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Size captureSize;
    private Range<Integer> targetFps;

    public HFCameraController(Context context, String cameraId) {
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.cameraId = cameraId;
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
                if (currentSurface != null) {
                    startHighSpeedSession();
                }
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
        Log.d(TAG, "setConfig() called with: size = [" + size + "], fps = [" + fps + "]");

        this.captureSize = size;
        this.targetFps = fps;

        Log.d(TAG, "setConfig() successfully applied. Capture size set to "
                + (this.captureSize != null ? this.captureSize.toString() : "null")
                + " and FPS range set to " + this.targetFps);
    }

    public void updateCameraSurface(Surface newSurface) {
        cameraHandler.post(() -> {
            if (newSurface == currentSurface) return;
            this.currentSurface = newSurface;
            if (cameraDevice != null) {
                startHighSpeedSession();
            }
        });
    }

    private void startHighSpeedSession() {
        if (cameraDevice == null || currentSurface == null) return;

        try {
            if (highSpeedSession != null) {
                highSpeedSession.close();
                highSpeedSession = null;
            }

            List<Surface> outputs = Collections.singletonList(currentSurface);
            cameraDevice.createConstrainedHighSpeedCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    highSpeedSession = session;
                    try {
                        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(currentSurface);


                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps);

                        List<CaptureRequest> highSpeedRequests = ((CameraConstrainedHighSpeedCaptureSession) highSpeedSession)
                                .createHighSpeedRequestList(builder.build());

                        highSpeedSession.setRepeatingBurst(highSpeedRequests, null, cameraHandler);
                    } catch (CameraAccessException | IllegalArgumentException e) {
                        Log.e(TAG, "Failed starting high speed burst requests", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "High speed configuration failed.");
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error generating dynamic high speed session", e);
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