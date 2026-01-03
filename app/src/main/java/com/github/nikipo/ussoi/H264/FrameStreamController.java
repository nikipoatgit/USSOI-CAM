package com.github.nikipo.ussoi.H264;



import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.streamingUrl;
import static com.github.nikipo.ussoi.ServicesManager.ConnRouter.sendAck;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;


import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.MacroServices.WebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class FrameStreamController {

    private static final String TAG = "FrameStreamController";
    private static FrameStreamController instance;
    private final Context context;
    private CameraController cameraController;
    private LocalRecorder localRecorder;
    private StreamingEncoder streamingEncoder;
    private WebSocketHandler ws;
    private final Logging logger;

    private Thread streamThread;


    private volatile boolean initialized;
    private volatile boolean streaming;
    private volatile boolean isRecording;
    private volatile boolean isRecordingParamsSet;

    private String currentCameraId = null;

    // HQ recording
    private int recordWidth  = 1920;
    private int recordHeight = 1080;
    private int HQBitrate    = 5_000_000;

    // LQ streaming
    private int streamWidth  = 1920;
    private int streamHeight = 1080;
    private int LQBitrate    = 500_000;

    private volatile int fps = 20;
    private long basePtsUs = -1;
    private static final int HEADER_SIZE = 1 + 8;
    private Range<Integer> targetRange = null;

    private FrameStreamController(Context ctx) {
        context = ctx.getApplicationContext();
        logger = Logging.getInstance(ctx);
        cameraController = new CameraController(context);
    }

    public static synchronized FrameStreamController getInstance(Context ctx) {
        if (instance == null) {
            instance = new FrameStreamController(ctx);
        }
        return instance;
    }

    /* ---------------- INIT ---------------- */

    public synchronized void init(String sessionKey) {
        isRecordingParamsSet = false;
        isRecording = false;
        streaming = false;

        if (initialized) {
            return;
        }

        currentCameraId = cycleCameraId();
        if (currentCameraId == null){
            return;
        }

        setQualityParams();

        ws = new WebSocketHandler(context,new WebSocketHandler.MessageCallback() {
            public void onOpen() {}
            @Override
            public void onPayloadReceivedText(String payload) {
            }

            @Override
            public void onPayloadReceivedByte(byte[] payload) {
            }
            @Override
            public void onClosed() {
            }
            @Override
            public void onError(String e) {
            }
        });
        ws.setupConnection(streamingUrl, sessionKey);
        try {

            // LQ streaming
            streamingEncoder = new StreamingEncoder();
            Surface lqSurface = streamingEncoder.prepare(
                    streamWidth, streamHeight, targetRange.getUpper(), LQBitrate
            );
            // HQ recording
            localRecorder = new LocalRecorder(context);
            Surface hqSurface = localRecorder.prepare(
                    recordWidth, recordHeight, targetRange.getUpper(), HQBitrate
            );

            // attach surface
            cameraController.attachHQSurface(hqSurface);
            cameraController.attachLQSurface(lqSurface);

        } catch (IOException e) {
            logger.log(TAG + " " + Log.getStackTraceString(e));
            throw new RuntimeException(e);
        }
        initialized = true;
    }
    private synchronized Range<Integer> getOptimalFpsRange(String cameraId, int width, int height, int targetFps) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Range<Integer>[] availableRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (map == null || availableRanges == null) {
                Log.e(TAG, "Capabilities not available.");
                return new Range<>(30, 30);
            }

            // --- STEP 1: Determine Hardware Limit for this Resolution ---
            Size targetSize = new Size(width, height);
            long minDurationNs = map.getOutputMinFrameDuration(SurfaceTexture.class, targetSize);
            double maxHwFps = (minDurationNs > 0) ? (1_000_000_000.0 / minDurationNs) : 30.0;

            // Clamp target down if hardware cannot support it
            int effectiveTargetFps = targetFps;
            if (effectiveTargetFps > maxHwFps) {
                effectiveTargetFps = (int) maxHwFps;
            }

            // --- STEP 2: Find Best Range (Prioritizing Fixed FPS) ---
            Range<Integer> bestRange = null;
            int bestScore = -1;

            for (Range<Integer> r : availableRanges) {
                int min = r.getLower();
                int max = r.getUpper();

                // Skip ranges that exceed the physical hardware limit
                if (max > maxHwFps) continue;

                int currentScore = 0;

                // --- CHANGED LOGIC START ---

                // PRIORITY 1: Must be a Fixed Range (min == max)
                // We give a massive bonus for fixed ranges.
                if (min == max) {
                    currentScore += 1000;
                }

                // PRIORITY 2: Match the Target (or get as close as possible without going over)
                if (max == effectiveTargetFps) {
                    currentScore += 500;
                } else if (max < effectiveTargetFps) {
                    // If we can't hit target, closer is better.
                    // Deduct points for how far away we are.
                    currentScore -= (effectiveTargetFps - max);
                } else {
                    // Range is higher than target (unlikely due to maxHwFps check, but safety)
                    currentScore -= (max - effectiveTargetFps) * 2;
                }
                // --- CHANGED LOGIC END ---

                if (currentScore > bestScore) {
                    bestScore = currentScore;
                    bestRange = r;
                }
            }

            // Fallback: If no range found (rare), pick the last one
            if (bestRange == null && availableRanges.length > 0) {
                bestRange = availableRanges[availableRanges.length - 1];
            }

            Log.d(TAG, "Target: " + width + "x" + height + " @ " + targetFps + " FPS");
            Log.d(TAG, "   -> Hardware Max for Size: " + (int)maxHwFps + " FPS");
            Log.d(TAG, "   -> Selected Range: " + bestRange);

            return bestRange;

        } catch (Exception e) {
            Log.e(TAG, "Error calculating optimal FPS", e);
            return new Range<>(30, 30);
        }
    }
    private synchronized String cycleCameraId() {
        try {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            String[] idList = manager.getCameraIdList();

            if (idList.length == 0) {
                Log.e(TAG, "No cameras found on device.");
                return null;
            }
            if (currentCameraId == null) {
                currentCameraId = idList[0];
                return currentCameraId;
            }

            int currentIndex = -1;
            for (int i = 0; i < idList.length; i++) {
                if (idList[i].equals(currentCameraId)) {
                    currentIndex = i;
                    break;
                }
            }

            int nextIndex = (currentIndex + 1) % idList.length;

            currentCameraId = idList[nextIndex];

            Log.d(TAG, "Cycled Camera: " + idList[currentIndex] + " -> " + currentCameraId);
            return currentCameraId;

        } catch (Exception e) {
            Log.e(TAG, "Failed to cycle camera ID", e);
            logger.log(TAG + " " + Log.getStackTraceString(e));
            return currentCameraId;
        }
    }
    private synchronized Size getHardwareSupportedResolution(String cameraId, int reqWidth, int reqHeight) {
        if (cameraId == null) {
            logger.log(TAG + " cameraId null");
            return null;
        }

        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                logger.log(TAG + " CameraManager null");
                return null;
            }

            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(cameraId);

            StreamConfigurationMap map =
                    characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                logger.log(TAG + " StreamConfigurationMap null");
                return null;
            }

            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) {
                logger.log(TAG + " no output sizes");
                return null;
            }

            final double reqAspect = (double) reqWidth / reqHeight;
            final long reqPixels = (long) reqWidth * reqHeight;

            Size bestSize = null;
            double bestAspectDiff = Double.MAX_VALUE;
            long bestPixelDiff = Long.MAX_VALUE;

            for (Size s : sizes) {
                double aspect =
                        (double) s.getWidth() / s.getHeight();
                double aspectDiff =
                        Math.abs(aspect - reqAspect);

                long pixels =
                        (long) s.getWidth() * s.getHeight();
                long pixelDiff =
                        Math.abs(pixels - reqPixels);

                // PRIMARY: aspect ratio
                // SECONDARY: pixel count
                if (aspectDiff < bestAspectDiff ||
                        (aspectDiff == bestAspectDiff &&
                                pixelDiff < bestPixelDiff)) {

                    bestAspectDiff = aspectDiff;
                    bestPixelDiff = pixelDiff;
                    bestSize = s;
                }
            }

            if (bestSize == null) {
                return null;
            }

            logger.log(TAG +
                    " req=" + reqWidth + "x" + reqHeight + "reqAspect :"+
                    " (" + reqAspect + ")" +
                    " used=" + bestSize.getWidth() +
                    "x" + bestSize.getHeight());

            return bestSize;

        } catch (Exception e) {
            logger.log(TAG + " exception " + e);
            return null;
        }
    }

    private synchronized void setQualityParams(){
        Size res = getHardwareSupportedResolution(currentCameraId, recordWidth, recordHeight);
        if (res == null) {
            return;
        }
        recordWidth = res.getWidth();
        recordHeight = res.getHeight();

        targetRange = getOptimalFpsRange(currentCameraId,recordWidth,recordHeight,fps);

        res = getHardwareSupportedResolution(currentCameraId,streamWidth,streamHeight);
        if (res == null) {
            return;
        }

        streamHeight = res.getHeight();
        streamWidth = res.getWidth();
    }


    /* ---------------- STREAM ---------------- */

    public void startStreaming() {
        if (!initialized) return;
        if (streaming) return;
        if (isRecording) return;

        streaming = true;
        basePtsUs = -1;

        cameraController.start(currentCameraId,targetRange);
        streamingEncoder.start();

        streamThread = new Thread(this::drainEncoder, "StreamDrain");
        streamThread.start();
    }
    private void drainEncoder() {
        while (streaming) {
            StreamingEncoder.EncodedFrame frame =
                    streamingEncoder.dequeue();

            if (frame == null) continue;

            byte[] packet = buildPacket(frame);
            ws.connSendPayloadBytes(packet);
        }
    }
    private byte[] buildPacket(StreamingEncoder.EncodedFrame frame) {

        if (basePtsUs < 0) {
            basePtsUs = frame.ptsUs;
        }

        long pts = frame.ptsUs - basePtsUs;
        ByteBuffer src = frame.data.duplicate();

        byte[] data = new byte[HEADER_SIZE  + src.remaining()];
        int i = 0;

        data[i++] = (byte) (frame.keyFrame ? 1 : 0);

        for (int b = 7; b >= 0; b--) {
            data[i++] = (byte) (pts >> (b * 8));
        }

        src.get(data, i, src.remaining());
        return data;
    }


    /* ---------------- RECORD ---------------- */
    public void setRecordingParams(int width,int height,int bitrate,int fps, int reqId){
        this.recordHeight = height; this.recordWidth = width; this.HQBitrate = bitrate; this.fps = fps;
        isRecordingParamsSet = true;
        sendAck("ack", reqId, "Saved Recording Params");
    }
    public void startRecording(int reqId) {
        if(!streaming){
            sendAck("nack", reqId, "!! Streaming Disabled");
            return;
        }
        if(isRecording) return;
        if(!isRecordingParamsSet){
            sendAck("nack", reqId, "Recording Params NOt Set");
            return;
        }
        setQualityParams();
        restartService();
        localRecorder.start();
        isRecording = true;
        Log.d(TAG, "Recording stated " );
        logger.log(TAG + " Recording stated" );
        sendAck("ack", reqId, "Recording Started");
    }
    public boolean isRecordingActive(){
        return  localRecorder.isRecordingActive();
    }

    // ----------------------------------------- control Functions ------------------------
    public void toggleVideo(boolean video,int reqId) {
        if (cameraController != null){
            sendAck("ack", reqId, "Play/Pause Cmd");
            if (video) {
                cameraController.resumeStreaming();
            }
            else {
                cameraController.pauseStreaming();
            }
        }
    }
    public void setVideoBitrate(int bitrate,int reqId) {
        if (streaming && streamingEncoder != null) {
            LQBitrate = bitrate;
            streamingEncoder.setBitrate(bitrate);
            sendAck("ack", reqId, "Stream Bitrate Cmd ");
        }
    }
    public synchronized void toggleCamera() {
        if (isRecording) return;

        currentCameraId = cycleCameraId();
        if (currentCameraId == null) return;

        if (streaming) {
            setQualityParams();
            restartService();
        }
    }
    public void ChangeFrameInterval(int frameIntervalMs) {
    }
    public synchronized void changeCaptureFormat(int width, int height, int fps, int reqId) {
        if (!initialized) {
            Log.w(TAG, "Cannot change format: Controller not initialized.");
            return;
        }
        if (isRecording){
            return;
        }

        this.streamWidth = width; this.streamHeight = height;

        setQualityParams();
        restartService();
        sendAck("ack", reqId, "Stream Resolution Changed ");
    }

    // ------------------------------------------  restart / stop service ------------------------------------
    private void restartService() {
        streaming = false;

        Thread t = streamThread;
        streamThread = null;

        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }

        synchronized (this) {

            streaming = false;

            cameraController.stop();

            if (streamingEncoder != null) {
                streamingEncoder.stop();
                streamingEncoder.release();
            }

            if (localRecorder != null) {
                if (isRecording) {
                    localRecorder.stop();
                }
                localRecorder.release();
                localRecorder = null;
            }

            cameraController = new CameraController(context);

            try {
                streamingEncoder = new StreamingEncoder();
                Surface lqSurface = streamingEncoder.prepare(
                        streamWidth, streamHeight, targetRange.getUpper(), LQBitrate
                );

                localRecorder = new LocalRecorder(context);
                Surface hqSurface = localRecorder.prepare(
                        recordWidth, recordHeight, targetRange.getUpper(), HQBitrate
                );

                cameraController.attachHQSurface(hqSurface);
                cameraController.attachLQSurface(lqSurface);

            } catch (IOException e) {
                logger.log(TAG + e);
                throw new RuntimeException(e);
            }

            basePtsUs = -1;
            streaming = true;
            initialized = true;

            cameraController.start(currentCameraId, targetRange);
            streamingEncoder.start();

            streamThread = new Thread(this::drainEncoder, "StreamDrain");
            streamThread.start();
        }
    }

    public synchronized void stopAllServices() {
        streaming = false;

        if (streamThread != null) {
            try {
                streamThread.join();
            } catch (InterruptedException e) {
                logger.log(TAG + " join interrupted " + e);
            }
            streamThread = null;
        }

        if (cameraController != null) {
            cameraController.stop();
        }

        if (streamingEncoder != null) {
            streamingEncoder.stop();
            streamingEncoder.release();
            streamingEncoder = null;
        }

        if (localRecorder != null) {
            if (isRecording) {
                localRecorder.stop();
            }
            localRecorder.release();
            localRecorder = null;
        }

        if (ws != null) {
            ws.closeConnection();
            ws = null;
        }

        isRecordingParamsSet = false;
        isRecording = false;
        initialized = false;
        basePtsUs = -1;
        Log.d(TAG, "Stopped");
    }

}
