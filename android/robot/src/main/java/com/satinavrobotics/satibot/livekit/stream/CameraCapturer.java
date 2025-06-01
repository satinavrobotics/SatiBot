package com.satinavrobotics.satibot.livekit.stream;

//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import livekit.org.webrtc.CameraEnumerator;
import livekit.org.webrtc.CameraVideoCapturer;
import livekit.org.webrtc.CapturerObserver;
import livekit.org.webrtc.Logging;
import livekit.org.webrtc.SurfaceTextureHelper;
import livekit.org.webrtc.VideoFrame;

abstract class CameraCapturer implements CameraVideoCapturer {
    private static final String TAG = "CameraCapturer";
    private static final int MAX_OPEN_CAMERA_ATTEMPTS = 3;
    private static final int OPEN_CAMERA_DELAY_MS = 500;
    private static final int OPEN_CAMERA_TIMEOUT = 10000;
    private final CameraEnumerator cameraEnumerator;
    private final CameraEventsHandler eventsHandler;
    private final Handler uiThreadHandler;
    @Nullable
    private final CameraSession.CreateSessionCallback createSessionCallback = new CameraSession.CreateSessionCallback() {
        public void onDone(CameraSession session) {
            checkIsOnCameraThread();
            Logging.d(TAG, "Create session done. Switch state: " + switchState);
            uiThreadHandler.removeCallbacks(openCameraTimeoutRunnable);
            synchronized(stateLock) {
                capturerObserver.onCapturerStarted(true);
                sessionOpening = false;
                currentSession = session;
                cameraStatistics = new CameraStatistics(surfaceHelper, eventsHandler);
                firstFrameObserved = false;
                stateLock.notifyAll();
                if (switchState == SwitchState.IN_PROGRESS) {
                    switchState = SwitchState.IDLE;
                    if (switchEventsHandler != null) {
                        switchEventsHandler.onCameraSwitchDone(cameraEnumerator.isFrontFacing(cameraName));
                        switchEventsHandler = null;
                    }
                } else if (CameraCapturer.this.switchState == SwitchState.PENDING) {
                    String selectedCameraName = pendingCameraName;
                    pendingCameraName = null;
                    switchState = SwitchState.IDLE;
                    switchCameraInternal(switchEventsHandler, selectedCameraName);
                }

            }
        }

        public void onFailure(CameraSession.FailureType failureType, String error) {
            checkIsOnCameraThread();
            uiThreadHandler.removeCallbacks(CameraCapturer.this.openCameraTimeoutRunnable);
            synchronized(stateLock) {
                capturerObserver.onCapturerStarted(false);
                --openAttemptsRemaining;
                if (openAttemptsRemaining <= 0) {
                    Logging.w(TAG, "Opening camera failed, passing: " + error);
                    sessionOpening = false;
                    stateLock.notifyAll();
                    if (switchState != SwitchState.IDLE) {
                        if (switchEventsHandler != null) {
                            switchEventsHandler.onCameraSwitchError(error);
                            switchEventsHandler = null;
                        }

                        switchState = SwitchState.IDLE;
                    }

                    if (failureType == CameraSession.FailureType.DISCONNECTED) {
                        eventsHandler.onCameraDisconnected();
                    } else {
                        eventsHandler.onCameraError(error);
                    }
                } else {
                    Logging.w(TAG, "Opening camera failed, retry: " + error);
                    createSessionInternal(OPEN_CAMERA_DELAY_MS);
                }
            }
        }
    };
    @Nullable
    private final CameraSession.Events cameraSessionEventsHandler = new CameraSession.Events() {
        public void onCameraOpening() {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (currentSession != null) {
                    Logging.w(TAG, "onCameraOpening while session was open.");
                } else {
                    eventsHandler.onCameraOpening(cameraName);
                }
            }
        }

        public void onCameraError(CameraSession session, String error) {
            checkIsOnCameraThread();
            synchronized(CameraCapturer.this.stateLock) {
                if (session != currentSession) {
                    Logging.w(TAG, "onCameraError from another session: " + error);
                } else {
                    eventsHandler.onCameraError(error);
                    stopCapture();
                }
            }
        }

