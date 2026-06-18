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

import com.github.nikipo.ussoi.media.utility.SurfaceMode;

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

    private volatile boolean closed = true;

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

        closed = false;
        this.fpsRange = fpsRange;

        startThread();

        try {

            CameraManager manager =
                    (CameraManager) context.getSystemService(
                            Context.CAMERA_SERVICE);

            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(
                    cameraId,
                    cameraCallback,
                    cameraHandler
            );

        } catch (Exception e) {

            try {
                stop();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }

            throw new RuntimeException(e);
        }
    }

    private final CameraDevice.StateCallback cameraCallback =
            new CameraDevice.StateCallback() {

                @Override
                public void onOpened(
                        @NonNull CameraDevice camera
                ) {

                    if (closed) {
                        camera.close();
                        return;
                    }

                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(
                        @NonNull CameraDevice camera
                ) {

                    closed = true;

                    camera.close();

                    if (session != null) {
                        session.close();
                        session = null;
                    }

                    cameraDevice = null;
                }

                @Override
                public void onError(
                        @NonNull CameraDevice camera,
                        int error
                ) {

                    closed = true;

                    camera.close();

                    if (session != null) {
                        session.close();
                        session = null;
                    }

                    cameraDevice = null;
                }
            };

    private void createSession() {

        if (closed || cameraDevice == null) {
            return;
        }

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
                        @NonNull CameraCaptureSession s
                ) {

                    if (closed) {
                        s.close();
                        return;
                    }

                    session = s;

                    try {

                        CaptureRequest.Builder builder =
                                cameraDevice.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD
                                );

                        builder.addTarget(lqSurface);

                        if (surfaceMode ==
                                SurfaceMode.LQ_AND_HQ) {
                            builder.addTarget(hqSurface);
                        }

                        builder.set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                fpsRange
                        );

                        session.setRepeatingRequest(
                                builder.build(),
                                null,
                                cameraHandler
                        );

                    } catch (CameraAccessException e) {

                        try {
                            stop();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }

                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onConfigureFailed(
                        @NonNull CameraCaptureSession session
                ) {

                    session.close();
                }
            };

    public synchronized void stop()
            throws InterruptedException {

        if (closed) {
            return;
        }

        closed = true;

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

            if (Thread.currentThread() != cameraThread) {
                cameraThread.join();
            }

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