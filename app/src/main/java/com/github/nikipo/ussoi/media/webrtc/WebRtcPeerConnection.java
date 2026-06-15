package com.github.nikipo.ussoi.media.webrtc;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file WebRtcPeerConnection
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

public final class WebRtcPeerConnection {

    private static final String TAG           = "WebRtcPeerConn";
    private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private static final String STREAM_ID      = "ARDAMS";

    // -------------------------------------------------------------------------
    // Callback
    // -------------------------------------------------------------------------

    public interface Callback {
        /** Local SDP (offer/answer) is ready to be sent over signaling. */
        void onLocalSdpReady(SessionDescription sdp);
        /** A new local ICE candidate is ready to be trickled to the remote. */
        void onLocalIceCandidate(IceCandidate candidate);
        /** ICE gathering + DTLS handshake succeeded — media is flowing. */
        void onConnected();
        /** Peer connection was closed or failed. */
        void onDisconnected();
        /** An error occurred inside the peer connection. */
        void onError(String message);
    }

    // -------------------------------------------------------------------------
    // Factory — initialized once per process
    // -------------------------------------------------------------------------

    private static volatile PeerConnectionFactory factory  = null;
    private static volatile EglBase               eglBase  = null;

    /**
     * Must be called once (e.g. in {@code Application.onCreate}) before any
     * {@link WebRtcPeerConnection} is created.
     */
    public static synchronized void initializeFactory(Context context) {
        if (factory != null) return;

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                        .builder(context.getApplicationContext())
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());

