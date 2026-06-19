package com.github.nikipo.ussoi.media.h264;

import static com.github.nikipo.ussoi.media.utility.CameraHelper.checkForExactSupportedResolution;
import static com.github.nikipo.ussoi.media.utility.CameraHelper.getOptimalFpsRange;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;
import static com.github.nikipo.ussoi.ui.UssoiStrings.ERROR;
import static com.github.nikipo.ussoi.ui.UssoiStrings.STREAM_H264;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.media.enocders.StreamingEncoder;
import com.github.nikipo.ussoi.media.utility.CameraHelper;
import com.github.nikipo.ussoi.media.utility.SurfaceMode;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file H264Media
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
public class H264Media implements Media {
    private static final String TAG = "H264Media";
    private CameraController camera;
    private StreamingEncoder streamEncoder;
    private LocalRecorder recorder;
    private WebSocketHandler websocket;
    private Context context;
    private H264Config h264Config;
    private boolean initialized = false;
    private Surface LQSurface ;
    private Surface HQSurface ;
    private SurfaceMode surfaceMode;
    private String[] cameraIds ;
    private Thread streamThread;
    private volatile boolean streamLoop;
    private volatile boolean streamMuted;
    private volatile long maxUploadByteRate;


    @Override
    public synchronized void init(Context ctx) throws CameraAccessException {
        if (initialized) {
            return;
        }
        context = ctx.getApplicationContext();
        h264Config = new H264Config();
        h264Config.recordingConfig.bitrate = 1_000_000*8;  // 1MBps
        h264Config.streamConfig.bitrate = 50_000*8; // 50KBps
        maxUploadByteRate = 50_000*3;
        cameraIds= CameraHelper.getCameraIdList(context);
        h264Config.cameraId = cameraIds[0];

        surfaceMode = SurfaceMode.LQ_ONLY;

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
    public void close() {
        stopRecorder();
        stopStream();
        stopWebSocket();
        initialized = false;
    }

    @Override
    public synchronized void StartStream() {
        if (!initialized) {
            throw new IllegalStateException("Media not initialized");
        }
        if (IsStreaming()){
            throw new IllegalStateException("Stream Already Running");
        }
        if (h264Config.streamConfig.res == null){
            throw new IllegalStateException("Stream Resolution not set");
        }
        if (IsRecording()){
            throw new IllegalStateException("Can't Recording Active");
        }
        if (!checkForExactSupportedResolution(
                context,
                h264Config.cameraId,
                h264Config.streamConfig.res.getWidth(),
                h264Config.streamConfig.res.getHeight(),
                h264Config.streamConfig.fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }
        // all checks done
        try {
            startEncoder();
            startCamera();
            SetStreamBitrate(h264Config.streamConfig.bitrate);// most times encoder ignores set bitrate cause res is high and bitrate is low mere 50KBps
        } catch (Exception e) {
            stopCamera();
            stopEncoder();
            throw e;
        }
    }

    @Override
    public void stopStream() {
        if (recorder != null){
            throw new IllegalStateException("Stop Recording First");
        }
        stopCamera();
        stopEncoder();
        stopRecorder();
    }

    @Override
    public void SetStreamResolution(
            int width,
            int height,
            int fps
    ) {
        Range<Integer> fpsRange = getOptimalFpsRange(context,h264Config.cameraId,fps);
        if (!checkForExactSupportedResolution(
                context,
                h264Config.cameraId,
                width,
                height,
                fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }
        h264Config.streamConfig.res =new Size(width, height);
        h264Config.streamConfig.fpsRange = fpsRange;


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
    public void SetStreamBitrate(int bitrate) {
        h264Config.streamConfig.bitrate = bitrate;
        maxUploadByteRate = (bitrate/8)*3;
        if(streamEncoder != null) {
            streamEncoder.setBitrate(h264Config.streamConfig.bitrate);
        }
    }

    @Override
    public boolean IsStreaming() {
        if (streamEncoder != null){
            return streamEncoder.isStreaming;
        }
        return false;
    }

    @Override
    public void StreamMute(boolean mute) {
        if (!IsStreaming()) {
            throw new IllegalStateException("Not Streaming");
        }
        streamMuted = mute;
    }
    @Override
    public void StartRecording() {
        if (IsRecording()) throw new IllegalStateException("Recording Already Active");
        if (!IsStreaming()) throw new IllegalStateException("Start Stream first");
        if (h264Config.recordingConfig.res == null) throw new IllegalStateException("Set Record Res first");
        if (!checkForExactSupportedResolution(
                context,
                h264Config.cameraId,
                h264Config.recordingConfig.res.getWidth(),
                h264Config.recordingConfig.res.getHeight(),
                h264Config.recordingConfig.fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }

        try {
            stopStream();

            surfaceMode = SurfaceMode.LQ_AND_HQ;
            startRecorder();

            if (!checkForExactSupportedResolution(
                    context,
                    h264Config.cameraId,
                    h264Config.streamConfig.res.getWidth(),
                    h264Config.streamConfig.res.getHeight(),
                    h264Config.streamConfig.fpsRange
            )) {
                throw new IllegalStateException("Resolution not supported by camera");
            }

            try {
                startEncoder();
                startCamera();
                SetStreamBitrate(h264Config.streamConfig.bitrate);// most times encoder ignores set bitrate cause res is high and bitrate is low mere 50KBps
            } catch (Exception e) {
                stopCamera();
                stopEncoder();
                throw e;
            }
        } catch (Exception e) {
            stopRecorder();
            throw e;
        }


    }

    @Override
    public void SetRecordingResolution(int width, int height, int fps) {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");
        Range<Integer> fpsRange = getOptimalFpsRange(context,h264Config.cameraId,fps);
        if (!checkForExactSupportedResolution(
                context,
                h264Config.cameraId,
                width,
                height,
                fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }
        h264Config.recordingConfig.res =new Size(width, height);
        h264Config.recordingConfig.fpsRange = fpsRange;
    }

    @Override
    public void SetRecordingBitrate(int bitrate) {
        if(recorder != null) {
           throw new IllegalStateException("Recording Active can't change");
        }
        h264Config.recordingConfig.bitrate = bitrate;
    }

    @Override
    public boolean IsRecording() {
        if(recorder != null){
            return recorder.isRecording;
        }
        return false;
    }

    @Override
    public void StopRecording() {
        stopRecorder();
        stopStream();
        surfaceMode = SurfaceMode.LQ_ONLY;
    }

    @Override
    public void SwitchCamera(int camId) {
        if (recorder != null) {
            throw new IllegalStateException("Recording Active");
        }

        if (camId < 0 || camId >= cameraIds.length) {
            throw new IllegalStateException("CamId Invalid");
        }

        boolean wasStreaming = IsStreaming();

        stopStream();

        h264Config.cameraId = cameraIds[camId];

        if (wasStreaming) {
            StartStream();
        }
    }

    @Override
    public void RotateCamera() {
    }

    @Override
    public void FlipCamera() {
    }

    private void stopWebSocket(){
        if (websocket != null){
            websocket.close();
            websocket = null;
        }
    }

    private void startEncoder(){
        streamEncoder = new StreamingEncoder();
        try {
            LQSurface = streamEncoder.prepare(
                    h264Config.streamConfig.res.getWidth(),
                    h264Config.streamConfig.res.getHeight(),
                    h264Config.streamConfig.fpsRange.getUpper(),
                    h264Config.streamConfig.bitrate
            );

            streamEncoder.start();
            startStreamLoop();
        } catch (IOException e) {
            throw new IllegalStateException("StreamEncoder IOException");
        }
    }
    private void stopEncoder(){
        stopStreamLoop();

        if (streamEncoder != null){
            streamEncoder.close();
        }
        streamEncoder = null;
    }

    private void startCamera(){
        Log.d(TAG,
                "startCamera() " +
                        "surfaceMode=" + surfaceMode +
                        ", cameraId=" + h264Config.cameraId);

        if (camera != null){
            stopCamera();
        }
        if(surfaceMode == SurfaceMode.LQ_ONLY){
            if (LQSurface == null || !LQSurface.isValid()) throw new IllegalStateException("Invalid LQSurface");
            camera = new CameraController(context,surfaceMode);
            camera.setLQSurface(LQSurface);
            camera.start(h264Config.cameraId,h264Config.streamConfig.fpsRange);
        }
        else{
            if (HQSurface == null || !HQSurface.isValid()) throw new IllegalStateException("Invalid HQSurface");
            camera = new CameraController(context,surfaceMode);
            camera.setLQSurface(LQSurface);
            camera.setHQSurface(HQSurface);
            Range<Integer> required =
                    h264Config.streamConfig.fpsRange.getUpper() >=
                            h264Config.recordingConfig.fpsRange.getUpper()
                            ? h264Config.streamConfig.fpsRange
                            : h264Config.recordingConfig.fpsRange;

            camera.start(
                    h264Config.cameraId,
                    required
            );
        }

    }
    private void stopCamera(){
        try {
            if (camera != null) {
                camera.stop();
                camera = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startRecorder(){
        if (recorder != null) throw new IllegalStateException("Recording Already Active");
        recorder = new LocalRecorder(context,STREAM_H264);
        try {
            HQSurface = recorder.prepare(
                    h264Config.recordingConfig.res.getWidth(),
                    h264Config.recordingConfig.res.getHeight(),
                    h264Config.recordingConfig.fpsRange.getUpper(),
                    h264Config.recordingConfig.bitrate
            );
            recorder.start();
        } catch (IOException e) {
            throw new IllegalStateException("recorder IOException");
        }
    }

    private void stopRecorder(){
        if (recorder != null){
            recorder.close();
        }
        recorder = null;
    }

    private void startStreamLoop() {

        streamLoop = true;

        streamThread = new Thread(() -> {

            while (streamLoop) {

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