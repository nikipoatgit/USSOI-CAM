package com.github.nikipo.ussoi.media.webrtc;

import static com.github.nikipo.ussoi.ui.UssoiStrings.DATA;
import static com.github.nikipo.ussoi.ui.UssoiStrings.RESPONSE;
import static com.github.nikipo.ussoi.ui.UssoiStrings.TYPE;
import static com.github.nikipo.ussoi.ui.UssoiStrings.WEBRTC_ICE;
import static com.github.nikipo.ussoi.ui.UssoiStrings.WEBRTC_SDP;

import android.content.Context;
import android.util.Log;

import com.github.nikipo.ussoi.media.CameraControl;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.h264.CameraController;
import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.storage.logs.Logging;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

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
 * ***************************************************short**************************
 */

public class WebRtcMedia implements Media, CameraControl {

    private static final String TAG = "WebRtcMedia";

    // Default STUN servers — caller can override via setIceServers() before init()
    private static final List<String> DEFAULT_ICE_SERVERS = Arrays.asList(
            // ---- Google (5 nodes, UDP 19302 + TLS 5349) ----
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
            // ---- Cloudflare ----
            "stun:stun.cloudflare.com:3478",
            // ---- Twilio ----
            "stun:global.stun.twilio.com:3478",
            // ---- Mozilla ----
            "stun:stun.services.mozilla.com:3478",
            // ---- Stunprotocol.org ----
            "stun:stun.stunprotocol.org:3478",
            // ---- VoIP / telecom operators ----
            "stun:stun.voip.blackberry.com:3478",
            "stun:stun.sipnet.net:3478",
            "stun:stun.sipnet.ru:3478",
            "stun:stun.voipstunt.com:3478",
            "stun:stun.zadarma.com:3478"
    );

    private final ConnectionManager connectionManager;


    private int videoWidth       = 1280;
    private int videoHeight      = 720;
    private int videoFps         = 20;
    private int streamBitrateBps = 400_000;
    private int recordBitrateBps = 1_000_000;

    private List<String> iceServers = DEFAULT_ICE_SERVERS;

    // -------------------------------------------------------------------------
    // Long-lived objects (survive across start/stop cycles)
    // -------------------------------------------------------------------------

    private Context context;
    private Logging logger;

    // -------------------------------------------------------------------------
    // Stream-scoped objects (created by StartStream, destroyed by stopStream)
    // -------------------------------------------------------------------------

    private WebRtcCameraSource   cameraSource;
    private WebRtcPeerConnection peerConnection;
    private CameraController     cameraController;

    // -------------------------------------------------------------------------
    // Runtime flags
    // -------------------------------------------------------------------------

    private volatile boolean streaming  = false;
    private volatile boolean audioMuted = false;

    // =========================================================================
    // Constructor
    // =========================================================================

