package com.github.nikipo.ussoi.media.webrtc;

import android.content.Context;
import android.util.Log;

import com.github.nikipo.ussoi.media.CameraControl;
import com.github.nikipo.ussoi.media.Media;
import com.github.nikipo.ussoi.media.camera.CameraController;
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
 * *****************************************************************************
 */


/**
 * WebRTC implementation of {@link Media} and {@link CameraControl}.
 *
 * <p>Architecture (all components live in this package):
 * <pre>
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │                    WebRtcMedia                       │
 *   │  (orchestrator — implements Media + CameraControl)   │
 *   └──────────┬────────────────────┬──────────────────────┘
 *              │                    │
 *   ┌──────────▼──────────┐  ┌──────▼────────────────────┐
 *   │  WebRtcCameraSource  │  │  WebRtcPeerConnection     │
 *   │  (Camera2Capturer    │  │  (PeerConnectionFactory,  │
 *   │   + LocalRecorder)   │  │   SDP, ICE, bitrate)      │
 *   └──────────────────────┘  └──────────-────────────────┘
 *
 *
 *   Re-used from H264Media:
 *     • CameraHelper  — camera ID cycling, resolution + FPS queries
 *     • CameraController — Camera2 exposure / focus controls (manual-mode)
 *     • LocalRecorder — local MP4 recording
 * </pre>
 *
 * <p><b>Streaming lifecycle:</b>
 * <ol>
 *   <li>{@link #init(Context)} — initialises WebRTC factory, camera source,
 *       peer connection, and signaling channel.</li>
 *   <li>{@link #StartStream()} — creates an SDP offer; the signaling client
 *       exchanges it with the remote peer; once ICE+DTLS completes, media flows.</li>
 *   <li>{@link #stop()} — tears everything down in reverse order.</li>
 * </ol>
 *
 * <p><b>Thread safety:</b> All public methods are {@code synchronized}. Callbacks
 * from signaling and the peer connection arrive on foreign threads and are
 * dispatched safely.
 *
 * <p><b>Manual camera controls (CameraControl):</b> WebRTC's {@link org.webrtc.Camera2Capturer}
 * does not expose Camera2 manual controls. A separate {@link CameraController}
 * instance is used purely for applying exposure / focus parameters to the same
 * physical camera; it shares no state with the capturer's Camera2 session.
 * Manual controls are therefore only advisory — apply() triggers a best-effort
 * parameter push.
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

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private int    videoWidth       = 1280;
    private int    videoHeight      = 720;
    private int    videoFps         = 30;
    private int    streamBitrateBps = 1_500_000;
    private int    recordBitrateBps = 8_000_000;

    private List<String> iceServers = DEFAULT_ICE_SERVERS;

    // -------------------------------------------------------------------------
    // Components
    // -------------------------------------------------------------------------

    private Context               context;
    private Logging               logger;

    private WebRtcCameraSource    cameraSource;
    private WebRtcPeerConnection  peerConnection;

    /**
     * Reused from H264Media — drives Camera2 manual exposure / focus controls.
     * Lifecycle is kept separate from the WebRTC capturer session.
     */
    private CameraController      cameraController;

    // -------------------------------------------------------------------------
    // Runtime flags
    // -------------------------------------------------------------------------

    private volatile boolean streaming  = false;
    private volatile boolean audioMuted      = false;

    public WebRtcMedia(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }


    // =========================================================================
    // Media — lifecycle
    // =========================================================================

    @Override
    public synchronized void init(Context ctx) {
        context = ctx.getApplicationContext();
        logger  = Logging.getInstance(context);

        // 1. One-time WebRTC factory init (no-op if already done)
        WebRtcPeerConnection.initializeFactory(context);

        // 2. Camera source (Camera2Capturer + optional LocalRecorder)
        cameraSource = new WebRtcCameraSource(
                context,
                videoWidth, videoHeight, videoFps,
                recordBitrateBps);

        // Prepare recorder surface (recording starts only on StartRecording())
        cameraSource.prepareRecorder();

        // 3. Peer connection
        peerConnection = new WebRtcPeerConnection(context, peerConnectionCallback);
        peerConnection.create(iceServers);

        // Sync resolved dimensions back (capturer may have snapped to hardware sizes)
        videoWidth  = cameraSource.getWidth();
        videoHeight = cameraSource.getHeight();
        videoFps    = cameraSource.getFps();

        // Start camera capture into the WebRTC video source
        cameraSource.start(
                peerConnection.getVideoSource(),
                peerConnection.getSurfaceTextureHelper());

        // 5. CameraController for manual exposure/focus (shared camera ID)
        cameraController = new CameraController(context);

        Log.d(TAG, "WebRtcMedia initialised: "
                + videoWidth + "x" + videoHeight + " @" + videoFps + "fps");
    }

    @Override
    public synchronized void stop() {
        streaming = false;
        audioMuted     = false;

        // Tear down in reverse init order
        if (cameraController != null) {
            cameraController.stop();
            cameraController = null;
        }

        if (cameraSource != null) {
            cameraSource.stopRecording();
            cameraSource.releaseRecorder();
            cameraSource.stop();
            cameraSource = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        Log.d(TAG, "WebRtcMedia stopped");
    }

    // =========================================================================
    // Media — streaming
    // =========================================================================

    /**
     * Initiates WebRTC streaming by creating an SDP offer.
     * The actual media flow begins once ICE + DTLS negotiate successfully
     * (signaled via {@link WebRtcPeerConnection.Callback#onConnected()}).
     *
     * @return 0 on success, -1 if not initialized.
     */
    @Override
    public synchronized short StartStream() {
        if (peerConnection == null) {
            logger.log(TAG + ": StartStream — not initialized");
            return -1;
        }
        if (streaming) return 0; // already streaming

        peerConnection.createOffer();
        // streaming = true is set inside peerConnectionCallback.onConnected()
        return 0;
    }

    /**
     * Changes the video capture resolution and FPS.
     * Delegates to {@link WebRtcCameraSource#changeCaptureFormat} — no full
     * restart required; Camera2Capturer handles the format change internally.
     *
     * @return  0  success
     *         -1  recording is active (stop it first)
     *         -2  not initialized
     */
    @Override
    public synchronized short SetStreamResolution(int width, int height, int fps) {
        if (cameraSource == null) return -2;

        if (IsRecording()){return  -3;}


        boolean changed = cameraSource.changeCaptureFormat(width, height, fps);
        if (!changed) return -1;

        videoWidth  = cameraSource.getWidth();
        videoHeight = cameraSource.getHeight();
        videoFps    = cameraSource.getFps();
        return 0;
    }

    /**
     * Dynamically adjusts the WebRTC encoding bitrate.
     * Takes effect immediately via RTP sender parameters.
     *
     * @return 0 always.
     */
    @Override
    public synchronized short SetStreamBitrate(int bitrate) {
        streamBitrateBps = bitrate;
        if (peerConnection != null && streaming) {
            peerConnection.setBitrate(bitrate);
        }
        return 0;
    }

    @Override
    public boolean IsStreaming() {
        return streaming;
    }

    /**
     * Mutes / unmutes the video stream by disabling the local video track.
     * The WebRTC encoder continues running but sends black frames — consistent
     * with how {@code H264Media.StreamMute} works via {@link CameraController#pauseStreaming()}.
     */
    @Override
    public synchronized void StreamMute(boolean mute) {
        if (peerConnection != null) {
            peerConnection.setVideoEnabled(!mute);
        }
    }

    // =========================================================================
    // Media — recording
    // =========================================================================

    /**
     * @return  0  started (or already recording)
     *         -1  not initialized
     */
    @Override
    public synchronized short StartRecording() {
        if (cameraSource == null) return -1;
        cameraSource.stopRecording();
        cameraSource.releaseRecorder();
        cameraSource.prepareRecorder();
        cameraSource.startRecording();
        return 0;
    }

    /**
     * Recording resolution is tied to the capture resolution (same as
     * {@code HighFpsH246Media} — one Camera2 session for both).
     *
     * @return -1 always (not independently configurable).
     */
    @Override
    public short SetRecordingResolution(int width, int height, int fps) {
        logger.log(TAG + ": SetRecordingResolution — recording resolution matches stream; use SetStreamResolution");
        return -1;
    }

    /**
     * Bitrate for the next recording session.
     *
     * @return  0  stored for next session
     *         -1  cannot change while recording is active
     */
    @Override
    public synchronized short SetRecordingBitrate(int bitrate) {
        if (cameraSource != null && cameraSource.isRecordingActive()) {
            logger.log(TAG + ": SetRecordingBitrate — cannot change while recording");
            return -1;
        }
        recordBitrateBps = bitrate;
        return 0;
    }

    @Override
    public boolean IsRecording() {
        return cameraSource != null && cameraSource.isRecordingActive();
    }

    @Override
    public synchronized void StopRecording() {
        if (cameraSource != null) cameraSource.stopRecording();
    }

    // =========================================================================
    // Media — camera
    // =========================================================================

    /**
     * Switches the active camera.
     *
     * @return  0  success
     *         -1  recording active
     *         -2  not initialized
     */
    @Override
    public synchronized short SwitchCamera() {
        if (cameraSource == null) return -2;
        boolean ok = cameraSource.switchCamera();
        return ok ? (short) 0 : (short) -1;
    }

    /** Rotation is applied on the receiver/host side. */
    @Override
    public short RotateCamera() {
        return 0;
    }

    /** Flip is applied on the receiver/host side. */
    @Override
    public short FlipCamera() {
        return 0;
    }

    public synchronized void AudioMute() {
        if (peerConnection != null) {
            peerConnection.setAudioEnabled(audioMuted = !audioMuted);
        }
    }

    @Override
    public synchronized JSONObject SupportedResolutions() {
        if (cameraSource == null) return new JSONObject();
        return cameraSource.getCameraHelper()
                .SupportedResolutions(cameraSource.getCurrentCameraId());
    }

    // SDP set
    public synchronized void setSdp(JSONObject sdpJson) {
        if (peerConnection == null) return;

        peerConnection.setRemoteDescription(sdpJson);
    }
    // ICE set
    public synchronized void SetIce(JSONObject iceJson) {
        if (peerConnection == null) return;

        peerConnection.addIceCandidate(iceJson);
    }

    // CameraControl — delegates to CameraController (manual Camera2 params)
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


    private final WebRtcPeerConnection.Callback peerConnectionCallback =
            new WebRtcPeerConnection.Callback() {

                @Override
                public void onLocalSdpReady(SessionDescription sdp) {
                    // Forward local SDP to remote peer via signaling channel
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
                    // Trickle ICE candidate to remote peer
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
                    // Apply initial bitrate constraint once connected
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

    private void sendIceCandidate(JSONObject json) {
        try {
            JSONObject root = new JSONObject();
            root.put("type", "data");
            root.put("cmd", "webrtc_ice");
            root.put("timestamp", System.currentTimeMillis() / 1000);

            JSONObject payload = new JSONObject();
            payload.put("candidate",     json.getString("candidate"));
            payload.put("sdpMid",        json.getString("sdpMid"));
            payload.put("sdpMLineIndex", json.getInt("sdpMLineIndex"));

            root.put("payload", payload);

            connectionManager.send(root);

        } catch (Exception e) {
            logger.logMsg(TAG, "sendIceCandidate failed " + e);
            Log.e(TAG, "sendIceCandidate failed", e);
        }
    }

    private void sendOffer(JSONObject sdpJson) {
        try {
            JSONObject root = new JSONObject();
            root.put("type", "data");
            root.put("cmd", "webrtc_offer");
            root.put("timestamp", System.currentTimeMillis() / 1000);

            JSONObject payload = new JSONObject();
            payload.put("sdp", sdpJson.getString("sdp"));

            root.put("payload", payload);

            connectionManager.send(root);

        } catch (Exception e) {
            Log.e(TAG, "sendOffer failed", e);
            logger.logMsg(TAG, "sendOffer failed " + e);
        }
    }

    private void sendAnswer(JSONObject sdpJson) {
        try {
            JSONObject root = new JSONObject();
            root.put("type", "data");
            root.put("cmd", "webrtc_answer");
            root.put("timestamp", System.currentTimeMillis() / 1000);
            root.put("deviceId", "abc");

            JSONObject payload = new JSONObject();
            payload.put("sdp", sdpJson.getString("sdp"));

            root.put("payload", payload);

            connectionManager.send(root);

        } catch (Exception e) {
            logger.logMsg(TAG, "sendAnswer failed " + e);
            Log.e(TAG, "sendAnswer failed", e);
        }
    }

    public void setIceServers(List<String> servers) {
//        this.iceServers = servers;
    }
}
