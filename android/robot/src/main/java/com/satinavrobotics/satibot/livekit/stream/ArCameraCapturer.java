
package com.satinavrobotics.satibot.livekit.stream;


import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;

import androidx.annotation.Nullable;

import livekit.org.webrtc.SurfaceTextureHelper;

// implement CameraCapturer, CameraCapturerWithSIze
public class ArCameraCapturer extends CameraCapturer {

    private final Context context;
    @Nullable
    private final CameraManager cameraManager;

    private ArCameraSession arCameraSession;
    private ExternalCameraSession externalCameraSession;
    private final String AR_DEVICE_NAME;
    private final String EXTERNAL_DEVICE_NAME;

    public ArCameraCapturer(Context context, ArCameraSession arCameraSession, ExternalCameraSession externalCameraSession, String cameraName, @Nullable CameraEventsHandler eventsHandler, ArCameraEnumerator cameraEnumerator) {
        super(cameraName, eventsHandler, cameraEnumerator);
        this.context = context;
        this.cameraManager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        this.arCameraSession = arCameraSession;
        this.externalCameraSession = externalCameraSession;
        this.AR_DEVICE_NAME = cameraEnumerator.getArDeviceName();
        this.EXTERNAL_DEVICE_NAME = cameraEnumerator.getExternalDeviceName();
    }

    protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate, Handler handler) {
        if (cameraName.equals(AR_DEVICE_NAME)) {
            arCameraSession.setCameraSession(createSessionCallback, events);
            arCameraSession.setCameraThreadHandler(getCameraThreadHandler());
            // Start the ARCore session (this will resume ARCore if needed)
            arCameraSession.startArCoreSession();
        } else if (cameraName.equals(EXTERNAL_DEVICE_NAME)) {
            externalCameraSession.setCameraSession(createSessionCallback, events);
            externalCameraSession.setCameraThreadHandler(getCameraThreadHandler());
            externalCameraSession.onDone();
        } else {
            if (arCameraSession.isRunning()) {
                createSessionCallback.onFailure(CameraSession.FailureType.ERROR, "AR camera session is still running");
                return;
            }
            createCamera2Session(createSessionCallback, events, applicationContext, surfaceTextureHelper, cameraName, width, height, framerate);
        }

    }
    protected void createCamera2Session(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate) {
        Camera2Session.create(createSessionCallback, events, applicationContext, this.cameraManager, surfaceTextureHelper, cameraName, width, height, framerate);
    }
}
