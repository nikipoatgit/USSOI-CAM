package com.github.nikipo.ussoi.H264;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class StreamingEncoder {

    private MediaCodec encoder;
    private Surface inputSurface;
    private MediaCodec.BufferInfo bufferInfo;

    private boolean started;

    /**
     * Prepares the encoder and returns the input Surface.
     * Must be called BEFORE Camera2 session creation.
     */
    public Surface prepare(
            int width,
            int height,
            int fps,
            int bitrate
    ) throws IOException {

        MediaFormat format =
                MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        width,
                        height
                );

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        format.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);

        format.setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31);

        format.setInteger(
                MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES,
                1
        );

        encoder = MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
        );

        encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        inputSurface = encoder.createInputSurface();
        bufferInfo = new MediaCodec.BufferInfo();

        return inputSurface;
    }

    /** Starts the encoder */
    public void start() {
        encoder.start();
        started = true;
    }

    /**
     * Pulls encoded H.264 data.
     * Call repeatedly from a background thread.
     */
    public EncodedFrame dequeue() {
        if (!started) return null;

        int index = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
        if (index < 0) return null;

        ByteBuffer buffer = encoder.getOutputBuffer(index);
        ByteBuffer copy = ByteBuffer.allocate(bufferInfo.size);
        buffer.position(bufferInfo.offset);
        buffer.limit(bufferInfo.offset + bufferInfo.size);
        copy.put(buffer);
        copy.flip();

        boolean isKeyFrame =
                (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

        long pts = bufferInfo.presentationTimeUs;

        encoder.releaseOutputBuffer(index, false);

        return new EncodedFrame(copy, pts, isKeyFrame);
    }

    /** Stops encoding */
    public void stop() {
        encoder.stop();
        started = false;
    }

    /** Releases native resources */
    public void release() {
        encoder.release();
        encoder = null;
        inputSurface = null;
    }

    public void setBitrate(int newBitrate) {
        if (encoder == null || !started) return;
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate);
        encoder.setParameters(params);
    }
    /** Encoder input surface for Camera2 */
    public Surface getInputSurface() {
        return inputSurface;
    }

    /* ---------- DATA HOLDER ---------- */

    public static final class EncodedFrame {
        public final ByteBuffer data;
        public final long ptsUs;
        public final boolean keyFrame;

        EncodedFrame(ByteBuffer d, long pts, boolean kf) {
            data = d;
            ptsUs = pts;
            keyFrame = kf;
        }
    }
}
