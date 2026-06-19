package com.github.nikipo.ussoi.media.webrtc;

import static com.github.nikipo.ussoi.media.utility.CameraHelper.getOptimalFpsRange;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import com.github.nikipo.ussoi.media.h264.CameraController;
import com.github.nikipo.ussoi.media.utility.SurfaceMode;

import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;

/**
 * *****************************************************************************
 *
 * @author nikipo
 * *****************************************************************************
 * @file WebRtcVideoCapturer
 * @date 6/17/26 10:10 AM
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
public class SurfaceCapturer implements VideoCapturer {
    private static final String TAG = "SurfaceCapturer";

    private final Context context;
    private String cameraId;
    private Range<Integer> fpsRange;

    private CameraController cameraController;
    private SurfaceTextureHelper surfaceTextureHelper;
    private CapturerObserver capturerObserver;
    private Surface lqSurface;
    private Surface hqSurface;
    private SurfaceMode surfaceMode;

    public SurfaceCapturer(Context context, String cameraId, Range<Integer> fpsRange) {
        this.context = context;
        this.cameraId = cameraId;
        this.fpsRange = fpsRange;
        surfaceMode = SurfaceMode.LQ_ONLY;
    }

    public void setSurfaceMode(SurfaceMode sm){
        surfaceMode = sm;
    }

    public void setHqSurface(Surface surface){
        this.hqSurface = surface;
    }


    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper,
                           Context applicationContext,
                           CapturerObserver capturerObserver) {
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.capturerObserver = capturerObserver;
    }

    @Override
    public synchronized void startCapture(int width, int height, int frameRate) {
        Log.d(TAG, "startCapture requested: " + width + "x" + height + " @" + frameRate + "fps");

        if (surfaceTextureHelper == null) {
            throw new IllegalStateException("SurfaceTextureHelper not initialized.");
        }

        // Handle WebRTC listening setup
        surfaceTextureHelper.startListening(capturerObserver::onFrameCaptured);
        capturerObserver.onCapturerStarted(true);

        // Configure buffers and spin up camera
        setupCameraPipeline(width, height);
    }

    @Override
    public synchronized void stopCapture() throws InterruptedException {
        Log.d(TAG, "stopCapture requested");

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.stopListening();
        }

        releaseCameraPipeline();

        if (capturerObserver != null) {
            capturerObserver.onCapturerStopped();
        }
    }

    @Override
    public void changeCaptureFormat(int width, int height, int frameRate) {
        Log.d(TAG, "changeCaptureFormat requested: " + width + "x" + height + " @" + frameRate + "fps");

        if (surfaceTextureHelper == null) {
            Log.e(TAG, "SurfaceTextureHelper is null during format change.");
            throw new IllegalStateException("SurfaceTextureHelper is null during format change.");
        }

        // Offload heavy hardware reconfiguration to a background thread
        new Thread(() -> {
            synchronized (SurfaceCapturer.this) {
                try {
                    releaseCameraPipeline();

                    // Recalculate optimal target frames for the new profile
                    fpsRange = getOptimalFpsRange(context, cameraId, frameRate);

                    setupCameraPipeline(width, height);
                } catch (Exception e) {
                    Log.e(TAG, "Error adjusting hardware video format parameters: " + e.getMessage(), e);
                    throw new IllegalStateException(e);
                }
            }
        }).start();
    }

    public synchronized void switchCamera(String newCameraId,
                                          int width,
                                          int height) {
        try {
            releaseCameraPipeline();

            cameraId = newCameraId;
            fpsRange = getOptimalFpsRange(context, cameraId, fpsRange.getUpper());

            setupCameraPipeline(width, height);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Configures the texture dimensions natively inverted (height, width) to
     * align with physical camera hardware sensors and boots the engine.
     */
    private void setupCameraPipeline(int width, int height) {
        SurfaceTexture surfaceTexture = surfaceTextureHelper.getSurfaceTexture();
        if (surfaceTexture == null) {
            throw new IllegalStateException("SurfaceTexture is unavailable.");
        }

        // Apply physical orientation fix (inverted coordinates)
        // todo check if other dev support
        surfaceTextureHelper.setTextureSize(height, width);
        surfaceTexture.setDefaultBufferSize(height, width);

        lqSurface = new Surface(surfaceTexture);

        cameraController = new CameraController(context, surfaceMode);
        cameraController.setLQSurface(lqSurface);
        if (surfaceMode == SurfaceMode.LQ_AND_HQ && hqSurface != null) {
            cameraController.setHQSurface(hqSurface);
        }
        cameraController.start(cameraId, fpsRange);
    }

    /**
     * Safely tears down active streaming allocations and frees graphic handles.
     */
    private void releaseCameraPipeline() throws InterruptedException {
        if (cameraController != null) {
            cameraController.stop();
            cameraController = null;
        }

        if (lqSurface != null) {
            lqSurface.release();
            lqSurface = null;
        }

        if (surfaceMode == SurfaceMode.LQ_ONLY) {
            hqSurface = null;
        }
    }

    @Override
    public void dispose() {
        // no-op; cleanup managed explicitly in stopCapture lifecycle step
    }

    @Override
    public boolean isScreencast() {
        return false;
    }
}