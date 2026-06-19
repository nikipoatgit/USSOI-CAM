package com.github.nikipo.ussoi.media.webrtc;

import static com.github.nikipo.ussoi.media.utility.CameraHelper.checkForExactSupportedResolution;
import static com.github.nikipo.ussoi.media.utility.CameraHelper.getOptimalFpsRange;
import static com.github.nikipo.ussoi.ui.UssoiStrings.CMD;
import static com.github.nikipo.ussoi.ui.UssoiStrings.CMD_ID;
import static com.github.nikipo.ussoi.ui.UssoiStrings.DATA;
import static com.github.nikipo.ussoi.ui.UssoiStrings.RESPONSE;
import static com.github.nikipo.ussoi.ui.UssoiStrings.STREAM_WEBRTC;
import static com.github.nikipo.ussoi.ui.UssoiStrings.TYPE;
import static com.github.nikipo.ussoi.ui.UssoiStrings.WEBRTC_ICE;
import static com.github.nikipo.ussoi.ui.UssoiStrings.WEBRTC_SDP;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.enocders.LocalRecorder;
import com.github.nikipo.ussoi.media.utility.SurfaceMode;
import com.github.nikipo.ussoi.media.utility.CameraHelper;
import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.storage.logs.Logging;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file WebRtcMedia
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
public class WebRtcMedia implements Media {

    private static final String TAG = "WebRtcMedia";
    // Default STUN servers
    private static final List<String> DEFAULT_ICE_SERVERS = Arrays.asList(
            "stun:stun.l.google.com:19302",
            "stun:stun.l.google.com:5349",
            "stun:stun1.l.google.com:19302",
            "stun:stun1.l.google.com:5349",
            "stun:stun2.l.google.com:19302",
            "stun:stun2.l.google.com:5349",
            "stun:stun3.l.google.com:3478",
            "stun:stun3.l.google.com:5349",
            "stun:stun4.l.google.com:19302",
            "stun:stun4.l.google.com:5349",
            "stun:stun.cloudflare.com:3478",
            "stun:global.stun.twilio.com:3478",
            "stun:stun.services.mozilla.com:3478",
            "stun:stun.stunprotocol.org:3478",
            "stun:stun.voip.blackberry.com:3478",
            "stun:stun.sipnet.net:3478",
            "stun:stun.sipnet.ru:3478",
            "stun:stun.voipstunt.com:3478",
            "stun:stun.zadarma.com:3478"
    );


    private final ConnectionManager connectionManager;
    private LocalRecorder recorder;
    private List<String> iceServers = DEFAULT_ICE_SERVERS;

    private Context   context;
    private Logging   logger;
    private webrtcConfig webrtcConfig;
    private String[]  cameraIds;
    private boolean   initialized = false;

    private WebRtcPeerConnection peerConnection;
    private SurfaceCapturer surfaceCapturer;
    private volatile boolean streaming  = false;


