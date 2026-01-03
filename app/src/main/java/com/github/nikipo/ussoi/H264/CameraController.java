package com.github.nikipo.ussoi.H264;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.HandlerThread;
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
    private volatile boolean isStreaming = true;


    public CameraController(Context ctx) {
        context = ctx.getApplicationContext();
    }
    public void attachHQSurface(Surface surface) {
        this.hqSurface = surface;
    }
    public void attachLQSurface(Surface surface) {
        this.lqSurface = surface;
    }

    public void start(String cameraId,Range<Integer> fpsRange ) {
        this.targetRange = fpsRange;

        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);


            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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
                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                }
            };

    private void createSession() {

        try {
            cameraDevice.createCaptureSession(
                    Arrays.asList(hqSurface, lqSurface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(
                                @NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                // ---------- HQ + LQ (streaming ON) ----------
                                CaptureRequest.Builder hqLqBuilder =
                                        cameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_RECORD);
                                hqLqBuilder.addTarget(hqSurface);
                                hqLqBuilder.addTarget(lqSurface);
                                applyParams(hqLqBuilder);
                                hqLqRequest = hqLqBuilder.build();

                                // ---------- HQ only (streaming PAUSED) ----------
                                CaptureRequest.Builder hqOnlyBuilder =
                                        cameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_RECORD);
                                hqOnlyBuilder.addTarget(hqSurface);
                                applyParams(hqOnlyBuilder);
                                hqOnlyRequest = hqOnlyBuilder.build();

                                // Start with streaming ON
                                captureSession.setRepeatingRequest(
                                        hqLqRequest,
                                        null,
                                        cameraHandler
                                );

                                isStreaming = true;

                            } catch (CameraAccessException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession session) {
                            throw new RuntimeException("Session failed");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }
    private void applyParams(CaptureRequest.Builder builder) {
        builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                targetRange
        );
        builder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO
        );
    }

    public void pauseStreaming() {
        if (captureSession == null || hqOnlyRequest == null || !isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingRequest(
                    hqOnlyRequest,
                    null,
                    cameraHandler
            );
            isStreaming = false;
        } catch (CameraAccessException ignored) {}
    }
    public void resumeStreaming() {
        if (captureSession == null || hqLqRequest == null || isStreaming) return;
        try {
            captureSession.stopRepeating();
            captureSession.setRepeatingRequest(
                    hqLqRequest,
                    null,
                    cameraHandler
            );
            isStreaming = true;
        } catch (CameraAccessException ignored) {}
    }
    public synchronized void stop() {
        if (captureSession != null) captureSession.close();
        if (cameraDevice != null) cameraDevice.close();
        if (cameraThread != null) cameraThread.quitSafely();
    }

    private void startThread() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }
}
