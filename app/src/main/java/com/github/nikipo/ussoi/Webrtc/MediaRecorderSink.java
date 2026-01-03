package com.github.nikipo.ussoi.Webrtc;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_LocalVideoBitrate;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import androidx.core.app.ActivityCompat;
import androidx.documentfile.provider.DocumentFile;

import com.github.nikipo.ussoi.MacroServices.SaveInputFields;

import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.RendererCommon;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import android.media.AudioFormat;
import android.media.MediaRecorder;


public class MediaRecorderSink implements VideoSink {
    private static final String TAG = "MediaRecorderSink";

    private final EglBase.Context sharedContext;
    private final Context context;
    private final FileDescriptor fd;
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private MediaMuxer muxer;
    private Surface encoderInputSurface;
    private EglBase encoderEgl;
    private VideoFrameDrawer frameDrawer;
    private RendererCommon.GlDrawer drawer;
    private HandlerThread processingThread;
    private Handler processingHandler;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private volatile boolean isRunning = false;
    private final AtomicBoolean recordingFlag = new AtomicBoolean(false);
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean muxerStarted = false;
    private long startTimeNs = -1;
    private long totalAudioSamples = 0;
    private static SaveInputFields saveInputFields;
    private static SharedPreferences prefs;
    private ParcelFileDescriptor pfd;

    public MediaRecorderSink(EglBase.Context sharedContext, Context ctx) {
        this.context = ctx;
        this.sharedContext = sharedContext;
        saveInputFields = SaveInputFields.getInstance(ctx);
        prefs = saveInputFields.get_shared_pref();


        FileDescriptor tmpFd = null;

        try {
                pfd = createVideoFilePfdInternal();
                tmpFd = pfd.getFileDescriptor();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create output target", e);
        }

        this.fd = tmpFd;
    }

    private ParcelFileDescriptor createVideoFilePfdInternal() throws IOException {
        String uriStr = prefs.getString(SaveInputFields.PREF_LOG_URI, null);
        if (uriStr == null) {
            throw new IllegalStateException("SAF permission missing");
        }

        Uri treeUri = Uri.parse(uriStr);
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.canWrite()) {
            throw new SecurityException("No write access to SAF tree");
        }

        DocumentFile videosDir = root.findFile("videos");
        if (videosDir == null || !videosDir.isDirectory()) {
            throw new IllegalStateException("videos folder missing");
        }

        String name = buildSessionLogFileName() + ".mp4";
        DocumentFile videoFile = videosDir.createFile("video/mp4", name);
        if (videoFile == null) {
            throw new IOException("File creation failed");
        }

