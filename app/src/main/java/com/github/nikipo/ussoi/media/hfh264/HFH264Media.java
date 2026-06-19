package com.github.nikipo.ussoi.media.hfh264;

import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.checkForExactHFSupportedResolution;
import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.getHFpsCameraId;
import static com.github.nikipo.ussoi.media.utility.HFCameraHelper.getOptimalHFpsRange;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;
import static com.github.nikipo.ussoi.ui.MainActivity.getTextureView;
import static com.github.nikipo.ussoi.ui.UssoiStrings.ERROR;
import static com.github.nikipo.ussoi.ui.UssoiStrings.STREAM_HFH264;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;

public class HFH264Media implements Media {
    private final String TAG = "HFH264Media";
    private Context context;
    private HFCameraController cameraController;
    private WebSocketHandler websocket;

    private Surface streamSurface = null;
    private Surface recordSurface = null;

    private HFH264Config hfh264Config;

    private TextureView previewTextureView;
    private TextureviewHelper textureviewHelper;

    private LocalRecorder recorder;
    private volatile long maxUploadByteRate;
    private boolean initialized = false;
    private volatile boolean streamMuted;

    private String[] cameraId;

    @Override
    public void init(Context context) throws CameraAccessException {
        this.context = context.getApplicationContext();
        hfh264Config = new HFH264Config();

        hfh264Config.hqBitrate = 2_000_000 * 8; // 2MBps, MediaCodec recording
        maxUploadByteRate = 50_000 * 3;

        hfh264Config.streamJpegQuality = 70;
        hfh264Config.streamFps = 10;

        cameraId = getHFpsCameraId(context);

        if (cameraId.length > 0) {
            hfh264Config.cameraId = cameraId[0];
        } else {
            hfh264Config.cameraId = null;
            throw new IllegalStateException("No HFPS Camera");
        }

        websocket = new WebSocketHandler(
                context,
                new WebSocketHandler.MessageCallback() {
                    @Override public void onOpen() {}
                    @Override public void onPayloadReceivedText(String payload) {}
                    @Override public void onPayloadReceivedByte(byte[] payload) {}
                    @Override public void onClosed() {}
                    @Override public void onError(String error) {}
                }
        );

        SharedPreferences preferences = SaveInputFields.getInstance(context).get_shared_pref();
        websocket.setupConnection(KEY_stream_api_path, preferences.getString(KEY_Session_KEY, ERROR));

        initialized = true;
    }

    public void setPreviewTextureView(TextureView textureView) {
        if (textureView == null) throw new IllegalStateException("TextureView not initialized");
        this.previewTextureView = textureView;
    }

    @Override
    public void StartStream() {
        if (!initialized) throw new IllegalStateException("Media not initialized");
        if (IsStreaming()) throw new IllegalStateException("Stream Already Running");
        if (hfh264Config.res == null) throw new IllegalStateException("Resolution not set");
        if (IsRecording()) throw new IllegalStateException("Can't Stream, Recording Active");
        if (!checkForExactHFSupportedResolution(context, hfh264Config.cameraId, hfh264Config.res.getWidth(), hfh264Config.res.getHeight(), hfh264Config.fpsRange)) {
            throw new IllegalStateException("Resolution not supported by camera");
        }

        try {
            startImageStream();
            startCameraDirectPipeline();
        } catch (Exception e) {
            throw e;
        }
    }

    private synchronized void startImageStream() {
        setPreviewTextureView(getTextureView());

        if (previewTextureView == null) {
            throw new IllegalStateException("Preview TextureView not set - call setPreviewTextureView() before StartStream()");
        }

        textureviewHelper = new TextureviewHelper(
                previewTextureView,
                hfh264Config.res.getWidth(),
                hfh264Config.res.getHeight(),
                hfh264Config.streamFps,
                hfh264Config.streamJpegQuality,
                (jpeg, timestamp) -> {
                    if (streamMuted) return;
                    byte[] packet = buildPacket(jpeg, timestamp);
                    if (websocket != null && websocket.getPendingBytes() < maxUploadByteRate) {
                        websocket.sendBytes(packet);
                    }
                }
        );

        streamSurface = textureviewHelper.start();
    }