    public WebRtcMedia(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public synchronized void init(Context ctx) {
        if (context != null) {
            // Already initialised — nothing to do.
            return;
        }

        context = ctx.getApplicationContext();
        logger  = Logging.getInstance(context);

        // One-time WebRTC factory + EGL base init (no-op if already done)
        WebRtcPeerConnection.initializeFactory(context);

        Log.d(TAG, "WebRtcMedia init complete — call StartStream() to begin streaming");
    }

    @Override
    public void close() {
        stopStream();
    }

    @Override
    public synchronized String StartStream() {

        if (context == null) {
            Log.e(TAG, "StartStream — call init() first");
            return "Streamer not initialized";
        }

        if (streaming) {
            return "Stream already running";
        }

        stopStream();

        Log.d(TAG, "StartStream — creating peer connection and camera capturer");

        // 1. Peer connection
        peerConnection = new WebRtcPeerConnection(context, peerConnectionCallback);

        String error = peerConnection.create(iceServers);
        if (error != null) {
            peerConnection = null;
            return "Peer connection failed: " + error;
        }

        if (peerConnection.getVideoSource() == null) {
            Log.e(TAG, "StartStream — peer connection creation failed");
            logger.log(TAG + ": StartStream — peerConnection.create() failed");
            peerConnection = null;
            return "Video source creation failed";
        }

        try {
            // 2. Camera source
            cameraSource = new WebRtcCameraSource(
                    context,
                    videoWidth,
                    videoHeight,
                    videoFps,
                    recordBitrateBps);

            cameraSource.start(
                    peerConnection.getRotatingCapturerObserver(),
                    peerConnection.getSurfaceTextureHelper());

            videoWidth = cameraSource.getWidth();
            videoHeight = cameraSource.getHeight();
            videoFps = cameraSource.getFps();

            // 3. Camera controller
            cameraController = new CameraController(context);

        } catch (Exception e) {
            stopStream();
            return "Camera startup failed: " + e.getMessage();
        }

        Log.d(TAG,
                "StartStream — offer sent, waiting for signaling. Config: "
                        + videoWidth + "x" + videoHeight + " @" + videoFps + "fps");

        return null;
    }

    /**
     * Tears down all stream-scoped resources in safe reverse order.
     *
     * <ul>
     *   <li>Recording is stopped and the recorder released.</li>
     *   <li>The camera capturer is stopped and disposed.</li>
     *   <li>The peer connection (including video/audio tracks and
     *       {@link org.webrtc.SurfaceTextureHelper}) is closed.</li>
     *   <li>The camera controller is stopped.</li>
     * </ul>
     *
     * <p>Safe to call when nothing is running. A subsequent {@link #StartStream()}
     * will create everything fresh.
     */
    @Override
    public synchronized String stopStream() {

        if (cameraSource == null &&
                peerConnection == null &&
                cameraController == null) {
            return "Stream already stopped";
        }

        Log.d(TAG, "stopStream — releasing all stream resources");

        StringBuilder errors = new StringBuilder();

        streaming = false;
        audioMuted = false;

        if (cameraController != null) {
            try {
                cameraController.stop();
            } catch (Exception e) {
                Log.w(TAG, "cameraController.stop()", e);
                errors.append("camera controller; ");
            }
            cameraController = null;
        }

        if (cameraSource != null) {
            try {
                cameraSource.stopRecording();
            } catch (Exception e) {
                Log.w(TAG, "stopRecording", e);
                errors.append("stop recording; ");
            }

            try {
                cameraSource.releaseRecorder();
            } catch (Exception e) {
                Log.w(TAG, "releaseRecorder", e);
                errors.append("release recorder; ");
            }

            try {
                cameraSource.stop();
            } catch (Exception e) {
                Log.w(TAG, "cameraSource.stop", e);
                errors.append("camera source; ");
            }

            cameraSource = null;
        }

        if (peerConnection != null) {
            try {
                peerConnection.close();
            } catch (Exception e) {
                Log.w(TAG, "peerConnection.close()", e);
                errors.append("peer connection; ");
            }
            peerConnection = null;
        }

        Log.d(TAG, "stopStream — all resources released");

        if (errors.length() > 0) {
            return "Cleanup issues: " + errors;
        }

        return null; // success
    }
    // =========================================================================
    // Media — stream configuration
    // =========================================================================

    @Override
    public synchronized String SetStreamResolution(int width, int height, int fps) {

        if (cameraSource == null) {
            return "Stream not running";
        }

        if (IsRecording()) {
            return "Cannot change resolution while recording";
        }

        boolean changed = cameraSource.changeCaptureFormat(width, height, fps);

        if (!changed) {
            return "Unsupported resolution or fps";
        }

        videoWidth  = cameraSource.getWidth();
        videoHeight = cameraSource.getHeight();
        videoFps    = cameraSource.getFps();

        return null;
    }

    /**
     * Dynamically adjusts the WebRTC encoding bitrate.
     * Takes effect immediately via RTP sender parameters.
     *
     * @return 0 always.
     */
    @Override
    public synchronized String SetStreamBitrate(int bitrate) {

        if (bitrate <= 0) {
            return "Invalid bitrate";
        }

        streamBitrateBps = bitrate;

        if (peerConnection != null && streaming) {
            try {
                peerConnection.setBitrate(bitrate);
            } catch (Exception e) {
                return "Bitrate update failed";
            }
        }

        return null;
    }

    @Override
    public boolean IsStreaming() {
        return streaming;
    }

    /**
     * Mutes / unmutes the video stream by disabling the local video track.
     * The WebRTC encoder continues running but sends black frames.
     */
    @Override
    public synchronized void StreamMute(boolean mute) {
        if (peerConnection != null) {
            peerConnection.setVideoEnabled(!mute);
        }
    }


    @Override
    public synchronized String RotateCamera() {

        if (cameraSource == null) {
            return "Stream not running";
        }

        try {
            // 1. Advance rotation in the camera source
            int newRotation = cameraSource.rotateVideo();

            // 2. Update frame relay rotation
            if (peerConnection != null) {
                peerConnection.setFrameRotation(newRotation);
            }

            // Sync dimensions
            videoWidth  = cameraSource.getWidth();
            videoHeight = cameraSource.getHeight();

            Log.d(TAG,
                    "RotateCamera → " + newRotation + "°  "
                            + videoWidth + "x" + videoHeight);

            return null;

        } catch (Exception e) {
            Log.e(TAG, "RotateCamera failed", e);
            return "Rotation failed";
        }
    }

    @Override
    public short FlipCamera() {
        return 0; // flip applied on client side
    }

    public synchronized void AudioMute() {
        if (peerConnection != null) {
            peerConnection.setAudioEnabled(audioMuted = !audioMuted);
        }
    }

    // =========================================================================
    // Media — recording
    // =========================================================================

    /**
     * @return  0  started
     *         -1  not initialised (stream not running)
     */
    @Override
    public synchronized String StartRecording() {

        if (cameraSource == null) {
            return "Stream not running";
        }

        if (IsRecording()) {
            return "Recording already active";
        }

        try {
            cameraSource.stopRecording();
            cameraSource.releaseRecorder();
            cameraSource.prepareRecorder();
            cameraSource.startRecording();

            return null;

        } catch (Exception e) {
            Log.e(TAG, "StartRecording failed", e);
            return "Recorder startup failed";
        }
    }

    /**
     * Recording resolution is tied to the capture resolution.
     *
     * @return -1 always (not independently configurable).
     */
    @Override
    public String SetRecordingResolution(int width, int height, int fps) {
        logger.log(TAG +": SetRecordingResolution — recording resolution uses StreamResolution");

        return "Recording resolution follows stream resolution";
    }

    /**
     * Bitrate for the next recording session.
     *
     * @return  0  stored for next session
     *         -1  cannot change while recording is active
     */
    @Override
    public synchronized String SetRecordingBitrate(int bitrate) {

        if (bitrate <= 0) {
            return "Invalid bitrate";
        }

        if (cameraSource != null && cameraSource.isRecordingActive()) {
            logger.log(TAG + ": SetRecordingBitrate — cannot change while recording");
            return "Cannot change bitrate while recording";
        }

        recordBitrateBps = bitrate;
        return null;
    }

    @Override
    public boolean IsRecording() {
        return cameraSource != null && cameraSource.isRecordingActive();
    }

    @Override
    public synchronized String StopRecording() {

        if (cameraSource == null) {
            return "cameraSource is Null";
        }

        if (!cameraSource.isRecordingActive()) {
            return "Recording was not active";
        }

        try {
            cameraSource.stopRecording();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "StopRecording failed", e);
            return "Failed to stop recording";
        }
    }
    // =========================================================================
    // Media — camera
    // =========================================================================

