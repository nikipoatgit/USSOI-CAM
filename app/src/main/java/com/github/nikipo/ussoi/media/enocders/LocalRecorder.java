package com.github.nikipo.ussoi.media.enocders;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import androidx.documentfile.provider.DocumentFile;

import com.github.nikipo.ussoi.storage.SaveInputFields;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LocalRecorder implements AutoCloseable {
    private final String TAG = "LocalRecorder";
    private final Context context;
    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private static SaveInputFields saveInputFields;
    private static SharedPreferences prefs;
    public volatile boolean isRecording = false;
    private String HeaderName;

    public LocalRecorder(Context ctx,String HeaderName) {
        this.context = ctx.getApplicationContext();
        this.HeaderName = HeaderName;

        saveInputFields = SaveInputFields.getInstance(ctx);
        prefs = saveInputFields.get_shared_pref();
    }

    public Surface prepare(
            int width,
            int height,
            int fps,
            int bitrate
    ) throws IOException {

        recorder = new MediaRecorder();
//
//        recorder.setOnErrorListener((mr, what, extra) ->
//                Log.e(TAG,
//                        "MediaRecorder error what=" + what +
//                                " extra=" + extra));
//
//        recorder.setOnInfoListener((mr, what, extra) ->
//                Log.d(TAG,
//                        "MediaRecorder info what=" + what +
//                                " extra=" + extra));

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);

        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        pfd = createVideoFilePfdInternal();
        recorder.setOutputFile(pfd.getFileDescriptor());

        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(fps);
        recorder.setVideoEncodingBitRate(bitrate);

        recorder.setAudioEncodingBitRate(128_000);
        recorder.setAudioSamplingRate(44_100);
        recorder.setAudioChannels(1);

        recorder.prepare();

        return recorder.getSurface();
    }

    public void start() {
        recorder.start();
        isRecording = true;
    }

    @Override
    public void close() {

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }

            recorder.release();
            recorder = null;
        }

        isRecording = false;

        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            pfd = null;
        }
    }

    private ParcelFileDescriptor createVideoFilePfdInternal() throws IOException {

        String uriStr = prefs.getString(SaveInputFields.PREF_LOG_URI, null);
        if (uriStr == null) {
            throw new IllegalStateException("SAF permission missing");
        }

        Uri treeUri = Uri.parse(uriStr);

        DocumentFile root =
                DocumentFile.fromTreeUri(context, treeUri);

        if (root == null || !root.canWrite()) {
            throw new SecurityException("No write access to SAF tree");
        }

        DocumentFile videosDir = root.findFile("videos");

        if (videosDir == null || !videosDir.isDirectory()) {
            throw new IllegalStateException("videos folder missing");
        }

        String fileName = buildSessionLogFileName() + ".mp4";

        DocumentFile videoFile =
                videosDir.createFile("video/mp4", fileName);

        if (videoFile == null) {
            throw new IOException("Failed to create video file");
        }

        return context.getContentResolver()
                .openFileDescriptor(videoFile.getUri(), "rw");
    }

    private String buildSessionLogFileName() {

        Date now = new Date();

        String timePart =
                new SimpleDateFormat("h_mm_a", Locale.US)
                        .format(now);

        String day =
                new SimpleDateFormat("d", Locale.US)
                        .format(now);

        String suffix =
                getDaySuffix(Integer.parseInt(day));

        String datePart =
                new SimpleDateFormat(
                        "d'" + suffix + "'_MMMM_yyyy",
                        Locale.US
                ).format(now);

        String precisionPart =
                new SimpleDateFormat("ss.SSS", Locale.US)
                        .format(now);

        return HeaderName +"__"
                + timePart
                + "__"
                + datePart
                + "__"
                + precisionPart;
    }

    private String getDaySuffix(int day) {

        if (day >= 11 && day <= 13) {
            return "th";
        }

        switch (day % 10) {
            case 1:
                return "st";

            case 2:
                return "nd";

            case 3:
                return "rd";

            default:
                return "th";
        }
    }

}