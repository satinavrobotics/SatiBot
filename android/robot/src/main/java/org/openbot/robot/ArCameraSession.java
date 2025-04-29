package org.openbot.robot;

import android.os.Handler;

import livekit.org.webrtc.VideoFrame;

public class ArCameraSession implements CameraSession, CameraSession.CreateSessionCallback, CameraSession.Events {

    private static ArCameraSession instance;

    public static ArCameraSession getInstance() {
        if (instance == null) {
            instance = new ArCameraSession();
        }
        return instance;
    }

    private Handler cameraThreadHandler;
    private CameraSession.CreateSessionCallback createSessionCallback;
    private CameraSession.Events eventsCallback;

    private ArCoreHandler arCoreHandler;

    private boolean isRunning = false;

    public boolean isReady() {
        return createSessionCallback != null && eventsCallback != null;
    }

    public void setCameraThreadHandler(Handler cameraThreadHandler) {
        this.cameraThreadHandler = cameraThreadHandler;
    }

    public Handler getCameraThreadHandler() {
        return cameraThreadHandler;
    }

    public void setCameraSession(CameraSession.CreateSessionCallback create,
                                 CameraSession.Events events) {
        this.createSessionCallback = create;
        this.eventsCallback = events;
    }

    public CameraSession.CreateSessionCallback getCreateSessionCallback() {
        return createSessionCallback;
    }

    public CameraSession.Events getEventsCallback() {
        return eventsCallback;
    }

    @Override
    public void onDone(CameraSession var1) {
        if (createSessionCallback != null) {
            createSessionCallback.onDone(this);
            isRunning = true;
        }
    }

    public void onDone() {
        onDone(this);
    }

    @Override
    public void onFailure(CameraSession.FailureType var1, String var2) {
        if (createSessionCallback != null) {
            createSessionCallback.onFailure(var1, var2);
        }
    }

    @Override
    public void onCameraOpening() {
        if (eventsCallback != null) {
            eventsCallback.onCameraOpening();
        }
    }

    @Override
    public void onCameraError(CameraSession var1, String var2) {
        if (eventsCallback != null) {
            eventsCallback.onCameraError(var1, var2);
        }
    }

    @Override
    public void onCameraDisconnected(CameraSession var1) {
        if (eventsCallback != null) {
            eventsCallback.onCameraDisconnected(var1);
        }
    }

    @Override
    public void onCameraClosed(CameraSession var1) {
        if (eventsCallback != null) {
            eventsCallback.onCameraClosed(var1);
        }
    }

    @Override
    public void onFrameCaptured(CameraSession var1, VideoFrame var2) {
        if (eventsCallback != null) {
            eventsCallback.onFrameCaptured(var1, var2);
        }
    }

    public void onFrameCapturedInCurrentSession(VideoFrame videoFrame) {
        if (cameraThreadHandler == null) {
            onFrameCaptured(this, videoFrame);
            videoFrame.release();
        } else {
            cameraThreadHandler.post(
                    () -> {
                        onFrameCaptured(this, videoFrame);
                        videoFrame.release();
                    });
        }

    }

    public void setArCoreHandler(ArCoreHandler arCoreHandler) {
        this.arCoreHandler = arCoreHandler;

    }

    @Override
    public void stop() {
        if (arCoreHandler != null) {
            arCoreHandler.closeSession();
        }
        eventsCallback = null;
        createSessionCallback = null;
        cameraThreadHandler = null;
        isRunning = false;
    }

    public boolean isRunning() {
        return isRunning;
    }
}