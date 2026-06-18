package com.github.nikipo.ussoi.media.hfh264;

import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.checkForExactHFSupportedResolution;
import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.frameRateScaler;
import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.getClosest360pEvenResolution;
import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.getHFpsCameraId;
import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.getOptimalHFpsRange;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;
import static com.github.nikipo.ussoi.ui.UssoiStrings.ERROR;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.media.enocders.StreamingEncoder;
import com.github.nikipo.ussoi.media.utility.SurfaceMode;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;

import java.io.IOException;
import java.nio.ByteBuffer;

public class HFH264Media implements Media {
    private final String TAG = "HFH264Media";
    private  Context context;
    private HFCameraController cameraController;
    private GlRenderer glRenderer;
    private WebSocketHandler websocket;
    private Surface LQSurface = null ;
    private Surface HQSurface = null;
    Surface cameraInputSurface = null;
    private HFH264Config hfh264Config;
    private StreamingEncoder streamEncoder;
    private LocalRecorder recorder;
    private volatile long maxUploadByteRate;
    private boolean initialized = false;
    private volatile boolean streamLoop;
    private volatile boolean streamMuted;
    private Thread streamThread;
    private SurfaceMode surfaceMode;

    private String[] cameraId;


    @Override
    public void init(Context context) throws CameraAccessException {
        this.context = context.getApplicationContext();
        hfh264Config = new HFH264Config();

        hfh264Config.hqBitrate = 2_000_000*8; // 2MBps
        hfh264Config.lqBitrate = 50_000*8; // 50KBps
        maxUploadByteRate = 50_000*3;

        surfaceMode = SurfaceMode.LQ_ONLY;

        cameraId = getHFpsCameraId(context);

        if (cameraId.length > 0) {
            hfh264Config.cameraId = cameraId[0];
        } else {
            hfh264Config.cameraId = null; // or fallback camera ID
            throw new IllegalStateException("No HFPS Camera");
        }

        websocket = new WebSocketHandler(
                context,
                new WebSocketHandler.MessageCallback() {

                    @Override
                    public void onOpen() {

                    }

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
                    public void onError(String error) {

                    }
                }
        );

        SharedPreferences preferences = SaveInputFields.getInstance(context).get_shared_pref();

        websocket.setupConnection(KEY_stream_api_path,preferences.getString(KEY_Session_KEY,ERROR));


        initialized = true;
    }

    @Override
    public void StartStream() {
        if (!initialized) {
            throw new IllegalStateException("Media not initialized");
        }
        if (IsStreaming()){
            throw new IllegalStateException("Stream Already Running");
        }
        if (hfh264Config.res == null){
            throw new IllegalStateException("Record Resolution not set");
        }
        if (hfh264Config.resStream == null){
            throw new IllegalStateException("Stream Resolution not set");
        }
        if (IsRecording()){
            throw new IllegalStateException("Can't Recording Active");
        }
        if (!checkForExactHFSupportedResolution(
                context, 
                hfh264Config.cameraId,
                hfh264Config.res.getWidth(),
                hfh264Config.res.getHeight(),
                hfh264Config.fpsRange
        )){
            throw new IllegalStateException("Resolution not supported by camera");
        }

        try {
            startEncoder();
            startGLRender(surfaceMode);
            startCamera();
            SetStreamBitrate(hfh264Config.lqBitrate);
        } catch (Exception e) {
            throw e;
        }
    }

    private void recordingBypassStartStream(){
        if (!initialized) {
            throw new IllegalStateException("Media not initialized");
        }
        if (IsStreaming()){
            throw new IllegalStateException("Stream Already Running");
        }
        if (hfh264Config.res == null){
            throw new IllegalStateException("Record Resolution not set");
        }
        if (hfh264Config.resStream == null){
            throw new IllegalStateException("Stream Resolution not set");
        }
        if (!checkForExactHFSupportedResolution(
                context,
                hfh264Config.cameraId,
                hfh264Config.res.getWidth(),
                hfh264Config.res.getHeight(),
                hfh264Config.fpsRange
        )){
            throw new IllegalStateException("Resolution not supported by camera");
        }

        try {
            startEncoder();
            startGLRender(surfaceMode);
            startCamera();
            SetStreamBitrate(hfh264Config.lqBitrate);
        } catch (Exception e) {
            throw e;
        }
    }

