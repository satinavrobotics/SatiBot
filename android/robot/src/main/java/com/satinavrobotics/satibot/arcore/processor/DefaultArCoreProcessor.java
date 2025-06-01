package com.satinavrobotics.satibot.arcore.processor;

import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.satinavrobotics.satibot.mapManagement.MapResolvingManager;

import java.util.List;

/**
 * Default implementation of the ArCoreProcessor interface.
 * This processor simply extracts the necessary data from the camera and passes it through.
 */
public class DefaultArCoreProcessor implements ArCoreProcessor {

    /**
     * Process the AR frame before rendering.
     * This default implementation simply extracts the necessary data from the camera and passes it through.
     *
     * @param frame The ARCore frame
     * @param camera The ARCore camera
     * @param resolvedAnchors List of resolved anchors
     * @return ProcessedFrameData containing the processed data for rendering
     */
    @Override
    public ProcessedFrameData update(Frame frame, Camera camera, 
                                    List<MapResolvingManager.ResolvedAnchor> resolvedAnchors) {
        TrackingState trackingState = camera.getTrackingState();
        Pose currentPose = camera.getPose();
        
        // Get camera matrices for 3D rendering if tracking
        float[] projectionMatrix = null;
        float[] viewMatrix = null;

        if (trackingState == TrackingState.TRACKING) {
            projectionMatrix = new float[16];
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
            viewMatrix = new float[16];
            camera.getViewMatrix(viewMatrix, 0);
        }
        
        // Return the processed data
        return new ProcessedFrameData(frame, trackingState, viewMatrix, projectionMatrix, currentPose, resolvedAnchors);
    }
}
