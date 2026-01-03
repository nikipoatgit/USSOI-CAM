package com.github.nikipo.ussoi.ServicesManager;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_Session_KEY;
import static com.github.nikipo.ussoi.ServicesManager.ConnRouter.sendAck;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_mse_Enable;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_local_recording;
import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_webrtc_Enable;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.documentfile.provider.DocumentFile;

import com.github.nikipo.ussoi.H264.FrameStreamController;
import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.github.nikipo.ussoi.MacroServices.WebSocketHandler;
import com.github.nikipo.ussoi.Webrtc.WebRtcHandler;
import org.json.JSONObject;

import java.io.File;

public class StreamRoute {
    private static final String TAG = "StreamRoute";
    private static WebRtcHandler webRtcHandler;
    private static FrameStreamController frameStreamController;
    private static SharedPreferences prefs;
    private static Logging logger;
    private static Context context;
    private static volatile boolean stream = false;
    private static volatile boolean record = false;

    public static void init(Context ctx) {
        logger = Logging.getInstance(ctx);
        SaveInputFields saveInputFields = SaveInputFields.getInstance(ctx);
        prefs = saveInputFields.get_shared_pref();
        context = ctx;
    }

    public static void initWebrtc(WebSocketHandler webSocketHandler) {
        webRtcHandler = WebRtcHandler.getInstance(context, webSocketHandler);
        webRtcHandler.init();
        webRtcHandler.startStream(1280, 720, 30);
        webRtcHandler.createOffer();
        stream = true;
    }

    public static void initMse() {
        frameStreamController = FrameStreamController.getInstance(context);
        frameStreamController.init(prefs.getString(KEY_Session_KEY, "block"));
        frameStreamController.startStreaming();
        stream = true;
    }


    public static boolean isRecording(){
        if (stream){
            if(webRtcHandler != null){
                record = webRtcHandler.isRecordingActive();
            } else if (frameStreamController != null) {
                record =frameStreamController.isRecordingActive();
            }
        }
        return record;
    }
    public static void stopStream() {
        if (frameStreamController != null) {
            frameStreamController.stopAllServices();
            frameStreamController = null;
        }
        if (webRtcHandler != null) {
            webRtcHandler.stopAllServices();
            webRtcHandler = null;
        }

        if (Build.VERSION.SDK_INT >= 26) {
            deleteSmallFilesSaf();
        } else {
            deleteSmallFilesLegacy();
        }

        stream = false;
        record = false;
    }

    public static boolean isStreamRunning() {
        return stream;
    }

    public static void webrtcControl(JSONObject json, SharedPreferences prefs) {
        int reqId = json.optInt("reqId", -1);
        if (webRtcHandler != null && prefs.getBoolean(KEY_webrtc_Enable, false)) {
            if (json.has("sdp")) {
                webRtcHandler.handleAnswer(json.optString("sdp"));
            } else if (json.has("candidate")) {
                webRtcHandler.handleRemoteIceCandidate(json);
            } else if (json.has("video")) {
                webRtcHandler.toggleVideo(json.optBoolean("video", true));
            } else if (json.has("audio")) {
                webRtcHandler.toggleAudio(json.optBoolean("audio", false));
            } else if (json.has("switch")) {
                webRtcHandler.switchCamera();
            } else if (json.has("rotate")) {
                webRtcHandler.rotateOutgoingVideo();
            } else if (json.has("res")) {
                JSONObject quality = json.optJSONObject("quality");
                if (quality != null) {
                    int width = quality.optInt("width", 1200);
                    int height = quality.optInt("height", 720);
                    int fps = quality.optInt("fps", 20);
                    if (width <= 0 || height <= 0 || fps <= 0) {
                        logger.log(TAG + "Invalid (non-positive) quality params: " +
                                "w=" + width +
                                " h=" + height +
                                " fps=" + fps);
                        sendAck("nack", json.optInt("reqId", -1), "Invalid (non-positive)");
                        return;
                    }
                    webRtcHandler.changeCaptureFormat(width, height, fps,reqId);
                }
            } else if (json.has("bitrate")) {
                webRtcHandler.setVideoBitrate(json.optInt("bitrate"),reqId);
            } else if (json.has("record") && json.optBoolean("record", false)) {
                if (prefs.getBoolean(KEY_local_recording, false))
                    webRtcHandler.startLocalRecording(reqId);
                else {
                    sendAck("nack", json.optInt("reqId", -1), "Local Recording Off");
                }
            }


        }
    }

