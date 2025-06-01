package com.satinavrobotics.satibot.arcore.rendering;

import android.content.Context;

import com.satinavrobotics.satibot.arcore.processor.ArCoreProcessor;

import java.nio.ByteBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Interface for ARCore rendering functionality.
 * This interface defines methods for rendering AR content using ARCore.
 */
public interface ARCoreRenderer {
    /**
     * Called when the surface is created.
     * Initialize OpenGL resources here.
     *
     * @param gl     The GL interface
     * @param config The EGL configuration
     * @param context The Android context
     */
    void onSurfaceCreated(GL10 gl, EGLConfig config, Context context);

    /**
     * Called when the surface changes size.
     *
     * @param gl     The GL interface
     * @param width  The new width
     * @param height The new height
     */
    void onSurfaceChanged(GL10 gl, int width, int height);

    /**
     * Draw the AR frame.
     *
     * @param frameData The processed frame data containing all necessary information for rendering
     */
    void drawFrame(ArCoreProcessor.ProcessedFrameData frameData);

    /**
     * Read pixels from the current framebuffer.
     * This should be called after drawFrame if pixel data is needed.
     *
     * @return A ByteBuffer containing the rendered frame data (RGBA) or null if direct rendering is used
     */
    ByteBuffer readPixels();

    /**
     * Get the texture ID used by the background renderer.
     *
     * @return The texture ID
     */
    int getBackgroundTextureId();

    /**
     * Clean up resources when the renderer is no longer needed.
     */
    void cleanup();

    /**
     * Get the current width of the rendering surface.
     *
     * @return The width in pixels
     */
    int getWidth();

    /**
     * Get the current height of the rendering surface.
     *
     * @return The height in pixels
     */
    int getHeight();
}
