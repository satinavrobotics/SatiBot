package com.satinavrobotics.satibot.livekit.stream;

import android.content.Context;
import androidx.annotation.NonNull;

import io.livekit.android.room.track.video.CameraCapturerWithSize;
import io.livekit.android.room.track.video.CameraEventsDispatchHandler;
import livekit.org.webrtc.CameraVideoCapturer;
import livekit.org.webrtc.CapturerObserver;
import livekit.org.webrtc.Size;
import livekit.org.webrtc.SurfaceTextureHelper;

public class ArCameraCapturerWithSize extends CameraCapturerWithSize implements CameraVideoCapturer {
    private final ArCameraCapturer capturer;
    private final String deviceName;

    public ArCameraCapturerWithSize(ArCameraCapturer capturer, String deviceName, CameraEventsDispatchHandler cameraEventsDispatchHandler) {
        super(cameraEventsDispatchHandler);
        this.capturer = capturer;
        this.deviceName = deviceName;
    }

    @NonNull
    @Override
    public Size findCaptureFormat(int i, int i1) {
        return new Size(1920, 1080);
    }

    // Delegating methods from CameraVideoCapturer to capturer
    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver capturerObserver) {
        capturer.initialize(surfaceTextureHelper, applicationContext, capturerObserver);
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        capturer.startCapture(width, height, framerate);
    }

    @Override
    public void stopCapture() throws InterruptedException {
        capturer.stopCapture();
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        capturer.changeCaptureFormat(width, height, framerate);
    }

    @Override
    public void dispose() {
        capturer.dispose();
    }

    @Override
    public boolean isScreencast() {
        return capturer.isScreencast();
    }

    @Override
    public void switchCamera(CameraSwitchHandler cameraSwitchHandler) {
        capturer.switchCamera(cameraSwitchHandler);
    }

    @Override
    public void switchCamera(CameraSwitchHandler cameraSwitchHandler, String s) {
        capturer.switchCamera(cameraSwitchHandler, s);
    }
}


