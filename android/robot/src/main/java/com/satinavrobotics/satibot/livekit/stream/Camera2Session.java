package com.satinavrobotics.satibot.livekit.stream;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import livekit.org.webrtc.CameraEnumerationAndroid;
import livekit.org.webrtc.Logging;
import livekit.org.webrtc.Size;
import livekit.org.webrtc.SurfaceTextureHelper;
import livekit.org.webrtc.TextureBufferImpl;
import livekit.org.webrtc.VideoFrame;


class Camera2Session implements CameraSession {
    private static final String TAG = "Camera2Session";
    private final Handler cameraThreadHandler;
    private final CreateSessionCallback callback;
    private final Events events;
    private final Context applicationContext;
    private final CameraManager cameraManager;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final String cameraId;
    private final int width;
    private final int height;
    private final int framerate;
    private CameraCharacteristics cameraCharacteristics;
    private int cameraOrientation;
    private boolean isCameraFrontFacing;
    private int fpsUnitFactor;
    private CameraEnumerationAndroid.CaptureFormat captureFormat;
    @Nullable
    private CameraDevice cameraDevice;
    @Nullable
    private Surface surface;
    @Nullable
    private CameraCaptureSession captureSession;
    private SessionState state;
    private boolean firstFrameReported;
    private final long constructionTimeNs;
    private volatile boolean isIntentionallyStopping = false;

    public static void create(CreateSessionCallback callback, Events events, Context applicationContext, CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate) {
        new Camera2Session(callback, events, applicationContext, cameraManager, surfaceTextureHelper, cameraId, width, height, framerate);
    }

    private Camera2Session(CreateSessionCallback callback, Events events, Context applicationContext, CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate) {
        this.state = SessionState.RUNNING;
        Logging.d("Camera2Session", "Create new camera2 session on camera " + cameraId);
        this.constructionTimeNs = System.nanoTime();
        this.cameraThreadHandler = new Handler();
        this.callback = callback;
        this.events = events;
        this.applicationContext = applicationContext;
        this.cameraManager = cameraManager;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        this.framerate = framerate;
        this.start();
    }

    private void start() {
        this.checkIsOnCameraThread();
        Logging.d("Camera2Session", "start");

        try {
            this.cameraCharacteristics = this.cameraManager.getCameraCharacteristics(this.cameraId);
        } catch (IllegalArgumentException | CameraAccessException var2) {
            Exception e = var2;
            this.reportError("getCameraCharacteristics(): " + ((Exception)e).getMessage());
            return;
        }

        this.cameraOrientation = (Integer)this.cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        this.isCameraFrontFacing = (Integer)this.cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == 0;
        this.findCaptureFormat();
        if (this.captureFormat != null) {
            this.openCamera();
        }
    }

