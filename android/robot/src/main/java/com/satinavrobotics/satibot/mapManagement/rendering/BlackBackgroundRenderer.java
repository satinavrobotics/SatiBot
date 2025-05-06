package com.satinavrobotics.satibot.mapManagement.rendering;

import android.content.Context;
import android.opengl.GLES20;

import java.io.IOException;

import timber.log.Timber;

/**
 * Simplified background renderer that only renders a black background.
 * This is specifically designed for use with the DepthMapPointCloudRenderer
 * to provide a clean black background for point cloud visualization.
 */
public class BlackBackgroundRenderer {
    
    private boolean isInitialized = false;
    
    /**
     * Creates and initializes OpenGL resources needed for rendering the background.
     * Must be called on the OpenGL thread.
     */
    public void createOnGlThread(Context context) throws IOException {
        // No complex shader programs or textures needed for a black background
        isInitialized = true;
        Timber.d("BlackBackgroundRenderer initialized");
    }
    
    /**
     * Draws a solid black background.
     * This method simply clears the screen to black.
     */
    public void draw() {
        if (!isInitialized) {
            Timber.w("BlackBackgroundRenderer not initialized");
            return;
        }
        
        // No need to test or write depth, we're just clearing the screen
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        
        // Clear the screen to black
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Re-enable depth testing for subsequent rendering
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
    }
    
    /**
     * Checks if the renderer has been initialized.
     */
    public boolean isInitialized() {
        return isInitialized;
    }
}
