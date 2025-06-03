package com.satinavrobotics.satibot.livekit.stream;

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
    private static boolean isRegistered = false;

    public static ArCameraProvider getInstance() {
        if (instance == null) {
            instance = new ArCameraProvider();
            if (!isRegistered) {
                CameraCapturerUtils.INSTANCE.registerCameraProvider(instance);
                isRegistered = true;
            }
        }
        instance.arCameraSession = ArCameraSession.getInstance();
        instance.externalCameraSession = ExternalCameraSession.getInstance();
        return instance;
    }

    /**
     * Resets the singleton instance and unregisters from CameraCapturerUtils.
     * This should be called during cleanup to prevent multiple camera sources.
     */
    public static synchronized void reset() {
        if (instance != null) {
            try {
                CameraCapturerUtils.INSTANCE.unregisterCameraProvider(instance);
            } catch (Exception e) {
                android.util.Log.w("ArCameraProvider", "Error unregistering camera provider: " + e.getMessage());
            }
            instance = null;
        }
        isRegistered = false;
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
