package com.github.nikipo.ussoi.media.hfh264;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * *****************************************************************************
 *
 * @author nikipo
 * *****************************************************************************
 * @file TextureviewHelper
 * @date 6/19/26 12:34 PM
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
public class TextureviewHelper {

    public interface SnapshotCallback {
        void onSnapshotAvailable(byte[] jpegData, long timestamp);
    }

    private final TextureView previewTextureView;
    private final int width;
    private final int height;
    private final int fps;
    private int jpegQuality;
    private final SnapshotCallback callback;

    private Surface streamSurface;
    private Bitmap snapshotBitmap;    // Main thread uses this to grab pixels
    private Bitmap processingBitmap;  // Background thread uses this to compress safely

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread snapshotThread;
    private Handler snapshotHandler;
    private volatile boolean snapshotLoopRunning = false;
    private final AtomicBoolean isCompressing = new AtomicBoolean(false);
    private long nextFrameTimeMs = 0;

    public TextureviewHelper(TextureView textureView, int width, int height, int fps, int jpegQuality, SnapshotCallback callback) {
        if (textureView == null) {
            throw new IllegalStateException("TextureView not initialized");
        }
        this.previewTextureView = textureView;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.jpegQuality = jpegQuality;
        this.callback = callback;
    }

    public Surface start() {
        if (!previewTextureView.isAvailable()) {
            throw new IllegalStateException("Preview TextureView surface not yet available");
        }

        SurfaceTexture surfaceTexture = previewTextureView.getSurfaceTexture();
        if (surfaceTexture == null) {
            throw new IllegalStateException("SurfaceTexture is null");
        }
        surfaceTexture.setDefaultBufferSize(width, height);
        streamSurface = new Surface(surfaceTexture);

        // need cause camera orientation is portrait
        snapshotBitmap = Bitmap.createBitmap(height, width, Bitmap.Config.ARGB_8888);
        processingBitmap = Bitmap.createBitmap(height, width, Bitmap.Config.ARGB_8888);

        snapshotThread = new HandlerThread("StreamSnapshotPoller");
        snapshotThread.start();
        snapshotHandler = new Handler(snapshotThread.getLooper());

        snapshotLoopRunning = true;
        isCompressing.set(false);

        nextFrameTimeMs = SystemClock.uptimeMillis();
        scheduleNextSnapshot();

        return streamSurface;
    }

    private void scheduleNextSnapshot() {
        if (!snapshotLoopRunning || snapshotHandler == null) return;

        long frameIntervalMs = 1000L / fps;
        nextFrameTimeMs += frameIntervalMs;

        long now = SystemClock.uptimeMillis();
        if (nextFrameTimeMs < now) {
            nextFrameTimeMs = now + frameIntervalMs;
        }
        snapshotHandler.postAtTime(this::triggerMainThreadCapture, nextFrameTimeMs);
    }

    private void triggerMainThreadCapture() {
        if (!snapshotLoopRunning) return;

        scheduleNextSnapshot();

        if (isCompressing.get()) {
            return;
        }

        mainHandler.post(() -> {
            if (!snapshotLoopRunning || !previewTextureView.isAvailable()) {
                return;
            }

            Bitmap captured = previewTextureView.getBitmap(snapshotBitmap);
            if (captured != null) {
                snapshotBitmap = captured;

                // Atomically lock compression state before delegating heavy work
                if (isCompressing.compareAndSet(false, true)) {
                    if (snapshotHandler != null) {
                        // Crucial thread-safety: copy pixels to processingBitmap
                        // so main thread can reuse snapshotBitmap on the next loop safely.
                        synchronized (this) {
                            if (processingBitmap != null && snapshotBitmap != null) {
                                processingBitmap.eraseColor(0);
                                android.graphics.Canvas canvas = new android.graphics.Canvas(processingBitmap);
                                canvas.drawBitmap(snapshotBitmap, 0, 0, null);
                            }
                        }
                        snapshotHandler.post(this::captureAndSendSnapshot);
                    } else {
                        isCompressing.set(false);
                    }
                }
            }
        });
    }

    private void captureAndSendSnapshot() {
        if (!snapshotLoopRunning) return;
        try {
            long now = System.currentTimeMillis();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            synchronized (this) {
                if (processingBitmap == null || processingBitmap.isRecycled()) return;
                processingBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
            }

            byte[] jpeg = out.toByteArray();

            if (callback != null) {
                callback.onSnapshotAvailable(jpeg, now);
            }
        } finally {
            // Unlocks backpressure block so the next main thread capture can slide in
            isCompressing.set(false);
        }
    }

    public void setJpegQuality(int quality) {
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("Quality must be between 0 and 100");
        }
        this.jpegQuality = quality;
    }

    public void stop() {
        snapshotLoopRunning = false;
        mainHandler.removeCallbacksAndMessages(null);

        if (snapshotHandler != null) {
            snapshotHandler.removeCallbacksAndMessages(null);
        }
        if (snapshotThread != null) {
            snapshotThread.quitSafely();
            try {
                snapshotThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            snapshotThread = null;
            snapshotHandler = null;
        }

        synchronized (this) {
            if (streamSurface != null) {
                streamSurface.release();
                streamSurface = null;
            }
            if (snapshotBitmap != null) {
                snapshotBitmap.recycle();
                snapshotBitmap = null;
            }
            if (processingBitmap != null) {
                processingBitmap.recycle();
                processingBitmap = null;
            }
        }
    }
}