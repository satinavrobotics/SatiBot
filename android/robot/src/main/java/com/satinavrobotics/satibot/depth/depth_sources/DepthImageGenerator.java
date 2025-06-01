package com.satinavrobotics.satibot.depth.depth_sources;

import android.content.Context;

import com.google.ar.core.Frame;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Interface for depth image generation.
 * This allows for different implementations (ARCore, TFLite, etc.) to be used interchangeably.
 */
public interface DepthImageGenerator {
    /**
     * Initialize the depth image generator.
     * @param context The application context
     * @throws IOException If initialization fails
     */
    void initialize(Context context) throws IOException;

    /**
     * Update the depth image generator with new data.
     * @param frame The ARCore frame (may be null for non-ARCore implementations)
     * @return true if the depth image was updated successfully
     */
    boolean update(Frame frame);

    /**
     * Get the depth image data.
     * @return The depth image data as a ByteBuffer, or null if not available
     */
    ByteBuffer getDepthImageData();

    /**
     * Get the confidence image data.
     * @return The confidence image data as a ByteBuffer, or null if not available
     */
    ByteBuffer getConfidenceImageData();

    /**
     * Get the width of the depth image.
     * @return The width in pixels
     */
    int getWidth();

    /**
     * Get the height of the depth image.
     * @return The height in pixels
     */
    int getHeight();

    /**
     * Release resources used by the depth image generator.
     */
    void release();

    /**
     * Check if the depth image generator is fully initialized and ready to use.
     * @return true if initialization is complete, false otherwise
     */
    boolean isInitialized();
}
