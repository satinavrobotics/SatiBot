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
                createSessionCallback.onFailure(FailureType.ERROR, "Failed to start ARCore session: " + e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        try {
            if (arCoreHandler != null) {
                arCoreHandler.closeSession(() -> {
                    // Just mark as not running, keep callbacks for reuse
                    isRunning = false;
                });
            } else {
                // No ARCore handler, just mark as not running
                isRunning = false;
            }
        } catch (Exception e) {
            // Log error but ensure cleanup happens
            if (arCoreHandler != null) {
                try {
                    // Try to get session info for logging
                    String sessionInfo = arCoreHandler.getSession() != null ? "active" : "null";
                    android.util.Log.w("ArCameraSession", "Error stopping ARCore session (session: " + sessionInfo + "): " + e.getMessage());
                } catch (Exception logError) {
                    android.util.Log.w("ArCameraSession", "Error stopping ARCore session: " + e.getMessage());
                }
            }
            // Force cleanup even if closeSession fails
            isRunning = false;
        }
    }

    /**
     * Completely stops and clears all callbacks. Used during reset.
     */
    private void stopAndClearCallbacks() {
        try {
            if (arCoreHandler != null) {
                arCoreHandler.closeSession(() -> {
                    // Cleanup completed successfully
                    eventsCallback = null;
                    createSessionCallback = null;
                    cameraThreadHandler = null;
                    isRunning = false;
                });
            } else {
                // No ARCore handler, just clean up state
                eventsCallback = null;
                createSessionCallback = null;
                cameraThreadHandler = null;
                isRunning = false;
            }
        } catch (Exception e) {
            // Log error but ensure cleanup happens
            android.util.Log.w("ArCameraSession", "Error in stopAndClearCallbacks: " + e.getMessage());
            // Force cleanup even if closeSession fails
            eventsCallback = null;
            createSessionCallback = null;
            cameraThreadHandler = null;
            isRunning = false;
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Resets the singleton instance. This should be called during cleanup
     * to prevent stale references and multiple camera sources.
     */
    public static synchronized void reset() {
        if (instance != null) {
            try {
                instance.stopAndClearCallbacks();
            } catch (Exception e) {
                android.util.Log.w("ArCameraSession", "Error stopping session during reset: " + e.getMessage());
            }
            instance = null;
        }
    }
}