        return context
                .getContentResolver()
                .openFileDescriptor(videoFile.getUri(), "rw");
    }
    private String buildSessionLogFileName() {
        Date now = new Date();

        String timePart = new SimpleDateFormat("h_mm_a", Locale.US).format(now);

        String day = new SimpleDateFormat("d", Locale.US).format(now);
        String suffix = getDaySuffix(Integer.parseInt(day));
        String datePart = new SimpleDateFormat("d'" + suffix + "'_MMMM_yyyy", Locale.US).format(now);

        String precisionPart = new SimpleDateFormat("ss.SSS", Locale.US).format(now);

        return "WebRtc__" + timePart + "__" + datePart + "__" + precisionPart;
    }
    private String getDaySuffix(int day) {
        if (day >= 11 && day <= 13) return "th";
        switch (day % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }


    public void start() {
        if (isRunning) return;
        isRunning = true;
        recordingFlag.set(false);

        processingThread = new HandlerThread("MediaRecorderThread");
        processingThread.start();
        processingHandler = new Handler(processingThread.getLooper());

        startAudioCapture();
    }

    public void stop() throws IOException {
        if (!isRunning) return;
        isRunning = false;
        recordingFlag.set(false);
        if (pfd != null) {
            pfd.close();
            pfd = null;
        }

        if (processingHandler != null) {
            processingHandler.post(() -> {
                releaseResources();
                processingThread.quitSafely();
            });
        }
    }
    @Override
    public void onFrame(VideoFrame frame) {
        if (!isRunning) return;
        frame.retain();
        recordingFlag.set(true);

        if (processingHandler != null) {
            processingHandler.post(() -> {
                if (!isRunning) { frame.release(); return; }

                // 1. Lazy Initialization: Config encoder based on ACTUAL camera frame size
                if (videoEncoder == null) {
                    try {
                        // Supports Portrait/Landscape automatically
                        int w = frame.getRotatedWidth();
                        int h = frame.getRotatedHeight();
                        Log.d(TAG, "Initializing recorder with detected resolution: " + w + "x" + h);
                        initEncoders(w, h);
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to init encoders", e);
                        frame.release();
                        return;
                    }
                }

                if (startTimeNs == -1) {
                    startTimeNs = frame.getTimestampNs();
                    totalAudioSamples = 0; // Reset audio counter
                }

                drawVideoFrame(frame);
                frame.release();
                drainCodec(videoEncoder, false);
            });
        }
    }

    private void startAudioCapture() {
        audioRunning = false; //default

        int sampleRate = 48000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        int bufferSize = AudioRecord.getMinBufferSize(
                sampleRate, channelConfig, audioFormat);

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio recording permission not granted");
            return;
        }
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
        );

        audioRunning = true;
        audioRecord.startRecording();

        audioThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (audioRunning) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && processingHandler != null) {
                    byte[] pcm = new byte[read];
                    System.arraycopy(buffer, 0, pcm, 0, read);
                    processingHandler.post(() -> {
                        if (audioEncoder != null && startTimeNs != -1) {
                            feedAudioEncoder(pcm, sampleRate);
                            drainCodec(audioEncoder, false);
                        }
                    });
                }
            }
        }, "RecorderAudioThread");

        audioThread.start();
    }


    private void initEncoders(int width, int height) throws IOException {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+
            muxer = new MediaMuxer(
                    fd, // FileDescriptor from ParcelFileDescriptor
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
        } else {
            File root = Environment.getExternalStorageDirectory();
            File ussoiDir = new File(root, "ussoi/videos");
            if (!ussoiDir.exists()) ussoiDir.mkdirs();

            File out = new File(ussoiDir, buildSessionLogFileName() + ".mp4");
            String legacyOutputPath = out.getAbsolutePath();


            muxer = new MediaMuxer(
                    legacyOutputPath, // absolute filesystem path
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
        }


        if (width % 2 != 0) width--;
        if (height % 2 != 0) height--;
        // --- Video Setup ---
        int kbps = prefs.getInt(KEY_LocalVideoBitrate, 4000);
        MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", width, height);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000); // 2Mbps
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        videoEncoder = MediaCodec.createEncoderByType("video/avc");
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();

        // --- EGL Setup ---
        encoderEgl = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE);
        encoderEgl.createSurface(encoderInputSurface);
        encoderEgl.makeCurrent();
        frameDrawer = new VideoFrameDrawer();
        drawer = new GlRectDrawer();

        // --- Audio Setup ---
        if (audioRunning) {
            MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000); // 64kbps

            audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
        }
    }

    private void drawVideoFrame(VideoFrame frame) {
        frameDrawer.drawFrame(
                frame,
                drawer,
                null,
                0, 0,
                encoderEgl.surfaceWidth(),
                encoderEgl.surfaceHeight()
        );
        // Fix: Use swapBuffers(timestamp) to ensure A/V sync
        encoderEgl.swapBuffers(frame.getTimestampNs());
    }

    private void feedAudioEncoder(byte[] pcmData, int sampleRate) {
        if (audioEncoder == null || startTimeNs == -1) return;

        ByteBuffer pcmBuffer = ByteBuffer.wrap(pcmData);

        while (pcmBuffer.hasRemaining()) {
            int index = audioEncoder.dequeueInputBuffer(10000);
            if (index < 0) return;

            ByteBuffer in = audioEncoder.getInputBuffer(index);
            assert in != null;
            in.clear();

            int toWrite = Math.min(pcmBuffer.remaining(), in.remaining());

            int oldLimit = pcmBuffer.limit();
            pcmBuffer.limit(pcmBuffer.position() + toWrite);
            in.put(pcmBuffer);
            pcmBuffer.limit(oldLimit);

            long ptsUs = (startTimeNs / 1000) +
                    (totalAudioSamples * 1_000_000L / sampleRate);

            int samplesWritten = toWrite / 2; // mono, 16-bit
            totalAudioSamples += samplesWritten;

            audioEncoder.queueInputBuffer(
                    index,
                    0,
                    toWrite,
                    ptsUs,
                    0
            );
        }
    }

    private void drainCodec(MediaCodec codec, boolean endOfStream) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int encoderStatus = codec.dequeueOutputBuffer(bufferInfo, 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                if (codec == videoEncoder) videoTrackIndex = muxer.addTrack(newFormat);
                else audioTrackIndex = muxer.addTrack(newFormat);

                boolean videoReady = videoTrackIndex != -1;
                boolean audioReady = (audioTrackIndex != -1) || !audioRunning;

                if (videoReady && audioReady && !muxerStarted) {
                    muxer.start();
                    muxerStarted = true;
                    Log.d(TAG, "Muxer started");
                }
            } else if (encoderStatus >= 0) {
                ByteBuffer encodedData = codec.getOutputBuffer(encoderStatus);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0;

                if (bufferInfo.size != 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(codec == videoEncoder ? videoTrackIndex : audioTrackIndex, encodedData, bufferInfo);
                }
                codec.releaseOutputBuffer(encoderStatus, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void releaseResources() {
        try {
            audioRunning = false;
            if (audioThread != null) {
                try {
                    audioThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (audioRecord != null) {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
                audioRecord = null;
            }
            if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); videoEncoder = null; }
            if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); audioEncoder = null; }
            if (muxer != null) {
                if (muxerStarted) muxer.stop();
                muxer.release();
                muxer = null;
            }
            if (encoderEgl != null) { encoderEgl.release(); encoderEgl = null; }
            if (frameDrawer != null) { frameDrawer.release(); frameDrawer = null; }
            if (drawer != null) { drawer.release(); drawer = null; }
            if (encoderInputSurface != null) { encoderInputSurface.release(); encoderInputSurface = null; }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup failed", e);
        }
    }

    public boolean isRecordingActive() {
        return recordingFlag.getAndSet(false);
    }

}