    private byte[] buildPacket(byte[] jpeg, long captureMs) {
        byte[] packet = new byte[9 + jpeg.length];

        packet[0] = 1; // every JPEG frame is independently decodable ("key frame")
        packet[1] = (byte) (captureMs >>> 56);
        packet[2] = (byte) (captureMs >>> 48);
        packet[3] = (byte) (captureMs >>> 40);
        packet[4] = (byte) (captureMs >>> 32);
        packet[5] = (byte) (captureMs >>> 24);
        packet[6] = (byte) (captureMs >>> 16);
        packet[7] = (byte) (captureMs >>> 8);
        packet[8] = (byte) captureMs;

        System.arraycopy(jpeg, 0, packet, 9, jpeg.length);
        return packet;
    }

    private synchronized void startCameraDirectPipeline() {
        try {
            cameraController = new HFCameraController(context, hfh264Config.cameraId);
            cameraController.setConfig(hfh264Config.res, hfh264Config.fpsRange);

            if (streamSurface != null) cameraController.updatePreviewSurface(streamSurface);
            if (recordSurface != null) cameraController.updateEncoderSurface(recordSurface);

            cameraController.openCamera();

        } catch (CameraAccessException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    @Override
    public synchronized void stopStream() {
        if (IsStreaming()) {
            stopCamera();
            stopImageStream();
        }
    }

    private void stopCamera() {
        if (cameraController != null) {
            cameraController.close();
            cameraController = null;
        }
    }

    private void stopImageStream() {
        if (textureviewHelper != null) {
            textureviewHelper.stop();
            textureviewHelper = null;
        }
        streamSurface = null;
    }

    @Override
    public void StreamMute(boolean mute) {
        this.streamMuted = mute;
    }

    @Override
    public boolean IsStreaming() {
        return streamSurface != null;
    }

    @Override
    public void StartRecording() {
        if (!IsStreaming())  throw new IllegalStateException("Start Streaming First");
        if (IsRecording()) throw new IllegalStateException("Recording Already Active");

        stopStream();

        try {
            startRecorder();
            if (!initialized) throw new IllegalStateException("Media not initialized");
            if (hfh264Config.res == null) throw new IllegalStateException("Record Resolution not set");
            if (!checkForExactHFSupportedResolution(context, hfh264Config.cameraId, hfh264Config.res.getWidth(), hfh264Config.res.getHeight(), hfh264Config.fpsRange)) {
                throw new IllegalStateException("Resolution not supported by camera");
            }

            try {
                startImageStream();
                startCameraDirectPipeline();
            } catch (Exception e) {
                stopStream();
                throw e;
            }
        } catch (Exception e) {
            stopRecorder();
            throw e;
        }
    }

    private void stopRecorder() {
        if (IsRecording()) {
            recorder.close();
            recorder = null;
            recordSurface = null;
        }
    }

    private void startRecorder() {
        try {
            recorder = new LocalRecorder(context,STREAM_HFH264);
            recordSurface = recorder.prepare(hfh264Config.res.getWidth(), hfh264Config.res.getHeight(), hfh264Config.fpsRange.getUpper(), hfh264Config.hqBitrate);
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
        stopRecorder();
    }

    @Override
    public void SwitchCamera(int camId) {
        if (IsRecording()) throw new IllegalStateException("Can't Switch Camera while Recording Active");

        for (String cam : cameraId) {
            if (cam.equals(String.valueOf(camId))) {
                hfh264Config.cameraId = cam;
                boolean wasStreaming = IsStreaming();
                stopStream();

                if (wasStreaming) {
                    StartStream();
                }
                return;
            }
        }
        throw new IllegalStateException("Camera Don't Exist");
    }

    @Override public void RotateCamera() {}
    @Override public void FlipCamera() {}

    @Override
    public void SetStreamResolution(int width, int height, int fps) {
        if (fps > 15 || fps < 1){
            throw new IllegalArgumentException("Stream Fps Limit 1 - 15");
        }
        hfh264Config.streamFps = fps;
    }

    @Override
    public void SetRecordingResolution(int width, int height, int fps) {
        if (IsRecording()) throw new IllegalStateException("Can't change Recording Resolution while Recording Active");
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
        bitrate = bitrate/8000;

        if (bitrate > 100 || bitrate < 1) throw new IllegalArgumentException("Quality Range 100-1 [" + bitrate +"]");

        if (IsStreaming()){
            textureviewHelper.setJpegQuality(bitrate);
        }
    }

    @Override
    public void SetRecordingBitrate(int bitrate) {
        if (IsRecording()) throw new IllegalStateException("Recording Active can't change bitrate");
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
    }
}