    /**
     * Switches the active camera.
     *
     * @return  0  success
     *         -1  recording active
     *         -2  not initialised
     */
    @Override
    public synchronized String SwitchCamera(int camId) {

        if (cameraSource == null) {
            return "cameraSource is Null";
        }

        try {
            boolean ok = cameraSource.switchCamera();

            if (!ok) {
                return "Camera switch failed";
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "SwitchCamera failed", e);
            return "Camera switch error";
        }
    }

    // =========================================================================
    // Signaling — inbound
    // =========================================================================

    public synchronized void setSdp(JSONObject sdpJson) {
        if (peerConnection == null) return;
        peerConnection.setRemoteDescription(sdpJson);
    }

    public synchronized void SetIce(JSONObject iceJson) {
        if (peerConnection == null) return;
        peerConnection.addIceCandidate(iceJson);
    }

    // =========================================================================
    // CameraControl — delegates to CameraController (manual Camera2 params)
    // =========================================================================

    @Override
    public synchronized void setExposureCompensation(int value) {
        if (cameraController != null) cameraController.setExposureCompensation(value);
    }

    @Override
    public synchronized void enableAutoExposure() {
        if (cameraController != null) cameraController.enableAutoExposure();
    }

    @Override
    public synchronized void disableAutoExposure() {
        if (cameraController != null) cameraController.disableAutoExposure();
    }

