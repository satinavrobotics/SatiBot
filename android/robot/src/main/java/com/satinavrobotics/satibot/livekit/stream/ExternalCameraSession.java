package com.satinavrobotics.satibot.livekit.stream;

import android.os.Handler;

import livekit.org.webrtc.VideoFrame;

import com.satinavrobotics.satibot.env.ExternalCameraConnector;
import com.satinavrobotics.satibot.arcore.ArCoreHandler;

import timber.log.Timber;

public class ExternalCameraSession implements CameraSession, CameraSession.CreateSessionCallback, CameraSession.Events, ExternalCameraConnector.StreamListener {

    private String STREAM_URL = "";
    private static ExternalCameraSession instance;

    public static ExternalCameraSession getInstance() {
        if (instance == null) {
            instance = new ExternalCameraSession();
        }
        return instance;
    }

    private Handler cameraThreadHandler;
    private CreateSessionCallback createSessionCallback;
    private Events eventsCallback;

    private ExternalCameraConnector externalCameraConnector;

    private boolean isRunning = false;

    public void startSession() {
        if (externalCameraConnector != null) {
            externalCameraConnector.connectStream(STREAM_URL, this);
        }
    }

    public boolean isReady() {
        return createSessionCallback != null && eventsCallback != null;
    }

    public void setCameraThreadHandler(Handler cameraThreadHandler) {
        this.cameraThreadHandler = cameraThreadHandler;
    }

    public Handler getCameraThreadHandler() {
        return cameraThreadHandler;
    }

    public void setCameraSession(CreateSessionCallback create,
                                 Events events) {
        this.createSessionCallback = create;
        this.eventsCallback = events;
    }

    public CreateSessionCallback getCreateSessionCallback() {
        return createSessionCallback;
    }

    public Events getEventsCallback() {
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
    public void onFailure(FailureType var1, String var2) {
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

    public void setExternalCameraConnector(ArCoreHandler arCoreHandler) {
        this.externalCameraConnector = externalCameraConnector;
    }

    @Override
    public void stop() {
        if (externalCameraConnector != null) {
            externalCameraConnector.disconnect();
        }
        eventsCallback = null;
        createSessionCallback = null;
        cameraThreadHandler = null;
        isRunning = false;
    }

    public boolean isRunning() {
        return isRunning;
    }



    // data coming from external camera

    @Override
    public void onFrame(VideoFrame frame) {
        onFrameCapturedInCurrentSession(frame);
    }

    @Override
    public void onError(Exception e) {
        e.printStackTrace();
    }

    @Override
    public void onDisconnected() {
        Timber.d("EXTERNAL CAMERA: onDisconnected");
    }


}