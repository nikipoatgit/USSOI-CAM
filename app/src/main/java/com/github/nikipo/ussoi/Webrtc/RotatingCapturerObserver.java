package com.github.nikipo.ussoi.Webrtc;

import org.webrtc.CapturerObserver;
import org.webrtc.VideoFrame;

public final class RotatingCapturerObserver implements CapturerObserver {

    private final CapturerObserver downstreamObserver;
    private volatile int rotationAngle; // 0,90,180,270

    public RotatingCapturerObserver(CapturerObserver downstreamObserver, int initialRotation) {
        this.downstreamObserver = downstreamObserver;
        this.rotationAngle = normalize(initialRotation);
    }

    public void setRotation(int angle) {
        this.rotationAngle = normalize(angle);
    }

    private int normalize(int angle) {
        angle %= 360;
        if (angle < 0) angle += 360;
        if (angle % 90 != 0) {
            throw new IllegalArgumentException("Rotation must be multiple of 90");
        }
        return angle;
    }

    @Override
    public void onCapturerStarted(boolean success) {
        downstreamObserver.onCapturerStarted(success);
    }

    @Override
    public void onCapturerStopped() {
        downstreamObserver.onCapturerStopped();
    }

    @Override
    public void onFrameCaptured(VideoFrame frame) {
        int newRotation = (frame.getRotation() + rotationAngle) % 360;

        VideoFrame rotatedFrame = new VideoFrame(
                frame.getBuffer(),
                newRotation,
                frame.getTimestampNs()
        );

        downstreamObserver.onFrameCaptured(rotatedFrame);

    }
}