        public void onCameraDisconnected(CameraSession session) {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (session != currentSession) {
                    Logging.w(TAG, "onCameraDisconnected from another session.");
                } else {
                    eventsHandler.onCameraDisconnected();
                    stopCapture();
                }
            }
        }

        public void onCameraClosed(CameraSession session) {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (session != currentSession && currentSession != null) {
                    Logging.d("CameraCapturer", "onCameraClosed from another session.");
                } else {
                    eventsHandler.onCameraClosed();
                }
            }
        }

        public void onFrameCaptured(CameraSession session, VideoFrame frame) {
            checkIsOnCameraThread();
            synchronized(stateLock) {
                if (session != currentSession) {
                    Logging.w(TAG, "onFrameCaptured from another session.");
                } else {
                    if (!firstFrameObserved) {
                        eventsHandler.onFirstFrameAvailable();
                        firstFrameObserved = true;
                    }

                    cameraStatistics.addFrame();
                    capturerObserver.onFrameCaptured(frame);
                }
            }
        }
    };
    private final Runnable openCameraTimeoutRunnable = new Runnable() {
        public void run() {
            eventsHandler.onCameraError("Camera failed to start within timeout.");
        }
    };
    private Handler cameraThreadHandler;

    protected Handler getCameraThreadHandler() {
        return cameraThreadHandler;
    }
    private Context applicationContext;
    private CapturerObserver capturerObserver;
    private SurfaceTextureHelper surfaceHelper;
    private final Object stateLock = new Object();
    private boolean sessionOpening;
    @Nullable
    private CameraSession currentSession;
    private String cameraName;
    private String pendingCameraName;
    private int width;
    private int height;
    private int framerate;
    private int openAttemptsRemaining;
    private SwitchState switchState;
    @Nullable
    private CameraSwitchHandler switchEventsHandler;
    @Nullable
    private CameraStatistics cameraStatistics;
    private boolean firstFrameObserved;

    public CameraCapturer(String cameraName, @Nullable CameraEventsHandler eventsHandler, CameraEnumerator cameraEnumerator) {
        this.switchState = SwitchState.IDLE;
        if (eventsHandler == null) {
            eventsHandler = new CameraEventsHandler() {
                public void onCameraError(String errorDescription) {
                }

                public void onCameraDisconnected() {
                }

                public void onCameraFreezed(String errorDescription) {
                }

                public void onCameraOpening(String cameraName) {
                }

                public void onFirstFrameAvailable() {
                }

                public void onCameraClosed() {
                }
            };
        }

        this.eventsHandler = eventsHandler;
        this.cameraEnumerator = cameraEnumerator;
        this.cameraName = cameraName;
        List<String> deviceNames = Arrays.asList(cameraEnumerator.getDeviceNames());
        this.uiThreadHandler = new Handler(Looper.getMainLooper());
        if (deviceNames.isEmpty()) {
            throw new RuntimeException("No cameras attached.");
        } else if (!deviceNames.contains(this.cameraName)) {
            throw new IllegalArgumentException("Camera name " + this.cameraName + " does not match any known camera device.");
        }
    }

    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver capturerObserver) {
        this.applicationContext = applicationContext;
        this.capturerObserver = capturerObserver;
        this.surfaceHelper = surfaceTextureHelper;
        this.cameraThreadHandler = surfaceTextureHelper.getHandler();
    }

    public void startCapture(int width, int height, int framerate) {
        Logging.d(TAG, "startCapture: " + width + "x" + height + "@" + framerate);
        if (this.applicationContext == null) {
            throw new RuntimeException("CameraCapturer must be initialized before calling startCapture.");
        } else {
            synchronized(this.stateLock) {
                if (!this.sessionOpening && this.currentSession == null) {
                    this.width = width;
                    this.height = height;
                    this.framerate = framerate;
                    this.sessionOpening = true;
                    this.openAttemptsRemaining = MAX_OPEN_CAMERA_ATTEMPTS;
                    this.createSessionInternal(0);
                } else {
                    Logging.w(TAG, "Session already open");
                }
            }
        }
    }

    private void createSessionInternal(int delayMs) {
        this.uiThreadHandler.postDelayed(this.openCameraTimeoutRunnable, (long)(delayMs + OPEN_CAMERA_TIMEOUT));
        this.cameraThreadHandler.postDelayed(new Runnable() {
            public void run() {
                createCameraSession(createSessionCallback, cameraSessionEventsHandler, applicationContext, surfaceHelper, cameraName, width, height, framerate, cameraThreadHandler);
            }
        }, (long)delayMs);
    }

    public void stopCapture() {
        Logging.d(TAG, "Stop capture");
        synchronized(this.stateLock) {
            while(this.sessionOpening) {
                Logging.d(TAG, "Stop capture: Waiting for session to open");

                try {
                    this.stateLock.wait();
                } catch (InterruptedException var4) {
                    Logging.w(TAG, "Stop capture interrupted while waiting for the session to open.");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (this.currentSession != null) {
                Logging.d(TAG, "Stop capture: Nulling session");
                this.cameraStatistics.release();
                this.cameraStatistics = null;
                final CameraSession oldSession = this.currentSession;
                this.cameraThreadHandler.post(new Runnable() {
                    public void run() {
                        oldSession.stop();
                    }
                });
                this.currentSession = null;
                this.capturerObserver.onCapturerStopped();
            } else {
                Logging.d(TAG, "Stop capture: No session open");
            }
        }

        Logging.d(TAG, "Stop capture done");
    }

    public void changeCaptureFormat(int width, int height, int framerate) {
        Logging.d(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        synchronized(this.stateLock) {
            this.stopCapture();
            this.startCapture(width, height, framerate);
        }
    }

    public void dispose() {
        Logging.d(TAG, "dispose");
        this.stopCapture();
    }

    public void switchCamera(final CameraSwitchHandler switchEventsHandler) {
        Logging.d(TAG, "switchCamera");
        this.cameraThreadHandler.post(new Runnable() {
            public void run() {
                List<String> deviceNames = Arrays.asList(cameraEnumerator.getDeviceNames());
                if (deviceNames.size() < 2) {
                    reportCameraSwitchError("No camera to switch to.", switchEventsHandler);
                } else {
                    int cameraNameIndex = deviceNames.indexOf(cameraName);
                    String cameraName = (String)deviceNames.get((cameraNameIndex + 1) % deviceNames.size());
                    switchCameraInternal(switchEventsHandler, cameraName);
                }
            }
        });
    }

    public void switchCamera(final CameraSwitchHandler switchEventsHandler, final String cameraName) {
        Logging.d(TAG, "switchCamera");
        this.cameraThreadHandler.post(new Runnable() {
            public void run() {
                switchCameraInternal(switchEventsHandler, cameraName);
            }
        });
    }

    public boolean isScreencast() {
        return false;
    }

    public void printStackTrace() {
        Thread cameraThread = null;
        if (this.cameraThreadHandler != null) {
            cameraThread = this.cameraThreadHandler.getLooper().getThread();
        }

        if (cameraThread != null) {
            StackTraceElement[] cameraStackTrace = cameraThread.getStackTrace();
            if (cameraStackTrace.length > 0) {
                Logging.d(TAG, "CameraCapturer stack trace:");
                StackTraceElement[] var3 = cameraStackTrace;
                int var4 = cameraStackTrace.length;

                for(int var5 = 0; var5 < var4; ++var5) {
                    StackTraceElement traceElem = var3[var5];
                    Logging.d(TAG, traceElem.toString());
                }
            }
        }

    }

    private void reportCameraSwitchError(String error, @Nullable CameraSwitchHandler switchEventsHandler) {
        Logging.e(TAG, error);
        if (switchEventsHandler != null) {
            switchEventsHandler.onCameraSwitchError(error);
        }

    }

    private void switchCameraInternal(@Nullable CameraSwitchHandler switchEventsHandler, String selectedCameraName) {
        Logging.d(TAG, "switchCamera internal");
        List<String> deviceNames = Arrays.asList(this.cameraEnumerator.getDeviceNames());
        if (!deviceNames.contains(selectedCameraName)) {
            this.reportCameraSwitchError("Attempted to switch to unknown camera device " + selectedCameraName, switchEventsHandler);
        } else {
            synchronized(this.stateLock) {
                if (this.switchState != SwitchState.IDLE) {
                    this.reportCameraSwitchError("Camera switch already in progress.", switchEventsHandler);
                    return;
                }

                if (!this.sessionOpening && this.currentSession == null) {
                    this.reportCameraSwitchError("switchCamera: camera is not running.", switchEventsHandler);
                    return;
                }

                this.switchEventsHandler = switchEventsHandler;
                if (this.sessionOpening) {
                    this.switchState = SwitchState.PENDING;
                    this.pendingCameraName = selectedCameraName;
                    return;
                }

                this.switchState = SwitchState.IN_PROGRESS;
                Logging.d(TAG, "switchCamera: Stopping session");
                this.cameraStatistics.release();
                this.cameraStatistics = null;
                final CameraSession oldSession = this.currentSession;
                this.cameraThreadHandler.post(new Runnable() {
                    public void run() {
                        oldSession.stop();
                    }
                });
                this.currentSession = null;
                this.cameraName = selectedCameraName;
                this.sessionOpening = true;
                this.openAttemptsRemaining = MAX_OPEN_CAMERA_ATTEMPTS;
                this.createSessionInternal(0);
            }

            Logging.d(TAG, "switchCamera done");
        }
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
            Logging.e(TAG, "Check is on camera thread failed.");
            throw new RuntimeException("Not on camera thread.");
        }
    }

    protected String getCameraName() {
        synchronized(this.stateLock) {
            return this.cameraName;
        }
    }

    protected abstract void createCameraSession(CameraSession.CreateSessionCallback var1, CameraSession.Events var2, Context var3, SurfaceTextureHelper var4, String var5, int var6, int var7, int var8, Handler handler);

    static enum SwitchState {
        IDLE,
        PENDING,
        IN_PROGRESS;

        private SwitchState() {
        }
    }
}

