package com.satinavrobotics.satibot.logging.sources;

import android.graphics.Bitmap;
import com.google.ar.core.Pose;
import com.satinavrobotics.satibot.logging.render.BitmapRenderer;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;

/**
 * Interface for handling different image sources in the logger
 */
public interface ImageSourceHandler {

    /**
     * Initialize the image source handler
     */
    void initialize();

    /**
     * Start capturing from this image source
     */
    void startCapture();

    /**
     * Stop capturing from this image source
     */
    void stopCapture();

    /**
     * Check if this image source is ready to capture
     *
     * @return true if ready, false otherwise
     */
    boolean isReady();

    /**
     * Get the display name for this image source
     *
     * @return Display name
     */
    String getDisplayName();

    /**
     * Cleanup resources
     */
    void cleanup();

    /**
     * Listener interface for image source events
     */
    interface ImageSourceListener {
        /**
         * Called when a new frame is available from the image source
         *
         * @param bitmap The captured image
         * @param pose The pose data (may be null for some sources)
         * @param cameraIntrinsics Camera intrinsics (may be null for some sources)
         * @param timestamp Timestamp of the capture
         */
        void onFrameAvailable(Bitmap bitmap, Pose pose, CameraIntrinsics cameraIntrinsics, long timestamp);

        /**
         * Called when an error occurs
         *
         * @param error The error message
         */
        void onError(String error);
    }

    /**
     * Set the listener for image source events
     *
     * @param listener The listener
     */
    void setImageSourceListener(ImageSourceListener listener);

    /**
     * Set the bitmap renderer for displaying camera feed on screen
     *
     * @param renderer The bitmap renderer
     */
    void setBitmapRenderer(BitmapRenderer renderer);
}