    @Override
    public synchronized void setManualExposure(long exposureTimeNs, int iso) {
        if (cameraController != null) cameraController.setManualExposure(exposureTimeNs, iso);
    }

    @Override
    public synchronized void setManualFocus(float diopters) {
        if (cameraController != null) cameraController.setManualFocus(diopters);
    }

    @Override
    public synchronized void enableAutoFocus() {
        if (cameraController != null) cameraController.enableAutoFocus();
    }

    @Override
    public synchronized void apply() {
        if (cameraController != null) cameraController.apply();
    }

    // =========================================================================
    // Configuration setters (call before StartStream)
    // =========================================================================

    public void setIceServers(List<String> servers) {
        this.iceServers = servers;
    }

    // =========================================================================
    // PeerConnection callbacks
    // =========================================================================

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

                        synchronized (WebRtcMedia.this) {
                            sendIceCandidate(json);
                        }
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
                            peerConnection.setBitrate(streamBitrateBps);
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
                    logger.log(TAG + ": " + message);
                }
            };

    // =========================================================================
    // Signaling — outbound helpers
    // =========================================================================

    private void sendIceCandidate(JSONObject json) {
        try {
            JSONObject root      = new JSONObject();
            JSONObject data      = new JSONObject();
            JSONObject candidate = new JSONObject();

            candidate.put("candidate",     json.getString("candidate"));
            candidate.put("sdpMid",        json.getString("sdpMid"));
            candidate.put("sdpMLineIndex", json.getInt("sdpMLineIndex"));

            data.put("candidate", candidate);

            root.put("type",  RESPONSE);
            root.put("cmd",   WEBRTC_ICE);
            root.put("cmdId", 0);
            root.put(DATA,    data);

            connectionManager.send(root);

        } catch (Exception e) {
            logger.logMsg(TAG, "sendIceCandidate failed " + e);
            Log.e(TAG, "sendIceCandidate failed", e);
        }
    }

    private void sendOffer(JSONObject sdpJson) {
        try {
            JSONObject root = new JSONObject();
            JSONObject data = new JSONObject();
            JSONObject sdp  = new JSONObject();

            sdp.put("type", sdpJson.getString("type"));
            sdp.put("sdp",  sdpJson.getString("sdp"));

            data.put("sdp", sdp);

            root.put("type",  RESPONSE);
            root.put("cmd",   WEBRTC_SDP);
            root.put("cmdId", 0);
            root.put(DATA,    data);

            connectionManager.send(root);

        } catch (Exception e) {
            Log.e(TAG, "sendOffer failed", e);
            logger.logMsg(TAG, "sendOffer failed " + e);
        }
    }

    private void sendAnswer(JSONObject sdpJson) {
        try {
            JSONObject root = new JSONObject();
            JSONObject data = new JSONObject();
            JSONObject sdp  = new JSONObject();

            sdp.put(TYPE,  sdpJson.getString("type"));
            sdp.put("sdp", sdpJson.getString("sdp"));

            data.put("sdp", sdp);

            root.put("type",  RESPONSE);
            root.put("cmd",   WEBRTC_SDP);
            root.put("cmdId", 0);
            root.put(DATA,    data);

            connectionManager.send(root);

        } catch (Exception e) {
            logger.logMsg(TAG, "sendAnswer failed " + e);
            Log.e(TAG, "sendAnswer failed", e);
        }
    }
}