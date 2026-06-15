package com.github.nikipo.ussoi.media.enocders;

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
        public volatile boolean isStreaming = false;
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

            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            );

            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

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

        public void start() {
            if (encoder == null) {
                throw new IllegalStateException("Encoder not prepared");
            }
            encoder.start();
            isStreaming = true;
        }

        public EncodedFrame dequeue() {

            int index =
                    encoder.dequeueOutputBuffer(
                            bufferInfo,
                            10_000
                    );

            if (index < 0) {
                return null;
            }

            ByteBuffer buffer =
                    encoder.getOutputBuffer(index);

            EncodedFrame frame = null;

            if (buffer != null &&
                    isStreaming &&
                    bufferInfo.size > 0) {

                ByteBuffer copy =
                        ByteBuffer.allocate(bufferInfo.size);

                buffer.position(bufferInfo.offset);
                buffer.limit(bufferInfo.offset + bufferInfo.size);

                copy.put(buffer);
                copy.flip();

                boolean keyFrame =
                        (bufferInfo.flags &
                                MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                frame = new EncodedFrame(
                        copy,
                        bufferInfo.presentationTimeUs,
                        keyFrame
                );
            }

            encoder.releaseOutputBuffer(index, false);

            return frame;
        }

        public void pauseStreaming() {
            isStreaming = false;
        }
        public void resumeStreaming() {
            isStreaming = true;
        }

        public void setBitrate(int bitrate) {

            if (encoder == null) {
                return;
            }

            Bundle params = new Bundle();
            params.putInt(
                    MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,
                    bitrate
            );

            encoder.setParameters(params);
        }

        public void close() {

            if (encoder != null) {

                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }

                encoder.release();
                encoder = null;
            }
            isStreaming = false;

            inputSurface = null;
            bufferInfo = null;
        }

        public static final class EncodedFrame {

            public final ByteBuffer data;
            public final long ptsUs;
            public final boolean keyFrame;

            EncodedFrame(
                    ByteBuffer data,
                    long ptsUs,
                    boolean keyFrame
            ) {
                this.data = data;
                this.ptsUs = ptsUs;
                this.keyFrame = keyFrame;
            }
        }

}
