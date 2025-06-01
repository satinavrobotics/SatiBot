package com.satinavrobotics.satibot.livekit.stream;

import android.os.Handler;

import com.satinavrobotics.satibot.arcore.ArCoreHandler;

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
    private CreateSessionCallback createSessionCallback;
    private Events eventsCallback;

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

    public void setArCoreHandler(ArCoreHandler arCoreHandler) {
        this.arCoreHandler = arCoreHandler;
    }

    /**
     * Starts the ARCore session if it's not already running.
     * This is called when switching back to ARCore from another camera source.
     */
    public void startArCoreSession() {
        if (arCoreHandler != null && createSessionCallback != null && eventsCallback != null) {
            try {
                // Check if ARCore session is already active
                if (arCoreHandler.getSession() == null) {
                    // Resume the ARCore session only if it's not already running
                    arCoreHandler.resume();
                }
                // Mark as running and notify success
                isRunning = true;
                createSessionCallback.onDone(this);
            } catch (Exception e) {
                // If ARCore fails to start, notify failure
                android.util.Log.e("ArCameraSession", "Failed to start ARCore session: " + e.getMessage(), e);
                createSessionCallback.onFailure(FailureType.ERROR, "Failed to start ARCore session: " + e.getMessage());
            }
        }
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