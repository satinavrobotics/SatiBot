package com.satinavrobotics.satibot.logging.sources;

import android.graphics.Bitmap;

import com.google.ar.core.Pose;
import com.google.ar.core.TrackingFailureReason;
import com.satinavrobotics.satibot.env.ExternalCameraConnector;
import com.satinavrobotics.satibot.logging.render.BitmapRenderer;
import com.satinavrobotics.satibot.arcore.ArCoreListener;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;
import com.satinavrobotics.satibot.arcore.ImageFrame;
import com.satinavrobotics.satibot.arcore.ArCoreHandler;
import com.satinavrobotics.satibot.utils.ImageSource;

import livekit.org.webrtc.VideoFrame;

/**
 * Image source handler for External Camera
 * Provides: image, arcore pose, sync
 */
public class ExternalCameraImageSourceHandler implements ImageSourceHandler,
    ExternalCameraConnector.BitmapCaptureListener, ArCoreListener {

    private ExternalCameraConnector externalCameraConnector;
    private ArCoreHandler arCoreHandler;
    private ImageSourceListener listener;
    private boolean isCapturing = false;
    private String captureUrl = ""; // URL for capturing still images
    private long captureIntervalMs = 100; // Capture interval in milliseconds
    private Thread captureThread;
    private BitmapRenderer bitmapRenderer;

    // For synchronization
    private Pose latestPose;
    private CameraIntrinsics latestCameraIntrinsics;

    public ExternalCameraImageSourceHandler(ArCoreHandler arCoreHandler) {
        this.arCoreHandler = arCoreHandler;
        this.externalCameraConnector = new ExternalCameraConnector();
    }

    public void setCaptureUrl(String captureUrl) {
        this.captureUrl = captureUrl;
    }

    public void setCaptureInterval(long intervalMs) {
        this.captureIntervalMs = intervalMs;
    }

    @Override
    public void initialize() {
        if (arCoreHandler != null) {
            arCoreHandler.setArCoreListener(this);
        }
    }

    @Override
    public void startCapture() {
        isCapturing = true;

        // Start bitmap renderer
        if (bitmapRenderer != null) {
            bitmapRenderer.startRendering();
        }

        // Start ARCore for pose data
        if (arCoreHandler != null) {
            arCoreHandler.setArCoreListener(this);
        }

        // Start external camera capture thread
        if (externalCameraConnector != null && !captureUrl.isEmpty()) {
            startCaptureThread();
        } else if (listener != null) {
            listener.onError("External camera capture URL not configured");
        }
    }

    @Override
    public void stopCapture() {
        isCapturing = false;

        // Stop bitmap renderer
        if (bitmapRenderer != null) {
            bitmapRenderer.stopRendering();
            bitmapRenderer.clearSurface();
        }

        // Stop the capture thread
        if (captureThread != null && captureThread.isAlive()) {
            captureThread.interrupt();
            try {
                captureThread.join(1000); // Wait up to 1 second for thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (arCoreHandler != null) {
            arCoreHandler.removeArCoreListener();
        }
    }

    @Override
    public boolean isReady() {
        return externalCameraConnector != null && arCoreHandler != null && isCapturing;
    }

    @Override
    public String getDisplayName() {
        return ImageSource.EXTERNAL_CAMERA.getDisplayName();
    }

    @Override
    public void cleanup() {
        stopCapture();
        externalCameraConnector = null;
        arCoreHandler = null;
        listener = null;
    }

    @Override
    public void setImageSourceListener(ImageSourceListener listener) {
        this.listener = listener;
    }

    @Override
    public void setBitmapRenderer(BitmapRenderer renderer) {
        this.bitmapRenderer = renderer;
    }

    /**
     * Start the capture thread that periodically captures images from the external camera
     */
    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            while (isCapturing && !Thread.currentThread().isInterrupted()) {
                try {
                    // Capture an image from the external camera
                    if (externalCameraConnector != null) {
                        externalCameraConnector.captureBitmap(captureUrl, this);
                    }

                    // Wait for the specified interval before next capture
                    Thread.sleep(captureIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError("External camera capture error: " + e.getMessage());
                    }
                }
            }
        });
        captureThread.start();
    }

    // ExternalCameraConnector.BitmapCaptureListener implementation
    @Override
    public void onImageCaptured(Bitmap bitmap) {
        if (!isCapturing) {
            return;
        }

        try {
            if (bitmap != null) {
                // Render bitmap to screen
                if (bitmapRenderer != null) {
                    bitmapRenderer.renderBitmap(bitmap);
                }

                // Send to listener for logging
                if (listener != null) {
                    // Use the latest pose data from ARCore for synchronization
                    listener.onFrameAvailable(bitmap, latestPose, latestCameraIntrinsics, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            if (listener != null) {
                listener.onError("External camera image processing error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onError(Exception e) {
        if (listener != null) {
            listener.onError("External camera error: " + e.getMessage());
        }
    }

    // ArCoreListener implementation for pose synchronization
    @Override
    public void onArCoreUpdate(Pose currentPose, ImageFrame frame, CameraIntrinsics cameraIntrinsics, long timestamp) {
        // Store the latest pose data for synchronization with external camera frames
        this.latestPose = currentPose;
        this.latestCameraIntrinsics = cameraIntrinsics;
    }

    @Override
    public void onRenderedFrame(VideoFrame.I420Buffer frame, long timestamp) {

    }

    @Override
    public void onArCoreTrackingFailure(long timestamp, TrackingFailureReason trackingFailureReason) {

    }

    @Override
    public void onArCoreSessionPaused(long timestamp) {

    }
}
