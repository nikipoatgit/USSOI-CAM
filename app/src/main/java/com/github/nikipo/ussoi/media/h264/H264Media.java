package com.github.nikipo.ussoi.media.h264;

import static com.github.nikipo.ussoi.media.utility.CameraHelper.checkForExactSupportedResolution;
import static com.github.nikipo.ussoi.media.utility.CameraHelper.getOptimalFpsRange;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_stream_api_path;
import static com.github.nikipo.ussoi.ui.UssoiStrings.ERROR;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.media.enocders.StreamingEncoder;
import com.github.nikipo.ussoi.media.utility.CameraHelper;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.storage.SaveInputFields;

import java.io.IOException;

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
    private SharedPreferences preferences;
    private SurfaceMode surfaceMode;


    @Override
    public synchronized void init(Context ctx) {
        if (initialized) {
            return;
        }
        context = ctx.getApplicationContext();
        h264Config = new H264Config();
        h264Config.recordingConfig.bitrate = 1_000_000*8;  // 1MBps
        h264Config.streamConfig.bitrate = 50_000*8; // 50KBps
        try{
            h264Config.cameraId = CameraHelper.getCameraIdList(context)[0];
        } catch (CameraAccessException e) {
            return;
        }

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

        preferences = SaveInputFields.getInstance(context).get_shared_pref();

        websocket.setupConnection(KEY_stream_api_path,preferences.getString(KEY_Session_KEY,ERROR));

        initialized = true;
    }

    @Override
    public void close() {
        stopCamera();
        stopStream();
        stopRecorder();
        stopWebSocket();
    }

    @Override
    public synchronized void StartStream() {
        if (!initialized) {
            throw new IllegalStateException("Media not initialized");
        }
        if (streamEncoder != null){
            throw new IllegalStateException("Stream Already Running");
        }
        if (h264Config.streamConfig.res == null){
            throw new IllegalStateException("Stream Resolution not set");
        }
        if (recorder != null){
            throw new IllegalStateException("Recording Active");
        }
        if (!checkForExactSupportedResolution(
                context,
                h264Config.cameraId,
                h264Config.streamConfig.res.getWidth(),
                h264Config.streamConfig.res.getHeight(),
                h264Config.streamConfig.fpsRange
        )){
            throw new IllegalStateException("Resolution Incapable with Camera");
        }
        // all checks done
        startEncoder();

        try {
            startCamera();
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
    }

    @Override
    public void SetStreamBitrate(int bitrate) {
        h264Config.streamConfig.bitrate = bitrate;
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
        throw new IllegalStateException("Functionality Unavailable");
    }

    @Override
    public void StartRecording() {
        if (streamEncoder != null){
            throw new IllegalStateException("Star");
        }
        if (h264Config.recordingConfig.res == null) throw new IllegalStateException("Stream Res Null")
    }

    @Override
    public void SetRecordingResolution(int width, int height, int fps) {
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
    }

    @Override
    public void SwitchCamera(int camId) {
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
        } catch (IOException e) {
            throw new IllegalStateException("StreamEncoder IOException");
        }
    }
    private void stopEncoder(){
        if (streamEncoder != null){
            streamEncoder.close();
        }
        streamEncoder = null;
    }

    private void startCamera(){
        if (camera != null){
            stopCamera();
        }
        if(sm == SurfaceMode.LQ_ONLY){
            if (LQSurface == null || !LQSurface.isValid()) throw new IllegalStateException("Invalid LQSurface");
            camera = new CameraController(context,sm);
            camera.setLQSurface(LQSurface);
            camera.start(h264Config.cameraId,h264Config.streamConfig.fpsRange);
        }
        else{
            if (HQSurface == null || !HQSurface.isValid()) throw new IllegalStateException("Invalid HQSurface");
            camera = new CameraController(context,sm);
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
        if(camera != null){
            camera.stop();
            camera = null;
        }
    }

    private void startRecorder(){
        if (recorder != null) throw new IllegalStateException("Recording Already Active");
        recorder = new LocalRecorder(context);
        try {
            HQSurface = recorder.prepare(
                    h264Config.recordingConfig.res.getWidth(),
                    h264Config.recordingConfig.res.getHeight(),
                    h264Config.recordingConfig.fpsRange.getUpper(),
                    h264Config.recordingConfig.bitrate
            );
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
}