    private synchronized void startEncoder(){
        streamEncoder = new StreamingEncoder();
        try {
            LQSurface = streamEncoder.prepare(
                    hfh264Config.res.getWidth(),
                    hfh264Config.res.getHeight(),
                    hfh264Config.fpsRange.getUpper(),
                    hfh264Config.lqBitrate
            );

            streamEncoder.start();
            startStreamLoop();
        } catch (IOException e) {
            throw new IllegalStateException("StreamEncoder IOException");
        }
    }
    private synchronized void startGLRender(SurfaceMode sm) {
        glRenderer = new GlRenderer(hfh264Config.res.getWidth(), hfh264Config.res.getHeight(), sm);
        glRenderer.init(HQSurface, LQSurface,hfh264Config.skipCount);
        cameraInputSurface = glRenderer.getCameraSurface();
    }
    private synchronized void  startCamera() {
        cameraController = new HFCameraController(context, hfh264Config.cameraId);
        cameraController.setConfig(hfh264Config.res, hfh264Config.fpsRange);
        cameraController.updateCameraSurface(cameraInputSurface);
        try {
            cameraController.openCamera();
        } catch (CameraAccessException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }


    @Override
    public synchronized void stopStream() {
        if(IsRecording()) throw new IllegalStateException("Can't Recording active");
        if (IsStreaming()){
            stopStreamLoop();
            stopCamera();
            releaseGlRenderer();
            stopStreamEncoder();
        }
    }

    private void stopCamera() {
        if (cameraController != null) {
            cameraController.close();
            cameraController = null;
        }
    }

    private void releaseGlRenderer() {
        if (glRenderer != null) {
            glRenderer.release();
            glRenderer = null;
        }
    }

    private void stopStreamEncoder() {
        if (streamEncoder != null) {
            streamEncoder.close();
            streamEncoder = null;
        }
    }



    @Override
    public void StreamMute(boolean mute) {
        if (glRenderer != null) {
            glRenderer.setLqEnabled(!mute);
        }
    }

    @Override
    public boolean IsStreaming() {
        return streamEncoder != null;
    }

    @Override
    public void StartRecording() {
        if (IsRecording()) throw new IllegalStateException("Recording Already Active");
        if (!IsStreaming()) throw new IllegalStateException("Start Stream first");
        if (hfh264Config.res == null) throw new IllegalStateException("Set Record Res first");


        try {
            stopRecorder();
            stopStream();
            surfaceMode = SurfaceMode.LQ_AND_HQ;
            startRecorder();
            recordingBypassStartStream();
        } catch (Exception e) {
            stopStream();
            stopRecorder();
            throw e;
        }


    }

    private void stopRecorder() {
        if (IsRecording()){
            surfaceMode = SurfaceMode.LQ_ONLY;

            recorder.close();
            recorder = null;
        }
    }

    private void startRecorder() {
        try {
            recorder = new LocalRecorder(context);
            HQSurface =  recorder.prepare(hfh264Config.res.getWidth(),hfh264Config.res.getHeight(),hfh264Config.fpsRange.getUpper(),hfh264Config.hqBitrate);
            recorder.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean IsRecording() {
        return recorder != null;
    }

    @Override
    public void StopRecording() {
       stopCamera();
       releaseGlRenderer();
       stopRecorder();
       stopStream();
    }

    @Override
    public void SwitchCamera(int camId) {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");

        for(String cam : cameraId){
            if (cam.equals(String.valueOf(camId))){
                hfh264Config.cameraId = cam;

                boolean wasStreaming = IsStreaming();
                stopStream();

                if (wasStreaming){
                    StartStream();
                }
                return;
            }
        }

        throw new IllegalStateException("Camera Don't Exist");
    }

    @Override
    public void RotateCamera() {
    }

    @Override
    public void FlipCamera() {
    }

    @Override
    public void SetStreamResolution(int width, int height, int fps) {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");
        if (hfh264Config.res == null) throw new IllegalStateException("Set Record Res First");
        if (fps > hfh264Config.fpsRange.getUpper()) throw new IllegalStateException("FPS Can't be higher that record res");

        hfh264Config.resStream = getClosest360pEvenResolution(hfh264Config.res.getWidth(),hfh264Config.res.getHeight());

        Pair<Integer, Integer> result = frameRateScaler(hfh264Config.fpsRange.getUpper(),fps);

        hfh264Config.fps = result.first;
        hfh264Config.skipCount = result.second;

        Log.d(TAG,
                "HighSpeedConfig: fps=" + hfh264Config.fps +
                        ", skipCount=" + hfh264Config.skipCount);

        boolean wasStreaming = IsStreaming();
        stopStream();

        try {
            if (wasStreaming) {
                StartStream();
            }
        } catch (Exception e) {
            stopStream();
            throw e;
        }
    }

    @Override
    public void SetRecordingResolution(int width, int height, int fps) {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");
        Range<Integer> fpsRange = getOptimalHFpsRange(context, hfh264Config.cameraId, width, height, fps);
        if (fpsRange == null) throw new IllegalStateException("Fps Not Available for this resolution");

        if (!checkForExactHFSupportedResolution(context, hfh264Config.cameraId, width, height, fpsRange)) {
            throw new IllegalStateException("Resolution not supported by camera");
        }

        hfh264Config.res = new Size(width, height);
        hfh264Config.fpsRange = fpsRange;
    }

    @Override
    public void SetStreamBitrate(int bitrate) {
        if(IsStreaming()){
            streamEncoder.setBitrate(bitrate);
        }
    }

    @Override
    public void SetRecordingBitrate(int bitrate) {
        if(IsRecording()) throw new IllegalStateException("Recording Active can't change");
        hfh264Config.hqBitrate = bitrate;
    }

    @Override
    public void close() {
        stopStream();
        stopRecorder();
        if (cameraController != null) {
            cameraController.close();
            cameraController = null;
        }
        if (glRenderer != null) {
            glRenderer.release();
            glRenderer = null;
        }
    }

    private void startStreamLoop() {

        streamLoop = true;

        streamThread = new Thread(() -> {

            while (streamLoop && streamEncoder != null) {

                StreamingEncoder.EncodedFrame frame = streamEncoder.dequeue();

                if (frame == null || streamMuted) {
                    continue;
                }
                byte[] packet = buildPacket(frame);
                if(websocket != null && websocket.getPendingBytes() < maxUploadByteRate) {
                    websocket.sendBytes(packet);
                }
            }

        }, "H264-Drain");

        streamThread.start();
    }

    private byte[] buildPacket(StreamingEncoder.EncodedFrame frame) {

        ByteBuffer src = frame.data.duplicate();
        int payloadSize = src.remaining();

        byte[] packet = new byte[9 + payloadSize];

        long captureMs = System.currentTimeMillis();

        packet[0] = (byte) (frame.keyFrame ? 1 : 0);

        packet[1] = (byte) (captureMs >>> 56);
        packet[2] = (byte) (captureMs >>> 48);
        packet[3] = (byte) (captureMs >>> 40);
        packet[4] = (byte) (captureMs >>> 32);
        packet[5] = (byte) (captureMs >>> 24);
        packet[6] = (byte) (captureMs >>> 16);
        packet[7] = (byte) (captureMs >>> 8);
        packet[8] = (byte) captureMs;

        src.get(packet, 9, payloadSize);

        return packet;
    }


    private void stopStreamLoop() {

        streamLoop = false;

        if (streamThread != null) {
            try {
                streamThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            streamThread = null;
        }
    }
}