    private void findCaptureFormat() {
        this.checkIsOnCameraThread();
        Range<Integer>[] fpsRanges = (Range[])this.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        this.fpsUnitFactor = ArCameraEnumerator.getFpsUnitFactor(fpsRanges);
        List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> framerateRanges = ArCameraEnumerator.convertFramerates(fpsRanges, this.fpsUnitFactor);
        List<Size> sizes = ArCameraEnumerator.getSupportedSizes(this.cameraCharacteristics);
        Logging.d("Camera2Session", "Available preview sizes: " + sizes);
        Logging.d("Camera2Session", "Available fps ranges: " + framerateRanges);
        if (!framerateRanges.isEmpty() && !sizes.isEmpty()) {
            CameraEnumerationAndroid.CaptureFormat.FramerateRange bestFpsRange = CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, this.framerate);
            Size bestSize = CameraEnumerationAndroid.getClosestSupportedSize(sizes, this.width, this.height);
            this.captureFormat = new CameraEnumerationAndroid.CaptureFormat(bestSize.width, bestSize.height, bestFpsRange);
            Logging.d("Camera2Session", "Using capture format: " + this.captureFormat);
        } else {
            this.reportError("No supported capture formats.");
        }
    }

    private void openCamera() {
        this.checkIsOnCameraThread();
        Logging.d("Camera2Session", "Opening camera " + this.cameraId);
        this.events.onCameraOpening();

        try {
            // Check if camera is available before opening
            String[] cameraIds = this.cameraManager.getCameraIdList();
            boolean cameraExists = false;
            for (String id : cameraIds) {
                if (id.equals(this.cameraId)) {
                    cameraExists = true;
                    break;
                }
            }

            if (!cameraExists) {
                this.reportError("Camera " + this.cameraId + " is not available");
                return;
            }

            this.cameraManager.openCamera(this.cameraId, new CameraStateCallback(), this.cameraThreadHandler);
        } catch (IllegalArgumentException | SecurityException | CameraAccessException var2) {
            Exception e = var2;
            this.reportError("Failed to open camera: " + e);
        }
    }

    public void stop() {
        Logging.d("Camera2Session", "Stop camera2 session on camera " + this.cameraId);
        this.checkIsOnCameraThread();
        if (this.state != SessionState.STOPPED) {
            long stopStartTime = System.nanoTime();
            this.isIntentionallyStopping = true;
            this.state = SessionState.STOPPED;
            this.stopInternal();
        }

    }

    private void stopInternal() {
        this.checkIsOnCameraThread();

        // Stop surface texture helper first
        try {
            this.surfaceTextureHelper.stopListening();
        } catch (Exception e) {
            Logging.w("Camera2Session", "Error stopping surface texture helper: " + e.getMessage());
        }

        // Close capture session with enhanced error handling
        if (this.captureSession != null) {
            try {
                // Try to stop repeating requests first, but handle camera hardware errors gracefully
                try {
                    this.captureSession.stopRepeating();
                } catch (Exception e) {
                    // Handle specific camera hardware errors that may occur during stopRepeating
                    String errorMsg = e.getMessage();
                    if (errorMsg != null &&
                        (errorMsg.contains("Function not implemented") ||
                         errorMsg.contains("cancelRequest") ||
                         errorMsg.contains("CAMERA_ERROR"))) {
                        Logging.w("Camera2Session", "Camera hardware error during stopRepeating - continuing: " + errorMsg);
                    } else {
                        Logging.w("Camera2Session", "Error stopping repeating requests: " + errorMsg);
                    }
                }

                // Add a small delay to let the camera hardware settle
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}

                // Now close the session
                this.captureSession.close();
            } catch (Exception e) {
                Logging.w("Camera2Session", "Error closing capture session: " + e.getMessage());
            }
            this.captureSession = null;
        }

        // Release surface
        if (this.surface != null) {
            try {
                this.surface.release();
            } catch (Exception e) {
                Logging.w("Camera2Session", "Error releasing surface: " + e.getMessage());
            }
            this.surface = null;
        }

        // Close camera device
        if (this.cameraDevice != null) {
            try {
                this.cameraDevice.close();
            } catch (Exception e) {
                Logging.w("Camera2Session", "Error closing camera device: " + e.getMessage());
            }
            this.cameraDevice = null;
        }

        // Reset the stopping flag after cleanup is complete
        this.isIntentionallyStopping = false;
    }

    private void reportError(String error) {
        this.checkIsOnCameraThread();
        Logging.e("Camera2Session", "Error: " + error);
        boolean startFailure = this.captureSession == null && this.state != SessionState.STOPPED;
        this.state = SessionState.STOPPED;
        this.stopInternal();
        if (startFailure) {
            this.callback.onFailure(FailureType.ERROR, error);
        } else {
            this.events.onCameraError(this, error);
        }

    }

    private int getFrameOrientation() {
        int rotation = CameraSession.getDeviceOrientation(this.applicationContext);
        if (!this.isCameraFrontFacing) {
            rotation = 360 - rotation;
        }

        return (this.cameraOrientation + rotation) % 360;
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }


    private static enum SessionState {
        RUNNING,
        STOPPED;

        private SessionState() {
        }
    }

    private class CameraStateCallback extends CameraDevice.StateCallback {
        private CameraStateCallback() {
        }

        private String getErrorDescription(int errorCode) {
            switch (errorCode) {
                case 1 -> {
                    return "Camera device is in use already.";
                }
                case 2 -> {
                    return "Camera device could not be opened because there are too many other open camera devices.";
                }
                case 3 -> {
                    return "Camera device could not be opened due to a device policy.";
                }
                case 4 -> {
                    return "Camera device has encountered a fatal error.";
                }
                case 5 -> {
                    return "Camera service has encountered a fatal error.";
                }
                default -> {
                    return "Unknown camera error: " + errorCode;
                }
            }
        }

        public void onDisconnected(CameraDevice camera) {
            checkIsOnCameraThread();
            boolean startFailure = captureSession == null && state != SessionState.STOPPED;

            // If we're intentionally stopping, don't process this callback to avoid hardware errors
            if (isIntentionallyStopping || cameraDevice == null) {
                cameraDevice = null;
                return;
            }

            // Only handle disconnection if we're not already stopping
            if (state != SessionState.STOPPED) {
                state = SessionState.STOPPED;
                cameraDevice = null;

                // Clean up capture session without calling stopRepeating
                if (captureSession != null) {
                    try {
                        // Don't call stopRepeating here as it may cause the hardware error
                        // Just close the session directly
                        captureSession.close();
                    } catch (Exception e) {
                        Logging.w("Camera2Session", "Error closing capture session in onDisconnected: " + e.getMessage());
                    }
                    captureSession = null;
                }

                if (surface != null) {
                    try {
                        surface.release();
                    } catch (Exception e) {
                        Logging.w("Camera2Session", "Error releasing surface in onDisconnected: " + e.getMessage());
                    }
                    surface = null;
                }

                if (startFailure) {
                    callback.onFailure(FailureType.DISCONNECTED, "Camera disconnected / evicted.");
                } else {
                    events.onCameraDisconnected(Camera2Session.this);
                }
            } else {
                // Already stopped, just clean up references
                cameraDevice = null;
                if (captureSession != null) {
                    captureSession = null;
                }
                if (surface != null) {
                    try {
                        surface.release();
                    } catch (Exception ignored) {}
                    surface = null;
                }
            }
        }

        public void onError(CameraDevice camera, int errorCode) {
            checkIsOnCameraThread();
            String errorDescription = this.getErrorDescription(errorCode);
            Logging.e("Camera2Session", "Camera error " + errorCode + ": " + errorDescription);
            reportError(errorDescription);
        }

        public void onOpened(CameraDevice camera) {
            checkIsOnCameraThread();
            Logging.d("Camera2Session", "Camera opened.");
            cameraDevice = camera;
            surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);
            surface = new Surface(surfaceTextureHelper.getSurfaceTexture());

            try {
                camera.createCaptureSession(Arrays.asList(surface), new CaptureSessionCallback(), cameraThreadHandler);
            } catch (CameraAccessException var3) {
                CameraAccessException e = var3;
                reportError("Failed to create capture session. " + e);
            }
        }

        public void onClosed(CameraDevice camera) {
            checkIsOnCameraThread();
            Logging.d("Camera2Session", "Camera device closed.");
            events.onCameraClosed(Camera2Session.this);
        }
    }

    private static class CameraCaptureCallback extends CameraCaptureSession.CaptureCallback {
        private CameraCaptureCallback() {
        }

        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            Logging.d("Camera2Session", "Capture failed: " + failure);
        }
    }

    private class CaptureSessionCallback extends CameraCaptureSession.StateCallback {
        private CaptureSessionCallback() {
        }

        public void onConfigureFailed(CameraCaptureSession session) {
            checkIsOnCameraThread();
            session.close();
            reportError("Failed to configure capture session.");
        }

        public void onConfigured(CameraCaptureSession session) {
            checkIsOnCameraThread();
            Logging.d("Camera2Session", "Camera capture session configured.");
            captureSession = session;

            try {
                CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range(captureFormat.framerate.min / fpsUnitFactor, captureFormat.framerate.max / fpsUnitFactor));
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, 1);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                this.chooseStabilizationMode(captureRequestBuilder);
                this.chooseFocusMode(captureRequestBuilder);
                captureRequestBuilder.addTarget(surface);
                session.setRepeatingRequest(captureRequestBuilder.build(), new CameraCaptureCallback(), cameraThreadHandler);
            } catch (CameraAccessException var3) {
                CameraAccessException e = var3;
                reportError("Failed to start capture request. " + e);
                return;
            }

            surfaceTextureHelper.startListening((frame) -> {
                checkIsOnCameraThread();
                if (state != SessionState.RUNNING) {
                    Logging.d("Camera2Session", "Texture frame captured but camera is no longer running.");
                } else {
                    if (!firstFrameReported) {
                        firstFrameReported = true;
                    }

                    VideoFrame modifiedFrame = new VideoFrame(CameraSession.createTextureBufferWithModifiedTransformMatrix((TextureBufferImpl)frame.getBuffer(), isCameraFrontFacing, -cameraOrientation), getFrameOrientation(), frame.getTimestampNs());
                    events.onFrameCaptured(Camera2Session.this, modifiedFrame);
                    modifiedFrame.release();
                }
            });
            Logging.d("Camera2Session", "Camera device successfully started.");
            callback.onDone(Camera2Session.this);
        }

        private void chooseStabilizationMode(CaptureRequest.Builder captureRequestBuilder) {
            int[] availableOpticalStabilization = (int[]) cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            int[] availableVideoStabilization;
            int var5;
            int mode;
            if (availableOpticalStabilization != null) {
                availableVideoStabilization = availableOpticalStabilization;
                int var4 = availableOpticalStabilization.length;

                for(var5 = 0; var5 < var4; ++var5) {
                    mode = availableVideoStabilization[var5];
                    if (mode == 1) {
                        captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1);
                        captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 0);
                        Logging.d("Camera2Session", "Using optical stabilization.");
                        return;
                    }
                }
            }

            availableVideoStabilization = (int[]) cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (availableVideoStabilization != null) {
                int[] var8 = availableVideoStabilization;
                var5 = availableVideoStabilization.length;

                for(mode = 0; mode < var5; ++mode) {
                    int modex = var8[mode];
                    if (modex == 1) {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 1);
                        captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 0);
                        Logging.d("Camera2Session", "Using video stabilization.");
                        return;
                    }
                }
            }

            Logging.d("Camera2Session", "Stabilization not available.");
        }

        private void chooseFocusMode(CaptureRequest.Builder captureRequestBuilder) {
            int[] availableFocusModes = (int[]) cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int[] var3 = availableFocusModes;
            int var4 = availableFocusModes.length;

            for(int var5 = 0; var5 < var4; ++var5) {
                int mode = var3[var5];
                if (mode == 3) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, 3);
                    Logging.d("Camera2Session", "Using continuous video auto-focus.");
                    return;
                }
            }

            Logging.d("Camera2Session", "Auto-focus is not available.");
        }
    }
}
