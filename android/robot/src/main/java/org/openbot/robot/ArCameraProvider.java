package org.openbot.robot;

import android.content.Context;

import androidx.annotation.NonNull;

import io.livekit.android.room.track.LocalVideoTrackOptions;
import io.livekit.android.room.track.video.CameraCapturerUtils;
import io.livekit.android.room.track.video.CameraEventsDispatchHandler;
import livekit.org.webrtc.CameraEnumerator;
import livekit.org.webrtc.CameraVideoCapturer;

public class ArCameraProvider implements CameraCapturerUtils.CameraProvider {

    private ArCameraSession arCameraSession;
    private ExternalCameraSession externalCameraSession;

    private static ArCameraProvider instance;

    public static ArCameraProvider getInstance() {
        if (instance == null) {
            instance = new ArCameraProvider();
            CameraCapturerUtils.INSTANCE.registerCameraProvider(instance);
        }
        instance.arCameraSession = ArCameraSession.getInstance();
        instance.externalCameraSession = ExternalCameraSession.getInstance();
        return instance;
    }

    @Override
    public int getCameraVersion() {
        return 3;
    }
    @NonNull
    @Override
    public CameraEnumerator provideEnumerator(@NonNull Context context) {
        return new ArCameraEnumerator(context);
    }

    @NonNull
    @Override
    public livekit.org.webrtc.VideoCapturer provideCapturer(@NonNull Context context, @NonNull LocalVideoTrackOptions localVideoTrackOptions, @NonNull CameraEventsDispatchHandler cameraEventsDispatchHandler) {
        ArCameraEnumerator enumerator = (ArCameraEnumerator) provideEnumerator(context);
        String targetDeviceName = enumerator.findCamera(localVideoTrackOptions.getDeviceId(), localVideoTrackOptions.getPosition(), false);
        CameraVideoCapturer targetVideoCapturer = enumerator.createCapturer(arCameraSession, externalCameraSession, targetDeviceName, cameraEventsDispatchHandler);
        return new ArCameraCapturerWithSize((ArCameraCapturer) targetVideoCapturer, targetDeviceName, cameraEventsDispatchHandler);
    }

    @Override
    public boolean isSupported(@NonNull Context context) {
        return true;
    }
}