        eglBase = EglBase.create();

        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                        new DefaultVideoEncoderFactory(
                                eglBase.getEglBaseContext(),
                                true,   // enableIntelVp8Encoder
                                true))  // enableH264HighProfile
                .setVideoDecoderFactory(
                        new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        Log.d(TAG, "PeerConnectionFactory initialized");
    }

    /** Returns the shared EGL context (needed by {@link SurfaceTextureHelper}). */
    public static EglBase.Context getEglContext() {
        if (eglBase == null) throw new IllegalStateException("Call initializeFactory() first");
        return eglBase.getEglBaseContext();
    }

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final Context  context;
    private final Callback callback;

    private PeerConnection       peerConnection;
    private VideoSource          videoSource;
    private VideoTrack           localVideoTrack;
    private SurfaceTextureHelper surfaceTextureHelper;
    private AudioSource audioSource;
    private AudioTrack audioTrack;

    /** Current logical rotation (0/90/180/270) stamped onto every outgoing frame. */
    private volatile int frameRotation = 0;

    /**
     * The capturer initialises against this sink instead of videoSource directly.
     * Each frame has its rotation metadata rewritten before being forwarded — zero
     * pixel-data copy, just a wrapper VideoFrame object.
     */
    private org.webrtc.VideoSink rotatingSink;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public WebRtcPeerConnection(Context context, Callback callback) {
        this.context  = context.getApplicationContext();
        this.callback = callback;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public synchronized String create(List<String> iceServers) {
        if (factory == null) {
            return "WebRtcPeerConnection factory not initialized";
        }

        List<PeerConnection.IceServer> servers = new ArrayList<>();
        for (String uri : iceServers) {
            servers.add(PeerConnection.IceServer.builder(uri).createIceServer());
        }

        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(servers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(
                rtcConfig,
                peerConnectionObserver
        );

        if (peerConnection == null) {
            return "PeerConnection creation failed";
        }

        try {
            surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread", getEglContext());

            videoSource = factory.createVideoSource(false);
            localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
            localVideoTrack.setEnabled(true);

            rotatingSink = frame -> {
                int rot = frameRotation;
                if (rot != 0) {
                    VideoFrame rotated = new VideoFrame(
                            frame.getBuffer(),
                            (frame.getRotation() + rot) % 360,
                            frame.getTimestampNs());
                    videoSource.getCapturerObserver().onFrameCaptured(rotated);
                    rotated.release();
                } else {
                    videoSource.getCapturerObserver().onFrameCaptured(frame);
                }
            };

            audioSource = factory.createAudioSource(new MediaConstraints());
            audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource);
            audioTrack.setEnabled(true);

            List<String> streamIds = Collections.singletonList(STREAM_ID);
            peerConnection.addTrack(localVideoTrack, streamIds);
            peerConnection.addTrack(audioTrack, streamIds);

            Log.d(TAG, "PeerConnection created");
            return null; // success

        } catch (Exception e) {
            return "Initialization failed: " + e.getMessage();
        }
    }

    public VideoSource getVideoSource() {
        return videoSource;
    }

    /**
     * Returns the {@link SurfaceTextureHelper} used by the video source.
     * The capturer needs this to post frames onto the correct EGL context.
     */
    public SurfaceTextureHelper getSurfaceTextureHelper() {
        return surfaceTextureHelper;
    }

    /**
     * Returns a {@link org.webrtc.VideoCapturer.CapturerObserver} that wraps the
     * real video-source observer with rotation metadata injection.
     *
     * <p>Pass this to {@link org.webrtc.Camera2Capturer#initialize} instead of
     * {@code videoSource.getCapturerObserver()} so every frame is stamped with the
     * current {@link #frameRotation} before reaching the encoder.
     */
    public org.webrtc.CapturerObserver getRotatingCapturerObserver() {
        return new org.webrtc.CapturerObserver() {
            @Override
            public void onCapturerStarted(boolean success) {
                videoSource.getCapturerObserver().onCapturerStarted(success);
            }
            @Override
            public void onCapturerStopped() {
                videoSource.getCapturerObserver().onCapturerStopped();
            }
            @Override
            public void onFrameCaptured(org.webrtc.VideoFrame frame) {
                int rot = frameRotation;
                if (rot != 0) {
                    org.webrtc.VideoFrame rotated = new org.webrtc.VideoFrame(
                            frame.getBuffer(),
                            (frame.getRotation() + rot) % 360,
                            frame.getTimestampNs());
                    videoSource.getCapturerObserver().onFrameCaptured(rotated);
                    rotated.release();
                } else {
                    videoSource.getCapturerObserver().onFrameCaptured(frame);
                }
            }
        };
    }

    /**
     * Updates the rotation (degrees) stamped on every outgoing video frame.
     * Safe to call at any time — takes effect on the very next captured frame.
     * Does NOT require stopping the capturer or the recorder.
     *
     * @param degrees 0, 90, 180, or 270.
     */
    public void setFrameRotation(int degrees) {
        this.frameRotation = degrees;
        Log.d(TAG, "frameRotation set to " + degrees + "°");
    }

    /** Releases all WebRTC resources. Safe to call multiple times. */
    public synchronized void close() {
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(false);
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        if (audioTrack != null) {
            audioTrack.setEnabled(false);
            audioTrack.dispose();
            audioTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }
        Log.d(TAG, "PeerConnection closed and all resources released");
    }

    // -------------------------------------------------------------------------
    // SDP negotiation
    // -------------------------------------------------------------------------

    /**
     * Creates an SDP offer and delivers it via
     * {@link Callback#onLocalSdpReady(SessionDescription)}.
     */
    public synchronized void createOffer() {
        if (peerConnection == null) return;
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(sdpObserver, constraints);
    }


    public synchronized void setRemoteDescription(JSONObject json) {
        if (peerConnection == null) return;

        try {
            JSONObject sdpJson = json.getJSONObject("sdp");

            String typeStr = sdpJson.getString("type");
            String sdpStr  = sdpJson.getString("sdp");

            SessionDescription.Type type =
                    SessionDescription.Type.fromCanonicalForm(typeStr);

            SessionDescription sdp =
                    new SessionDescription(type, sdpStr);

            peerConnection.setRemoteDescription(new SdpObserver() {

                @Override
                public void onCreateSuccess(SessionDescription sd) {}

                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "Remote description set: " + typeStr);

                    if (type == SessionDescription.Type.OFFER) {
                        createAnswer();
                    }
                }

                @Override
                public void onCreateFailure(String s) {}

                @Override
                public void onSetFailure(String s) {
                    callback.onError("setRemoteDescription failed: " + s);
                }

            }, sdp);

        } catch (Exception e) {
            callback.onError(
                    "setRemoteDescription exception: " + e.getMessage()
            );
        }
    }

    public synchronized void addIceCandidate(JSONObject json) {
        if (peerConnection == null) return;

        try {
            JSONObject candidateJson = json.getJSONObject("candidate");

            IceCandidate candidate = new IceCandidate(
                    candidateJson.getString("sdpMid"),
                    candidateJson.getInt("sdpMLineIndex"),
                    candidateJson.getString("candidate")
            );

            peerConnection.addIceCandidate(candidate);

            Log.d(TAG, "ICE candidate added");

        } catch (Exception e) {
            Log.e(TAG, "addIceCandidate failed", e);

            callback.onError(
                    "addIceCandidate exception: " + e.getMessage()
            );
        }
    }

    // -------------------------------------------------------------------------
    // Runtime control
    // -------------------------------------------------------------------------

    /**
     * Dynamically adjusts the video encoding bitrate via RTP sender parameters.
     *
     * <p><b>Root-cause note:</b> {@code getParameters()} returns an object whose
     * {@code encodings} list is <em>empty</em> until ICE + DTLS negotiation has
     * completed and the encoder is running. Calling {@code setParameters()} with an
     * empty list silently succeeds but changes nothing. The guard below logs a
     * warning and bails out in that case; the bitrate is stored in
     * {@link WebRtcMedia#streamBitrateBps} and re-applied by
     * {@link WebRtcMedia.peerConnectionCallback#onConnected()} once the connection
     * is fully established.
     *
     * <p>Both {@code minBitrateBps} and {@code maxBitrateBps} are set — omitting
     * the minimum allows the encoder to clamp the bitrate to its own floor, which
     * can make a high {@code maxBitrateBps} appear to have no effect.
     *
     * @param bitrateBps Target bitrate in bits per second.
     */
    public synchronized void setBitrate(int bitrateBps) {
        if (peerConnection == null) return;
        try {
            for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
                if (transceiver.getMediaType() !=
                        org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) continue;

                RtpSender sender = transceiver.getSender();
                org.webrtc.RtpParameters params = sender.getParameters();

                // Guard: encodings is empty before negotiation completes.
                // onConnected() will call setBitrate() again once the encoder is live.
                if (params.encodings.isEmpty()) {
                    Log.w(TAG, "setBitrate(" + bitrateBps
                            + "): encodings list is empty — negotiation not complete yet, skipping");
                    return;
                }

                for (org.webrtc.RtpParameters.Encoding enc : params.encodings) {
                    // Set both floor and ceiling so the encoder doesn't clamp down
                    enc.minBitrateBps = bitrateBps / 2;
                    enc.maxBitrateBps = bitrateBps;
                }

                boolean ok = sender.setParameters(params);
                Log.d(TAG, "setBitrate(" + bitrateBps + " bps) → setParameters=" + ok
                        + "  encodings=" + params.encodings.size());
                break;
            }
        } catch (Exception e) {
            Log.e(TAG, "setBitrate failed", e);
        }
    }

    /**
     * Enables or disables the local video track (soft mute — encoder keeps
     * running but the track is black).
     */
    public synchronized void setVideoEnabled(boolean enabled) {
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(enabled);
        }
    }

    public synchronized void setAudioEnabled(boolean enabled) {
        if (audioTrack != null) {
            audioTrack.setEnabled(enabled);
        }
    }

    // -------------------------------------------------------------------------
    // Private — SDP answer
    // -------------------------------------------------------------------------

    private void createAnswer() {
        if (peerConnection == null) return;

        // After setRemoteDescription the transceivers exist — force SEND_ONLY
        // on any transceiver that has a live sender track. This is what makes
        // the answer say a=sendonly instead of a=inactive.
        for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
            RtpSender sender = transceiver.getSender();
            if (sender != null && sender.track() != null) {
                transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
            }
        }

        peerConnection.createAnswer(sdpObserver, new MediaConstraints());
    }

    // -------------------------------------------------------------------------
    // SDP observer — shared for offer + answer creation
    // -------------------------------------------------------------------------

    private final SdpObserver sdpObserver = new SdpObserver() {
        @Override
        public void onCreateSuccess(SessionDescription sdp) {
            synchronized (WebRtcPeerConnection.this) {
                if (peerConnection == null) return;

                // Set local description, then notify caller
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription sd) {}
                    @Override public void onSetSuccess() {
                        Log.d(TAG, "Local description set: " + sdp.type);
                        callback.onLocalSdpReady(sdp);
                    }
                    @Override public void onCreateFailure(String s) {}
                    @Override public void onSetFailure(String s) {
                        callback.onError("setLocalDescription failed: " + s);
                    }
                }, sdp);
            }
        }

        @Override public void onSetSuccess()             {}
        @Override public void onCreateFailure(String s)  { callback.onError("createSdp failed: " + s); }
        @Override public void onSetFailure(String s)     { callback.onError("setSdp failed: " + s);    }
    };

    // -------------------------------------------------------------------------
    // PeerConnection observer
    // -------------------------------------------------------------------------

    private final PeerConnection.Observer peerConnectionObserver =
            new PeerConnection.Observer() {

                @Override
                public void onIceCandidate(IceCandidate candidate) {
                    Log.d(TAG, "New local ICE candidate: " + candidate.sdp);
                    callback.onLocalIceCandidate(candidate);
                }

                @Override
                public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                    Log.d(TAG, "Connection state: " + state);
                    switch (state) {
                        case CONNECTED:
                            callback.onConnected();
                            break;
                        case FAILED:
                        case CLOSED:
                            callback.onDisconnected();
                            break;
                        default:
                            break;
                    }
                }

                // ---- Unused overrides ----
                @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
                @Override public void onIceConnectionReceivingChange(boolean b) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                @Override public void onAddStream(MediaStream s) {}
                @Override public void onRemoveStream(MediaStream s) {}
                @Override public void onDataChannel(DataChannel d) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
            };
}