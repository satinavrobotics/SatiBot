package com.satinavrobotics.satibot.arcore.rendering;

import android.content.Context;
import android.opengl.GLES30;

import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.satinavrobotics.satibot.mapManagement.MapResolvingManager;
import com.satinavrobotics.satibot.arcore.processor.ArCoreProcessor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

/**
 * A simple implementation of ARCoreRenderer interface.
 * This class provides direct rendering of AR content to the screen using ARCore.
 */
public class SimpleARCoreRenderer implements ARCoreRenderer {
    private static final String TAG = SimpleARCoreRenderer.class.getSimpleName();

    // Default dimensions
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;

    // Rendering components
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final TwoDRenderer twoDRenderer = new TwoDRenderer();
    private final float[] anchorMatrix = new float[16];

    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config, Context context) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Initialize ARCore background rendering
        try {
            backgroundRenderer.createOnGlThread(context);
        } catch (IOException e) {
            Timber.e(e, "Failed to create background renderer");
        }

        // Initialize 2D renderer for anchor markers
        twoDRenderer.createOnGlThread(context, "render/gmap_marker.png");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
        this.width = width;
        this.height = height;
    }


    @Override
    public void drawFrame(ArCoreProcessor.ProcessedFrameData frameData) {
        if (frameData == null || frameData.getFrame() == null) {
            Timber.w("Null frame data passed to drawFrame");
            return;
        }

        Frame frame = frameData.getFrame();
        TrackingState trackingState = frameData.getTrackingState();
        float[] viewMatrix = frameData.getViewMatrix();
        float[] projectionMatrix = frameData.getProjectionMatrix();
        Pose currentPose = frameData.getCurrentPose();
        List<MapResolvingManager.ResolvedAnchor> resolvedAnchors = frameData.getResolvedAnchors();

        try {
            // Clear the default framebuffer (screen)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

            // Draw background
            backgroundRenderer.draw(frame);

            // If we have view and projection matrices, prepare for 3D rendering
            if (trackingState == TrackingState.TRACKING && viewMatrix != null && projectionMatrix != null) {
                // Enable depth test for 3D objects
                GLES30.glEnable(GLES30.GL_DEPTH_TEST);

                // Note: Anchor markers will be rendered by the caller after this method returns
                // and before readPixels() is called
            }

            // If tracking and we have matrices, render anchor markers
            if (trackingState == TrackingState.TRACKING && viewMatrix != null && projectionMatrix != null) {
                // Render all resolved cloud anchors
                if (resolvedAnchors != null && !resolvedAnchors.isEmpty()) {
                    for (MapResolvingManager.ResolvedAnchor resolvedAnchor : resolvedAnchors) {
                        renderAnchorMarker(resolvedAnchor.getAnchor().getPose(), viewMatrix, projectionMatrix, currentPose);
                    }
                }
            }

            // Check for OpenGL errors
            ShaderUtil.checkGLError(TAG, "After drawing");
        } catch (Exception e) {
            Timber.e(e, "Error in drawFrame");
        }
    }

    @Override
    public ByteBuffer readPixels() {
        return null;
    }

    private void renderAnchorMarker(Pose pose, float[] viewMatrix, float[] projectionMatrix, Pose currentPose) {
        if (pose == null || viewMatrix == null || projectionMatrix == null) {
            return;
        }

        float[] translation = new float[3];
        float[] rotation = new float[4];
        pose.getTranslation(translation, 0);
        currentPose.getRotationQuaternion(rotation, 0);
        Pose rotatedPose = new Pose(translation, rotation);
        rotatedPose.toMatrix(anchorMatrix, 0);

        float scaleFactor = 0.5f;  // Smaller scale for simplicity
        twoDRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
        twoDRenderer.draw(viewMatrix, projectionMatrix);
    }

    @Override
    public int getBackgroundTextureId() {
        return backgroundRenderer.getTextureId();
    }

    @Override
    public void cleanup() {
        // Nothing to clean up for direct rendering
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }
}
