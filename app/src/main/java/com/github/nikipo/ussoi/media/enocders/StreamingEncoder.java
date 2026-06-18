package com.github.nikipo.ussoi.media.enocders;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class StreamingEncoder {
    private final Object lock = new Object();
    private MediaCodec encoder;
    private Surface inputSurface;
    private MediaCodec.BufferInfo bufferInfo;

    public volatile boolean isStreaming = false;

    // Cache array for SPS/PPS metadata (pre-API 29)
    private byte[] cachedConfigData = null;

    // Reusable output buffer to eliminate per-frame allocations
    private ByteBuffer reusableDirectBuffer;

    public Surface prepare(int width, int height, int fps, int bitrate) throws IOException {
        synchronized (lock) {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            inputSurface = encoder.createInputSurface();
            bufferInfo = new MediaCodec.BufferInfo();

            // Pre-allocate a generous buffer (e.g., 2MB) to prevent runtime allocations.
            // Adjust size based on your max expected target resolution/bitrate.
            reusableDirectBuffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);

            return inputSurface;
        }
    }

    public void start() {
        synchronized (lock) {
            if (encoder == null) {
                throw new IllegalStateException("Encoder not prepared");
            }
            encoder.start();
            isStreaming = true;
        }
    }

    public EncodedFrame dequeue() {
        // 1. Grab a local reference to the encoder to drop the lock during the blocking call
        MediaCodec localEncoder;
        synchronized (lock) {
            if (!isStreaming) return null;
            localEncoder = encoder;
        }

        if (localEncoder == null) return null;

        // 2. Blocking call happens OUTSIDE the lock.
        // Other threads can now call close() or setBitrate() without being blocked for 10ms.
        int index = localEncoder.dequeueOutputBuffer(bufferInfo, 10_000);

        if (index < 0) return null;

        EncodedFrame frame = null;

        // 3. Re-acquire lock only to process the output buffer and safely read state
        synchronized (lock) {
            // Check if we were closed while waiting for the buffer
            if (encoder == null || !isStreaming) {
                localEncoder.releaseOutputBuffer(index, false);
                return null;
            }

            ByteBuffer buffer = localEncoder.getOutputBuffer(index);

            if (buffer != null && bufferInfo.size > 0) {
                buffer.position(bufferInfo.offset);
                buffer.limit(bufferInfo.offset + bufferInfo.size);

                boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                if (isConfig) {
                    // Lazy initialization or resize if config is unexpectedly larger
                    if (cachedConfigData == null || cachedConfigData.length < bufferInfo.size) {
                        cachedConfigData = new byte[bufferInfo.size];
                    }
                    buffer.get(cachedConfigData, 0, bufferInfo.size);
                } else {
                    int requiredSize = bufferInfo.size;
                    boolean prependConfig = isKeyFrame && cachedConfigData != null;

                    if (prependConfig) {
                        requiredSize += cachedConfigData.length;
                    }

                    // Ensure our reusable buffer has enough capacity (fallback resize)
                    if (reusableDirectBuffer.capacity() < requiredSize) {
                        reusableDirectBuffer = ByteBuffer.allocateDirect(requiredSize * 2);
                    }

                    reusableDirectBuffer.clear();

                    if (prependConfig) {
                        reusableDirectBuffer.put(cachedConfigData);
                    }
                    reusableDirectBuffer.put(buffer);
                    reusableDirectBuffer.flip();

                    // Note: EncodedFrame now wraps a slice of the reusable buffer.
                    // The caller must consume this frame before the next call to dequeue().
                    frame = new EncodedFrame(
                            reusableDirectBuffer.duplicate(),
                            bufferInfo.presentationTimeUs,
                            isKeyFrame
                    );
                }
            }

            localEncoder.releaseOutputBuffer(index, false);
        }

        return frame;
    }

    public void pauseStreaming() {
        isStreaming = false;
    }

    public void resumeStreaming() {
        isStreaming = true;
    }

    public void setBitrate(int bitrate) {
        synchronized (lock) {
            if (encoder == null) return;

            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            encoder.setParameters(params);
        }
    }

    public void close() {
        synchronized (lock) {
            isStreaming = false;
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {}
                try {
                    encoder.release();
                } catch (Exception ignored) {}
                encoder = null;
            }
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            bufferInfo = null;
            cachedConfigData = null;
            reusableDirectBuffer = null;
        }
    }

    public static final class EncodedFrame {
        public final ByteBuffer data;
        public final long ptsUs;
        public final boolean keyFrame;

        EncodedFrame(ByteBuffer data, long ptsUs, boolean keyFrame) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.keyFrame = keyFrame;
        }
    }
}