    public WebRtcMedia(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public synchronized void init(Context ctx) throws CameraAccessException {
        if (initialized) return;

        context   = ctx.getApplicationContext();
        logger    = Logging.getInstance(context);
        webrtcConfig = new webrtcConfig();
        webrtcConfig.recordConfig.bitrate = 1_000_000*8;  // 1MBps
        webrtcConfig.streamConfig.bitrate = 50_000*8; // 50KBps

        cameraIds = CameraHelper.getCameraIdList(context);
        webrtcConfig.cameraId = cameraIds[0];

        // One-time factory + EGL init (no-op if already done)
        WebRtcPeerConnection.initializeFactory(context);

        initialized = true;
        Log.d(TAG, "WebRtcMedia initialized");
    }

    @Override
    public synchronized void close() {
        stopRecorder();
        stopStream();
        initialized = false;
    }


    @Override
    public synchronized void StartStream() {
        if (!initialized) throw new IllegalStateException("Not initialized");
        if (streaming)    throw new IllegalStateException("Stream already running");
        if (webrtcConfig.streamConfig.res == null)  throw new IllegalStateException("Stream resolution not set");
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");

        if (!checkForExactSupportedResolution(
                context,
                webrtcConfig.cameraId,
                webrtcConfig.streamConfig.res.getWidth(),
                webrtcConfig.streamConfig.res.getHeight(),
                webrtcConfig.streamConfig.fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }


        try {
            startPeerConnection();
            startCapturer();
        } catch (Exception e) {
            stopStream();
            throw e;
        }
    }

    @Override
    public synchronized void stopStream() {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");
        streaming  = false;

        stopCapturer();
        stopPeerConnection();
    }

    @Override
    public void SetStreamResolution(int width, int height, int fps) {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");
        Range<Integer> fpsRange =  getOptimalFpsRange(context, webrtcConfig.cameraId,fps);
        if (!checkForExactSupportedResolution(
                context,
                webrtcConfig.cameraId,
                width,
                height,
                fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }

        webrtcConfig.streamConfig.res = new Size(width, height);
        webrtcConfig.streamConfig.fpsRange    = fpsRange;

        if (streaming && surfaceCapturer != null) {
            surfaceCapturer.changeCaptureFormat(width, height, fpsRange.getUpper());
        }
    }

    @Override
    public void SetStreamBitrate(int bitrate) {
        if (bitrate <= 100) throw new IllegalArgumentException("Invalid bitrate");
        webrtcConfig.streamConfig.bitrate = bitrate;
        if (peerConnection != null && streaming) {
            peerConnection.setBitrate(bitrate);
        }
    }

    @Override
    public boolean IsStreaming() {
        return streaming;
    }

    @Override
    public void StreamMute(boolean mute) {
        if (peerConnection != null) {
            peerConnection.setVideoEnabled(!mute);
        }
    }


    @Override
    public void StartRecording() {
        if (IsRecording()) throw new IllegalStateException("Recording Already Active");
        if (!IsStreaming()) throw new IllegalStateException("Start Stream first");
        if (webrtcConfig.recordConfig.res == null) throw new IllegalStateException("Set Record Res first");
        if (!checkForExactSupportedResolution(
                context,
                webrtcConfig.cameraId,
                webrtcConfig.recordConfig.res.getWidth(),
                webrtcConfig.recordConfig.res.getHeight(),
                webrtcConfig.recordConfig.fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }

        try {
            startRecorder();
        } catch (Exception e) {
            stopRecorder();
            throw new IllegalStateException(e.getMessage());
        }
    }


    private void startRecorder(){
        if (recorder != null) throw new IllegalStateException("Recording Already Active");
        try {
            recorder = new LocalRecorder(context,STREAM_WEBRTC);
            Surface hqSurface;
            hqSurface = recorder.prepare(
                    webrtcConfig.recordConfig.res.getWidth(),
                    webrtcConfig.recordConfig.res.getHeight(),
                    webrtcConfig.recordConfig.fpsRange.getUpper(),
                    webrtcConfig.recordConfig.bitrate
            );
            if(!hqSurface.isValid()) throw new IllegalStateException("Invalid HQSurface");

            if (surfaceCapturer == null) throw new IllegalStateException("CameraCapture Null");
            surfaceCapturer.setSurfaceMode(SurfaceMode.LQ_AND_HQ);
            surfaceCapturer.setHqSurface(hqSurface);

            recorder.start();
            surfaceCapturer.changeCaptureFormat(
                    webrtcConfig.recordConfig.res.getWidth(),
                    webrtcConfig.recordConfig.res.getHeight(),
                    webrtcConfig.recordConfig.fpsRange.getUpper()
            );

        } catch (IOException e) {
            throw new IllegalStateException("recorder IOException");
        }

    }

    private void stopRecorder(){
        if (surfaceCapturer != null) {
            surfaceCapturer.setSurfaceMode(SurfaceMode.LQ_ONLY);
            if (streaming && webrtcConfig.streamConfig.res != null) {
                surfaceCapturer.changeCaptureFormat(
                        webrtcConfig.streamConfig.res.getWidth(),
                        webrtcConfig.streamConfig.res.getHeight(),
                        webrtcConfig.streamConfig.fpsRange.getUpper()
                );
            } else {
                stopCapturer();
            }
        }

        if (recorder != null) {
            recorder.close();
            recorder = null;
        }
    }


    @Override
    public void SetRecordingResolution(int width, int height, int fps) {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");
        Range<Integer> fpsRange = getOptimalFpsRange(context,webrtcConfig.cameraId,fps);
        if (!checkForExactSupportedResolution(
                context,
                webrtcConfig.cameraId,
                width,
                height,
                fpsRange
        )) {
            throw new IllegalStateException("Resolution not supported by camera");
        }
        webrtcConfig.recordConfig.res =new Size(width, height);
        webrtcConfig.recordConfig.fpsRange = fpsRange;
    }

    @Override
    public void SetRecordingBitrate(int bitrate) {
        webrtcConfig.recordConfig.bitrate = bitrate;
    }

    @Override
    public boolean IsRecording() {
        return recorder != null;
    }

    @Override
    public void StopRecording() {
        stopRecorder();
    }


    @Override
    public synchronized void SwitchCamera(int camId) {
        if (IsRecording()) throw new IllegalStateException("Can't Recording Active");

        if (camId < 0 || camId >= cameraIds.length) {
            throw new IllegalArgumentException("Invalid camId: " + camId);
        }

        String newCameraId = cameraIds[camId];

        if (streaming) {
            if (webrtcConfig.streamConfig.res == null) throw new IllegalStateException("Stream resolution not set");
            if (!checkForExactSupportedResolution(
                    context,
                    newCameraId,
                    webrtcConfig.streamConfig.res.getWidth(),
                    webrtcConfig.streamConfig.res.getHeight(),
                    webrtcConfig.streamConfig.fpsRange
            )) {
                throw new IllegalStateException("Resolution not supported by camera");
            }

            surfaceCapturer.switchCamera(
                    newCameraId,
                    webrtcConfig.streamConfig.res.getWidth(),
                    webrtcConfig.streamConfig.res.getHeight()
            );
        }

        webrtcConfig.cameraId = newCameraId;
    }

    @Override
    public void RotateCamera() {
        if (peerConnection == null) throw new IllegalStateException("Stream not running");
        try {
            int current = peerConnection.getFrameRotation();
            int next    = (current + 90) % 360;
            peerConnection.setFrameRotation(next);
            Log.d(TAG, "RotateCamera -> " + next + "deg");
        } catch (Exception e) {
            Log.e(TAG, "RotateCamera failed", e);
            throw e;
        }
    }

    @Override
    public void FlipCamera() {
        // flip applied client-side
    }


    /** Toggle microphone mute. */
    public synchronized void AudioMute(boolean mute) {
        if (peerConnection != null) {
            peerConnection.setAudioEnabled(!mute);
        }
    }

    /** Apply remote SDP received from signaling server. */
    public synchronized void setSdp(JSONObject sdpJson) {
        if (peerConnection == null) return;
        peerConnection.setRemoteDescription(sdpJson);
    }

    /** Apply remote ICE candidate received from signaling server. */
    public synchronized void SetIce(JSONObject iceJson) {
        if (peerConnection == null) return;
        peerConnection.addIceCandidate(iceJson);
    }

    /** Override ICE servers before calling init(). */
    public void setIceServers(List<String> servers) {
        this.iceServers = servers;
    }

    private void startPeerConnection() {
        peerConnection = new WebRtcPeerConnection(context, peerConnectionCallback);
        String error = peerConnection.create(iceServers);
        if (error != null) {
            peerConnection = null;
            throw new IllegalStateException("PeerConnection failed: " + error);
        }
    }

    private void stopPeerConnection() {
        if (peerConnection != null) {
            try { peerConnection.close(); }
            catch (Exception e) { Log.w(TAG, "peerConnection.close()", e); }
            peerConnection = null;
        }
    }

    private void startCapturer() {
        surfaceCapturer = new SurfaceCapturer(
                context,
                webrtcConfig.cameraId,
                webrtcConfig.streamConfig.fpsRange
        );
        surfaceCapturer.initialize(
                peerConnection.getSurfaceTextureHelper(),
                context,
                peerConnection.getRotatingCapturerObserver()
        );
        surfaceCapturer.startCapture(
                webrtcConfig.streamConfig.res.getWidth(),
                webrtcConfig.streamConfig.res.getHeight(),
                webrtcConfig.streamConfig.fpsRange.getUpper()
        );
    }

    private void stopCapturer() {
        if (surfaceCapturer != null) {
            try { surfaceCapturer.stopCapture(); }
            catch (Exception e) { Log.w(TAG, "capturer.stopCapture()", e); }
            surfaceCapturer = null;
        }
    }
    private final WebRtcPeerConnection.Callback peerConnectionCallback =
            new WebRtcPeerConnection.Callback() {

                @Override
                public void onLocalSdpReady(SessionDescription sdp) {
                    try {
                        JSONObject sdpJson = new JSONObject();
                        sdpJson.put("type", sdp.type.canonicalForm());
                        sdpJson.put("sdp",  sdp.description);

                        synchronized (WebRtcMedia.this) {
                            if (sdp.type == SessionDescription.Type.OFFER) {
                                sendOffer(sdpJson);
                            } else {
                                sendAnswer(sdpJson);
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to serialize local SDP", e);
                    }
                }

                @Override
                public void onLocalIceCandidate(IceCandidate candidate) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("sdpMid",        candidate.sdpMid);
                        json.put("sdpMLineIndex", candidate.sdpMLineIndex);
                        json.put("candidate",     candidate.sdp);
                        synchronized (WebRtcMedia.this) { sendIceCandidate(json); }
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to serialize ICE candidate", e);
                    }
                }

                @Override
                public void onConnected() {
                    Log.d(TAG, "WebRTC connected — media flowing");
                    streaming = true;
                    synchronized (WebRtcMedia.this) {
                        if (peerConnection != null) {
                            peerConnection.setBitrate(webrtcConfig.streamConfig.bitrate);
                        }
                    }
                }

                @Override
                public void onDisconnected() {
                    Log.d(TAG, "WebRTC disconnected");
                    streaming = false;
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "PeerConnection error: " + message);
                    if (logger != null) logger.log(TAG + ": " + message);
                }
            };

    private void sendIceCandidate(JSONObject json) {
        try {
            JSONObject root      = new JSONObject();
            JSONObject data      = new JSONObject();
            JSONObject candidate = new JSONObject();

            candidate.put("candidate",     json.getString("candidate"));
            candidate.put("sdpMid",        json.getString("sdpMid"));
            candidate.put("sdpMLineIndex", json.getInt("sdpMLineIndex"));

            data.put("candidate", candidate);

            root.put(TYPE,  RESPONSE);
            root.put(CMD,   WEBRTC_ICE);
            root.put(CMD_ID, 0);
            root.put(DATA,    data);

            Log.d(TAG, "ICE GENERATED");

            connectionManager.send(root);
        } catch (Exception e) {
            Log.e(TAG, "sendIceCandidate failed", e);
            if (logger != null) logger.log(TAG, "sendIceCandidate failed " + e);
        }
    }

    private void sendOffer(JSONObject sdpJson) {
        try {
            JSONObject root = new JSONObject();
            JSONObject data = new JSONObject();
            JSONObject sdp  = new JSONObject();

            sdp.put(TYPE,  sdpJson.getString(TYPE));
            sdp.put("sdp", sdpJson.getString("sdp"));

            data.put("sdp", sdp);

            root.put("type",  RESPONSE);
            root.put("cmd",   WEBRTC_SDP);
            root.put("cmdId", 0);
            root.put(DATA,    data);

            connectionManager.send(root);
        } catch (Exception e) {
            Log.e(TAG, "sendOffer failed", e);
            if (logger != null) logger.log(TAG, "sendOffer failed " + e);
        }
    }

    private void sendAnswer(JSONObject sdpJson) {
        try {
            JSONObject root = new JSONObject();
            JSONObject data = new JSONObject();
            JSONObject sdp  = new JSONObject();

            sdp.put(TYPE,  sdpJson.getString(TYPE));
            sdp.put("sdp", sdpJson.getString("sdp"));

            data.put("sdp", sdp);

            root.put("type",  RESPONSE);
            root.put("cmd",   WEBRTC_SDP);
            root.put("cmdId", 0);
            root.put(DATA,    data);

            connectionManager.send(root);
        } catch (Exception e) {
            Log.e(TAG, "sendAnswer failed", e);
            if (logger != null) logger.log(TAG, "sendAnswer failed " + e);
        }
    }
}