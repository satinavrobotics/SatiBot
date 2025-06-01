package com.satinavrobotics.satibot.depth.depth_sources;

import android.content.Context;
import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.io.IOException;
import java.nio.ByteBuffer;

import timber.log.Timber;

/**
 * Implementation of DepthImageGenerator that uses ARCore's depth API.
 */
public class ARCoreDepthImageGenerator implements DepthImageGenerator {
    private static final String TAG = ARCoreDepthImageGenerator.class.getSimpleName();

    private ByteBuffer depthImageBuffer;
    private ByteBuffer confidenceImageBuffer;
    private int width;
    private int height;

    /**
     * Creates a new ARCoreDepthImageGenerator.
     */
    public ARCoreDepthImageGenerator() {
        // Initialize with empty buffers
        depthImageBuffer = null;
        confidenceImageBuffer = null;
        width = 0;
        height = 0;
    }

    @Override
    public void initialize(Context context) throws IOException {
        // No specific initialization needed for ARCore depth
        Timber.d("ARCore depth image generator initialized");
    }

    @Override
    public boolean update(Frame frame) {
        if (frame == null) {
            Timber.w("Frame is null, cannot update depth image");
            return false;
        }

        try {
            // Get the depth image from ARCore
            Image depthImage = frame.acquireDepthImage16Bits();
            Image confidenceImage = frame.acquireRawDepthConfidenceImage();

            // Get depth image dimensions
            width = depthImage.getWidth();
            height = depthImage.getHeight();

            if (width <= 0 || height <= 0) {
                Timber.e("Invalid depth image dimensions: %d x %d", width, height);
                depthImage.close();
                confidenceImage.close();
                return false;
            }

            // Frame dimensions will be updated in DepthVisualizationFragment

            // Get the depth image data
            Image.Plane depthImagePlane = depthImage.getPlanes()[0];
            int depthPixelStride = depthImagePlane.getPixelStride();
            int depthRowStride = depthImagePlane.getRowStride();
            ByteBuffer depthBuffer = depthImagePlane.getBuffer();

            // Get the confidence image data
            Image.Plane confidenceImagePlane = confidenceImage.getPlanes()[0];
            int confidencePixelStride = confidenceImagePlane.getPixelStride();
            int confidenceRowStride = confidenceImagePlane.getRowStride();
            ByteBuffer confidenceBuffer = confidenceImagePlane.getBuffer();

            // Create new buffers if needed
            if (depthImageBuffer == null || depthImageBuffer.capacity() != width * height * 2) {
                depthImageBuffer = ByteBuffer.allocateDirect(width * height * 2);
            }
            if (confidenceImageBuffer == null || confidenceImageBuffer.capacity() != width * height) {
                confidenceImageBuffer = ByteBuffer.allocateDirect(width * height);
            }

            // Reset position to beginning
            depthImageBuffer.rewind();
            confidenceImageBuffer.rewind();

            // Copy the data to our buffers
            copyImageData(depthBuffer, depthImageBuffer, width, height, depthPixelStride, depthRowStride, 2);
            copyImageData(confidenceBuffer, confidenceImageBuffer, width, height, confidencePixelStride, confidenceRowStride, 1);

            // Close the images to release resources
            depthImage.close();
            confidenceImage.close();

            return true;
        } catch (NotYetAvailableException e) {
            Timber.d("Depth not yet available: %s", e.getMessage());
            return false;
        } catch (IllegalStateException e) {
            Timber.e(e, "ARCore depth not available - depth mode may not be enabled or supported: %s", e.getMessage());
            return false;
        } catch (Exception e) {
            Timber.e(e, "Error updating depth image: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Copies image data from source to destination buffer.
     */
    private void copyImageData(ByteBuffer src, ByteBuffer dst, int width, int height,
                              int pixelStride, int rowStride, int bytesPerPixel) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcOffset = y * rowStride + x * pixelStride;
                for (int b = 0; b < bytesPerPixel; b++) {
                    dst.put(src.get(srcOffset + b));
                }
            }
        }
        dst.rewind();
    }

    @Override
    public ByteBuffer getDepthImageData() {
        return depthImageBuffer;
    }

    @Override
    public ByteBuffer getConfidenceImageData() {
        return confidenceImageBuffer;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void release() {
        // No resources to release
    }

    @Override
    public boolean isInitialized() {
        // ARCore depth generator is always considered initialized after the initialize method is called
        return true;
    }
}
