package com.github.nikipo.ussoi.media.h264;

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

    private final Context context;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;

    private Surface hqSurface;
    private Surface lqSurface;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private Range<Integer> fpsRange;

    private final SurfaceMode surfaceMode;

    public CameraController(
            Context context,
            SurfaceMode surfaceMode
    ) {
        this.context = context.getApplicationContext();
        this.surfaceMode = surfaceMode;
    }
    public synchronized void setHQSurface(Surface surface) {
        hqSurface = surface;
    }

    public synchronized void setLQSurface(Surface surface) {
        lqSurface = surface;
    }

    public synchronized void start(
            String cameraId,
            Range<Integer> fpsRange
    ) {

        this.fpsRange = fpsRange;

        startThread();

        try {

            CameraManager manager =
                    (CameraManager) context.getSystemService(
                            Context.CAMERA_SERVICE);

            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            manager.openCamera(
                    cameraId,
                    cameraCallback,
                    cameraHandler
            );

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final CameraDevice.StateCallback cameraCallback =
            new CameraDevice.StateCallback() {

                @Override
                public void onOpened(
                        @NonNull CameraDevice camera) {

                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(
                        @NonNull CameraDevice camera) {

                    stop();
                }

                @Override
                public void onError(
                        @NonNull CameraDevice camera,
                        int error) {

                    stop();
                }
            };

    private void createSession() {

        try {

            if (surfaceMode == SurfaceMode.LQ_ONLY) {

                cameraDevice.createCaptureSession(
                        Arrays.asList(lqSurface),
                        sessionCallback,
                        cameraHandler
                );

            } else {

                cameraDevice.createCaptureSession(
                        Arrays.asList(
                                hqSurface,
                                lqSurface
                        ),
                        sessionCallback,
                        cameraHandler
                );
            }

        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(
                        @NonNull CameraCaptureSession s) {

                    session = s;

                    try {

                        CaptureRequest.Builder builder =
                                cameraDevice.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD);

                        builder.addTarget(lqSurface);

                        if (surfaceMode == SurfaceMode.LQ_AND_HQ) {
                            builder.addTarget(hqSurface);
                        }

                        builder.set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                fpsRange);

                        session.setRepeatingRequest(
                                builder.build(),
                                null,
                                cameraHandler
                        );

                    } catch (CameraAccessException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onConfigureFailed(
                        @NonNull CameraCaptureSession session) {

                }
            };

    public synchronized void stop() {

        if (session != null) {
            session.close();
            session = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void startThread() {

        cameraThread =
                new HandlerThread("CameraThread");

        cameraThread.start();

        cameraHandler =
                new Handler(cameraThread.getLooper());
    }
}