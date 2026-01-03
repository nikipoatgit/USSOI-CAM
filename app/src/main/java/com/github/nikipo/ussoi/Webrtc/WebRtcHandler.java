package com.github.nikipo.ussoi.Webrtc;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_turn_array;
import static com.github.nikipo.ussoi.ServicesManager.ConnRouter.sendAck;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import com.github.nikipo.ussoi.MacroServices.WebSocketHandler;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebRtcHandler {

    private static final String TAG = "WebRtcHandler";
    private static WebRtcHandler instance;

    private final Context context;
    private final Gson gson = new Gson();

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;

    private EglBase rootEgl;
    private SurfaceTextureHelper surfaceTextureHelper;

    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;

    private VideoCapturer videoCapturer;
    private CameraVideoCapturer cameraVideoCapturer;
    private MediaRecorderSink mediaRecorder;
    private SharedPreferences prefs;
    private SaveInputFields saveInputFields;
    private WebSocketHandler webSocketHandler;
    private RotatingCapturerObserver rotatingCapturerObserver;
    private RtpSender videoSender;
    private RtpSender audioSender;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int currentRotation = 0;
    private boolean isRecording = false;

    /* ===================== Singleton ===================== */

    private WebRtcHandler(Context context, WebSocketHandler webSocketHandler) {
        this.context = context.getApplicationContext();
        this.webSocketHandler = webSocketHandler;

        saveInputFields = SaveInputFields.getInstance(context);
        this.prefs = saveInputFields.get_shared_pref();
    }

    public static synchronized WebRtcHandler getInstance(Context context, WebSocketHandler webSocketHandler) {
        if (instance == null) {
            instance = new WebRtcHandler(context, webSocketHandler);
        }
        return instance;
    }

    /* ===================== Init ===================== */

    public void init() {
        isRecording = false;
        rootEgl = EglBase.create();

        JavaAudioDeviceModule audioDeviceModule =
                JavaAudioDeviceModule.builder(context).createAudioDeviceModule();


        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                        .builder(context)
                        .createInitializationOptions()
        );


        // 3. Configure Options
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableEncryption = false;
        options.disableNetworkMonitor = true;


        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(rootEgl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(rootEgl.getEglBaseContext()))
                .createPeerConnectionFactory();

    }

    /* ===================== PeerConnection ===================== */

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();

        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.services.mozilla.com:3478").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.stunprotocol.org:3478").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.nextcloud.com:3478").createIceServer());

        String turn_Array = prefs.getString(KEY_turn_array,"");
        try {
            JSONArray turnArray = new JSONArray(turn_Array);

            for (int i = 0; i < turnArray.length(); i++) {
                JSONObject t = turnArray.optJSONObject(i);
                if (t == null) continue;

                String url = t.optString("url", "").trim();
                if (url.isEmpty()) continue;

                PeerConnection.IceServer.Builder builder =
                        PeerConnection.IceServer.builder(url);

                String user = t.optString("user", "").trim();
                String pass = t.optString("pass", "").trim();

                if (!user.isEmpty() && !pass.isEmpty()) {
                    builder.setUsername(user);
                    builder.setPassword(pass);
                }

                iceServers.add(builder.createIceServer());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid TURN JSON in prefs", e);
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("type", "ice");
                    payload.put("candidate", iceCandidate.sdp);
                    payload.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    payload.put("sdpMid", iceCandidate.sdpMid);
                    webSocketHandler.connSendPayload(payload);
                } catch (Exception e) {
                    Log.e(TAG, "ICE error", e);
                }
            }

            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { Log.d(TAG, "ICE: " + state); }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) { Log.d(TAG, "Connection: " + newState); }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(MediaStream mediaStream) {}
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
        });
    }

    /* ===================== Media ===================== */

    public void startStream(int width, int height, int fps) {
        if (factory == null) return;

        videoCapturer = createCameraCapturer(context);
        if (videoCapturer instanceof CameraVideoCapturer) {
            cameraVideoCapturer = (CameraVideoCapturer) videoCapturer;
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEgl.getEglBaseContext());
        VideoSource videoSource = factory.createVideoSource(videoCapturer.isScreencast());

        rotatingCapturerObserver = new RotatingCapturerObserver(videoSource.getCapturerObserver(), 0);
        videoCapturer.initialize(surfaceTextureHelper, context, rotatingCapturerObserver);
        videoCapturer.startCapture(width, height, fps);

        localVideoTrack = factory.createVideoTrack("VIDEO_TRACK_ID", videoSource);
        localAudioTrack = factory.createAudioTrack("AUDIO_TRACK_ID", factory.createAudioSource(new MediaConstraints()));

    }

    private VideoCapturer createCameraCapturer(Context context) {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context) ? new Camera2Enumerator(context) : new Camera1Enumerator(true);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isBackFacing(name)) return enumerator.createCapturer(name, null);
        }
        return enumerator.createCapturer(enumerator.getDeviceNames()[0], null);
    }

    public void createOffer() {
        if (peerConnection == null) createPeerConnection();

        List<String> streamIds = Collections.singletonList("ARDAMS");

        if (localVideoTrack != null) {
            // THIS IS THE CRITICAL FIX
            videoSender = peerConnection.addTrack(localVideoTrack, streamIds);
        }

        if (localAudioTrack != null) {
            // THIS IS THE CRITICAL FIX
            audioSender = peerConnection.addTrack(localAudioTrack, streamIds);
        }
        peerConnection.createOffer(new SdpObserverAdapter("Offer") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(this, sdp);
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("type", "sdp");
                    payload.put("sdp", sdp.description);
                    webSocketHandler.connSendPayload(payload);
                } catch (Exception e) {
                    Log.e(TAG, "SDP error", e);
                }
            }
        }, getSdpConstraints());
    }

    public void handleAnswer(String sdp) {
        peerConnection.setRemoteDescription(new SdpObserverAdapter("RemoteAnswer"),
                new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    public void handleRemoteIceCandidate(JSONObject json) {
        if (peerConnection == null) return;
        try {
            peerConnection.addIceCandidate(new IceCandidate(
                    json.getString("sdpMid"),
                    json.getInt("sdpMLineIndex"),
                    json.getString("candidate")));
        } catch (Exception e) {
            Log.e(TAG, "Remote ICE error", e);
        }
    }

    /* ===================== Controls ===================== */

    public void changeCaptureFormat(int width, int height, int fps,int reqId) {
        if (videoCapturer != null && !isRecording) {
            sendAck("ack", reqId, "Resolution Changed ");
            videoCapturer.changeCaptureFormat(width, height, fps);
        }
    }

    public void rotateOutgoingVideo() {
        if (rotatingCapturerObserver != null && !isRecording) {
            currentRotation = (currentRotation + 90) % 360;
            rotatingCapturerObserver.setRotation(currentRotation);
        }
    }

    public void switchCamera() {
        if (cameraVideoCapturer != null && !isRecording) cameraVideoCapturer.switchCamera(null);
    }
    /**
     * Mutes the stream to the remote peer, but KEEPS the camera active
     * so the local recording continues uninterrupted.
     */
    public void toggleVideo(boolean muteStream) {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            if (videoSender != null) {
                // CHANGE 1: takeOwnership must be FALSE.
                // We (the Java class) own the track, not the sender.
                boolean takeOwnership = false;

                videoSender.setTrack(muteStream ? null : localVideoTrack, takeOwnership);
                Log.d(TAG, "Video toggled. Muted to remote: " + muteStream);
            } else {
                Log.e(TAG, "Cannot toggle Video: VideoSender is null");
            }
        });
    }

    public void toggleAudio(boolean unmuteStream) {
        if (executor.isShutdown()) return; // Safety check

        try {
            executor.execute(() -> {
                if (localAudioTrack != null) {
                    localAudioTrack.setEnabled(unmuteStream);
                    Log.d(TAG, "Audio toggled. Muted to remote: " + !unmuteStream);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to toggle audio: Service is stopping", e);
        }
    }

    public void setVideoBitrate(int bitratebps, int reqId) {
        if (peerConnection == null || localVideoTrack == null) return;
        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() != null && sender.track().id().equals(localVideoTrack.id())) {
                RtpParameters p = sender.getParameters();
                if (!p.encodings.isEmpty()) {
                    sendAck("ack", reqId, "Bitrate Changed ");
                    p.encodings.get(0).maxBitrateBps = bitratebps;
                    sender.setParameters(p);
                }
            }
        }
    }

    private MediaConstraints getSdpConstraints() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        return c;
    }

    /* ===================== recording Handel ==================*/
    public void startLocalRecording(int reqId) {

        if (localVideoTrack == null || rootEgl == null || isRecording) return;

        // Create the combined recorder
        mediaRecorder = new MediaRecorderSink(
                rootEgl.getEglBaseContext(),context
        );

        mediaRecorder.start();
        localVideoTrack.addSink(mediaRecorder);
        isRecording = true;
        sendAck("ack", reqId, "Recording started ");
        Log.d(TAG, "AV Recording started");
    }
    public boolean isRecordingActive(){
        if (mediaRecorder == null){
            return false;
        }
        return  mediaRecorder.isRecordingActive();
    }

    // --------------------------------------------------------------------------------------------------

    public void stopAllServices() {
        isRecording = false;
        if (mediaRecorder != null) {
            try {
                localVideoTrack.removeSink(mediaRecorder);
                mediaRecorder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recorder", e);
            }
            mediaRecorder = null;
            Log.d(TAG, "AV Recording stopped");
        }

        // 1. Shutdown the executor
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow(); // Use shutdownNow to force interrupt
        }

        // 2. Cleanup WebRTC objects
        try { if (videoCapturer != null) videoCapturer.stopCapture(); } catch (Exception ignored) {}

        // Dispose in correct order (Capturer -> Source -> Track -> PeerConnection -> Factory -> Surface)
        if (videoCapturer != null) {
            videoCapturer.dispose();
            videoCapturer = null;
        }

        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }

        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }

        if (peerConnection != null) {
            peerConnection.close(); // Close before dispose
            peerConnection.dispose();
            peerConnection = null;
        }

        if (factory != null) {
            factory.dispose();
            factory = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (rootEgl != null) {
            rootEgl.release();
            rootEgl = null;
        }

        //  Reset the Singleton instance
        instance = null;

        Log.d(TAG, "WebRTC Services completely stopped and instance reset.");
    }
}

class SdpObserverAdapter implements SdpObserver {
    private final String logTag;
    public SdpObserverAdapter(String tag) { this.logTag = "SdpObserver:" + tag; }
    @Override public void onCreateSuccess(SessionDescription s) { Log.d(logTag, "Success"); }
    @Override public void onSetSuccess() { Log.d(logTag, "Set Success"); }
    @Override public void onCreateFailure(String s) { Log.e(logTag, "Failure: " + s); }
    @Override public void onSetFailure(String s) { Log.e(logTag, "Set Failure: " + s); }
}