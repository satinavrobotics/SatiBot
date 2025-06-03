
package com.satinavrobotics.satibot.livekit.stream;


import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;

import androidx.annotation.Nullable;

import livekit.org.webrtc.SurfaceTextureHelper;

/**
 * Camera capturer that supports ARCore, external camera streams, and regular Camera2 devices.
 * Handles proper session cleanup and switching between different camera sources.
 */
public class ArCameraCapturer extends CameraCapturer {

    // Camera switching delays
    private static final int ARCORE_CLEANUP_DELAY_MS = 500;
    private static final int CAMERA_RETRY_DELAY_MS = 1000;
    private static final int MAX_CAMERA_RETRIES = 3;

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
            createArCameraSession(createSessionCallback, events);
        } else if (cameraName.equals(EXTERNAL_DEVICE_NAME)) {
            createExternalCameraSession(createSessionCallback, events);
        } else {
            createCamera2SessionWithCleanup(createSessionCallback, events, applicationContext, surfaceTextureHelper, cameraName, width, height, framerate, handler);
        }
    }

    private void createArCameraSession(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events) {
        if (externalCameraSession.isRunning()) {
            externalCameraSession.stop();
        }
        arCameraSession.setCameraSession(createSessionCallback, events);
        arCameraSession.setCameraThreadHandler(getCameraThreadHandler());
        arCameraSession.startArCoreSession();
    }

    private void createExternalCameraSession(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events) {
        if (arCameraSession.isRunning()) {
            arCameraSession.stop();
        }
        externalCameraSession.setCameraSession(createSessionCallback, events);
        externalCameraSession.setCameraThreadHandler(getCameraThreadHandler());
        externalCameraSession.startSession();
        externalCameraSession.onDone();
    }

    private void createCamera2SessionWithCleanup(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate, Handler handler) {
        if (arCameraSession.isRunning()) {
            // Stop ARCore session and wait for cleanup before starting Camera2
            arCameraSession.stop();
            handler.postDelayed(() -> {
                stopExternalSessionAndCreateCamera2(createSessionCallback, events, applicationContext, surfaceTextureHelper, cameraName, width, height, framerate);
            }, ARCORE_CLEANUP_DELAY_MS * 2); // Double the delay for better cleanup
            return;
        }

        stopExternalSessionAndCreateCamera2(createSessionCallback, events, applicationContext, surfaceTextureHelper, cameraName, width, height, framerate);
    }

    private void stopExternalSessionAndCreateCamera2(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate) {
        if (externalCameraSession.isRunning()) {
            externalCameraSession.stop();
        }
        createCamera2Session(createSessionCallback, events, applicationContext, surfaceTextureHelper, cameraName, width, height, framerate);
    }
    protected void createCamera2Session(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate) {
        createCamera2SessionWithRetry(createSessionCallback, events, applicationContext, surfaceTextureHelper, cameraName, width, height, framerate, 0);
    }

    private void createCamera2SessionWithRetry(CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate, int retryCount) {
        CameraSession.CreateSessionCallback retryCallback = new CameraSession.CreateSessionCallback() {
            @Override
            public void onDone(CameraSession session) {
                createSessionCallback.onDone(session);
            }

            @Override
            public void onFailure(CameraSession.FailureType failureType, String error) {
                boolean isCameraConflict = error.contains("in use") || error.contains("CAMERA_IN_USE") || error.contains("disconnected");

                if (isCameraConflict && retryCount < MAX_CAMERA_RETRIES) {
                    getCameraThreadHandler().postDelayed(() -> {
                        createCamera2SessionWithRetry(createSessionCallback, events, applicationContext, surfaceTextureHelper, cameraName, width, height, framerate, retryCount + 1);
                    }, CAMERA_RETRY_DELAY_MS);
                } else {
                    createSessionCallback.onFailure(failureType, error);
                }
            }
        };

        Camera2Session.create(retryCallback, events, applicationContext, this.cameraManager, surfaceTextureHelper, cameraName, width, height, framerate);
    }
}
