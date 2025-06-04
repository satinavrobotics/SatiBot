package com.satinavrobotics.satibot.arcore.processor;

import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.satinavrobotics.satibot.mapManagement.MapResolvingManager;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Interface for ARCore processing functionality.
 * This interface defines methods for processing AR frames before rendering.
 */
public interface ArCoreProcessor {
    /**
     * Process the AR frame before rendering.
     *
     * @param frame The ARCore frame
     * @param camera The ARCore camera
     * @param resolvedAnchors List of resolved anchors
     * @return ProcessedFrameData containing the processed data for rendering
     */
    ProcessedFrameData update(Frame frame, Camera camera,
                             List<MapResolvingManager.ResolvedAnchor> resolvedAnchors);

    /**
     * Data class to hold processed frame data.
     */
    class ProcessedFrameData {
        private final Frame frame;
        private final TrackingState trackingState;
        private final float[] viewMatrix;
        private final float[] projectionMatrix;
        private final Pose currentPose;
        private final List<MapResolvingManager.ResolvedAnchor> resolvedAnchors;

        // Optional depth data fields
        private ByteBuffer depthImageData;
        private ByteBuffer confidenceImageData;
        private int depthWidth;
        private int depthHeight;
        private boolean[][] closerNextPixelInfo;
        private boolean[][] verticalFartherPixelInfo;
        private boolean[][] horizontalGradientInfo;
        private boolean[][] tooClosePixelInfo;

        public ProcessedFrameData(Frame frame, TrackingState trackingState, float[] viewMatrix,
                                 float[] projectionMatrix, Pose currentPose,
                                 List<MapResolvingManager.ResolvedAnchor> resolvedAnchors) {
            this.frame = frame;
            this.trackingState = trackingState;
            this.viewMatrix = viewMatrix;
            this.projectionMatrix = projectionMatrix;
            this.currentPose = currentPose;
            this.resolvedAnchors = resolvedAnchors;

            // Initialize depth data fields to null/default values
            this.depthImageData = null;
            this.confidenceImageData = null;
            this.depthWidth = 0;
            this.depthHeight = 0;
            this.closerNextPixelInfo = null;
            this.verticalFartherPixelInfo = null;
            this.horizontalGradientInfo = null;
            this.tooClosePixelInfo = null;
        }

        public ProcessedFrameData(Frame frame, TrackingState trackingState, float[] viewMatrix,
                                 float[] projectionMatrix, Pose currentPose,
                                 List<MapResolvingManager.ResolvedAnchor> resolvedAnchors,
                                 ByteBuffer depthImageData, ByteBuffer confidenceImageData,
                                 int depthWidth, int depthHeight,
                                 boolean[][] closerNextPixelInfo, boolean[][] verticalFartherPixelInfo,
                                 boolean[][] horizontalGradientInfo, boolean[][] tooClosePixelInfo) {
            this.frame = frame;
            this.trackingState = trackingState;
            this.viewMatrix = viewMatrix;
            this.projectionMatrix = projectionMatrix;
            this.currentPose = currentPose;
            this.resolvedAnchors = resolvedAnchors;

            // Set depth data fields
            this.depthImageData = depthImageData;
            this.confidenceImageData = confidenceImageData;
            this.depthWidth = depthWidth;
            this.depthHeight = depthHeight;
            this.closerNextPixelInfo = closerNextPixelInfo;
            this.verticalFartherPixelInfo = verticalFartherPixelInfo;
            this.horizontalGradientInfo = horizontalGradientInfo;
            this.tooClosePixelInfo = tooClosePixelInfo;
        }

        public Frame getFrame() {
            return frame;
        }

        public TrackingState getTrackingState() {
            return trackingState;
        }

        public float[] getViewMatrix() {
            return viewMatrix;
        }

        public float[] getProjectionMatrix() {
            return projectionMatrix;
        }

        public Pose getCurrentPose() {
            return currentPose;
        }

        public List<MapResolvingManager.ResolvedAnchor> getResolvedAnchors() {
            return resolvedAnchors;
        }

        // Getters for depth data fields
        public ByteBuffer getDepthImageData() {
            return depthImageData;
        }

        public ByteBuffer getConfidenceImageData() {
            return confidenceImageData;
        }

        public int getDepthWidth() {
            return depthWidth;
        }

        public int getDepthHeight() {
            return depthHeight;
        }

        public boolean[][] getCloserNextPixelInfo() {
            return closerNextPixelInfo;
        }

        public boolean[][] getVerticalFartherPixelInfo() {
            return verticalFartherPixelInfo;
        }

        public boolean[][] getHorizontalGradientInfo() {
            return horizontalGradientInfo;
        }

        public boolean[][] getTooClosePixelInfo() {
            return tooClosePixelInfo;
        }

        public boolean hasDepthData() {
            return depthImageData != null && confidenceImageData != null && depthWidth > 0 && depthHeight > 0;
        }
    }
}