    public static void mseControl(JSONObject json, SharedPreferences prefs) {
        int reqId = json.optInt("reqId", -1);
        if (frameStreamController != null && prefs.getBoolean(KEY_mse_Enable, false)) {
            if (json.has("video")) {
                frameStreamController.toggleVideo(json.optBoolean("video", true),reqId);
            } else if (json.has("switch")) {
                frameStreamController.toggleCamera();
            } else if (json.has("res")) {
                JSONObject quality = json.optJSONObject("quality");
                if (quality != null) {
                    int width = quality.optInt("width", 1200);
                    int height = quality.optInt("height", 720);
                    int fps = quality.optInt("fps", 20);
                    if (width <= 0 || height <= 0 || fps <= 0) {
                        logger.log(TAG + "Invalid (non-positive) quality params: " +
                                "w=" + width +
                                " h=" + height +
                                " fps=" + fps);
                        sendAck("nack", json.optInt("reqId", -1), "Invalid (non-positive)");

                        return;
                    }
                    frameStreamController.changeCaptureFormat(width, height, fps,reqId);
                }
            } else if (json.has("fps")) {
                int fps = json.optInt("fps", 20);
                if (fps < 1) {
                    sendAck("nack", json.optInt("reqId", -1), "Fps Can't be less than 1");
                }
                int frameIntervalMs = 1000 / fps;
                frameStreamController.ChangeFrameInterval(frameIntervalMs);
            } else if (json.has("bitrate")) {
                int bitrate = json.optInt("bitrate");
                if ( bitrate <= 0 ) {
                    logger.log(TAG + "Invalid (non-positive) quality params: " +
                            " br=" + bitrate);
                    sendAck("nack", json.optInt("reqId", -1), "Invalid (non-positive)");
                    return;
                }
                frameStreamController.setVideoBitrate(bitrate,reqId);
            } else if (json.has("resRecord")) {
                JSONObject quality = json.optJSONObject("quality");
                if (quality != null) {
                    int width = quality.optInt("width", 1200);
                    int height = quality.optInt("height", 720);
                    int bitrate = quality.optInt("bitrate");
                    int fps = quality.optInt("fps", 20);
                    if (width <= 0 || height <= 0 || bitrate <= 0 || fps <= 0) {
                        logger.log(TAG + "Invalid (non-positive) quality params: " +
                                "w=" + width +
                                " h=" + height +
                                " fps=" + fps +
                                " br=" + bitrate);
                        sendAck("nack", json.optInt("reqId", -1), "Invalid (non-positive)");
                        return;
                    }
                    frameStreamController.setRecordingParams(width, height, bitrate, fps,reqId);

                }
            } else if (json.has("record") && json.optBoolean("record", false)) {
                if (prefs.getBoolean(KEY_local_recording, false))
                    frameStreamController.startRecording(json.optInt("reqId", -1));
                else {
                    sendAck("nack", json.optInt("reqId", -1), "Local Recording is Off");
                }
            }
        }
    }


    private static void deleteSmallFilesSaf() {
        String uriStr = prefs.getString(SaveInputFields.PREF_LOG_URI, null);
        if (uriStr == null) return;

        Uri treeUri = Uri.parse(uriStr);
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null) return;

        DocumentFile videosDir = root.findFile("videos");
        if (videosDir == null || !videosDir.isDirectory()) return;

        for (DocumentFile f : videosDir.listFiles()) {
            long size = f.length(); // may return -1 on some providers
            if (size >= 0 && size < 5 * 1024) {
                f.delete();
            }
        }
    }

    private static void deleteSmallFilesLegacy() {
        File root = Environment.getExternalStorageDirectory();
        File videosDir = new File(root, "ussoi/videos");

        if (!videosDir.exists() || !videosDir.isDirectory()) return;

        File[] files = videosDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile() && f.length() < 1024) {
                f.delete();
            }
        }
    }




    // end of class
}
