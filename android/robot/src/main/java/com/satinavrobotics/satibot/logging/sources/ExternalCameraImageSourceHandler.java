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
import timber.log.Timber;

/**
 * Image source handler for External Camera
 * Provides: image, arcore pose, sync
 */
public class ExternalCameraImageSourceHandler implements ImageSourceHandler,
    ExternalCameraConnector.StreamListener, ArCoreListener {

    private ExternalCameraConnector externalCameraConnector;
    private ArCoreHandler arCoreHandler;
    private ImageSourceListener listener;
    private boolean isCapturing = false;
    private String streamUrl = ""; // URL for streaming video
    private BitmapRenderer bitmapRenderer;

    // For synchronization
    private Pose latestPose;
    private CameraIntrinsics latestCameraIntrinsics;

    public ExternalCameraImageSourceHandler(ArCoreHandler arCoreHandler) {
        this.arCoreHandler = arCoreHandler;
        this.externalCameraConnector = new ExternalCameraConnector();
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl;
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

        // Start external camera stream
        if (externalCameraConnector != null && !streamUrl.isEmpty()) {
            externalCameraConnector.connectStream(streamUrl, this);
        } else if (listener != null) {
            listener.onError("External camera stream URL not configured");
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

        // Disconnect from external camera stream
        if (externalCameraConnector != null) {
            externalCameraConnector.disconnect();
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

    // ExternalCameraConnector.StreamListener implementation
    @Override
    public void onFrame(VideoFrame frame) {
        if (!isCapturing) {
            return;
        }

        try {
            if (frame != null && frame.getBuffer() instanceof ExternalCameraConnector.BitmapBuffer) {
                ExternalCameraConnector.BitmapBuffer bitmapBuffer = (ExternalCameraConnector.BitmapBuffer) frame.getBuffer();
                Bitmap bitmap = bitmapBuffer.getBitmap();

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
            }
        } catch (Exception e) {
            if (listener != null) {
                listener.onError("External camera frame processing error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onError(Exception e) {
        if (listener != null) {
            listener.onError("External camera error: " + e.getMessage());
        }
    }

    @Override
    public void onDisconnected() {
        if (listener != null) {
            listener.onError("External camera disconnected");
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
