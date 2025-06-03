package com.satinavrobotics.satibot.livekit.stream;

import android.os.Handler;

import livekit.org.webrtc.VideoFrame;

import com.satinavrobotics.satibot.env.ExternalCameraConnector;
import com.satinavrobotics.satibot.arcore.ArCoreHandler;

import timber.log.Timber;

public class ExternalCameraSession implements CameraSession, CameraSession.CreateSessionCallback, CameraSession.Events, ExternalCameraConnector.StreamListener {

    private String STREAM_URL = "http://192.168.0.10:81/stream";
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
    private boolean shouldReconnect = true;
    private boolean isStarting = false; // Flag to prevent concurrent startSession calls
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY_MS = 2000; // Reduced from 3s to 2s

    public ExternalCameraSession() {
        // Initialize the external camera connector
        this.externalCameraConnector = new ExternalCameraConnector();
    }

    public synchronized void startSession() {
        if (isStarting || externalCameraConnector == null) {
            return;
        }

        // Don't start if not properly configured with callbacks
        if (createSessionCallback == null || eventsCallback == null) {
            return;
        }

        isStarting = true;
        try {
            shouldReconnect = true;
            reconnectAttempts = 0;
            externalCameraConnector.connectStream(STREAM_URL, this);
        } finally {
            isStarting = false;
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

    public void setExternalCameraConnector(ExternalCameraConnector externalCameraConnector) {
        this.externalCameraConnector = externalCameraConnector;
    }

    public void setStreamUrl(String streamUrl) {
        this.STREAM_URL = streamUrl;
    }

    @Override
    public synchronized void stop() {
        shouldReconnect = false;
        reconnectAttempts = 0;
        isStarting = false;
        if (externalCameraConnector != null) {
            externalCameraConnector.disconnect();
        }
        // Clear callbacks to prevent stale references
        eventsCallback = null;
        createSessionCallback = null;
        cameraThreadHandler = null;
        isRunning = false;
    }

    /**
     * Reset the session to a clean state for reuse
     */
    public synchronized void reset() {
        stop();
        // Recreate the connector to ensure clean state
        externalCameraConnector = new ExternalCameraConnector();
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Resets the singleton instance. This should be called during cleanup
     * to prevent stale references and multiple camera sources.
     */
    public static synchronized void resetSingleton() {
        if (instance != null) {
            try {
                instance.reset();
            } catch (Exception e) {
                Timber.w(e, "Error resetting external camera session during singleton reset");
            }
            instance = null;
        }
    }



    // data coming from external camera

    @Override
    public void onFrame(VideoFrame frame) {
        if (reconnectAttempts > 0) {
            reconnectAttempts = 0;
        }
        onFrameCapturedInCurrentSession(frame);
    }

    @Override
    public void onError(Exception e) {
        boolean isNetworkError = e instanceof java.net.SocketTimeoutException ||
                                e instanceof java.net.SocketException ||
                                e.getMessage().contains("timeout") ||
                                e.getMessage().contains("Socket closed");

        if (isNetworkError && shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (shouldReconnect && isRunning) {
                    startSession();
                }
            }, RECONNECT_DELAY_MS);
            return;
        }

        if (eventsCallback != null) {
            if (cameraThreadHandler != null) {
                cameraThreadHandler.post(() -> {
                    if (eventsCallback != null) {
                        eventsCallback.onCameraError(this, "External camera stream error: " + e.getMessage());
                    }
                });
            } else {
                eventsCallback.onCameraError(this, "External camera stream error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisconnected() {
        if (eventsCallback != null) {
            if (cameraThreadHandler != null) {
                cameraThreadHandler.post(() -> eventsCallback.onCameraDisconnected(this));
            } else {
                eventsCallback.onCameraDisconnected(this);
            }
        }
    }


}