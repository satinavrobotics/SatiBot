package com.satinavrobotics.satibot.depth;

import android.content.Context;

import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.satinavrobotics.satibot.depth.depth_sources.DepthImageGenerator;
import com.satinavrobotics.satibot.mapManagement.MapResolvingManager;
import com.satinavrobotics.satibot.arcore.processor.ArCoreProcessor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Generates a polar histogram from depth data for robot navigation.
 * The polar histogram represents obstacles at different angles around the robot.
 * Implements ArCoreProcessor interface to be compatible with ArCoreHandler.
 */
public class DepthProcessor implements ArCoreProcessor {
    private static final String TAG = DepthProcessor.class.getSimpleName();

    // Thread management
    private ExecutorService processingExecutor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    // Depth image generator
    private DepthImageGenerator depthImageGenerator;
    private float confidenceThreshold = 0.5f;
    private float tooCloseThreshold = 1000.0f; // Default 100cm in millimeters

    private final float depthGradientThreshold; // Threshold for depth discontinuity
    private final float verticalCloserThreshold; // Threshold for considering a pixel as "vertically closer"
    private final float verticalFartherThreshold; // Threshold for considering a pixel as "vertically farther"
    private final float maxSafeDistance; // Maximum safe distance for navigation
    private final int consecutiveThreshold;

    private int downsampleFactor;



    // Depth image dimensions
    private int depthWidth;
    private int depthHeight;

    // Depth data for visualization
    private short[][] lastDepthArray;
    private short[][] filteredDepthArray; // Median-filtered depth array
    private boolean[][] lastVerticalCloserPixel; // Tracks pixels where next depth is closer (potential drop-offs)
    private boolean[][] lastVerticalFartherPixel; // Tracks pixels where next depth is farther (potential step-ups)
    private boolean[][] lastHorizontalGradientPixel; // Tracks pixels where horizontal gradient surpasses threshold
    private boolean[][] lastTooClosePixel; // Tracks pixels that are too close for safe navigation

    // Navigability maps for left and right windows
    private static final int NUM_ROWS = 12;
    private static final float TOP_PERCENTAGE = 0.7f;
    private boolean[] leftNavigabilityMap = new boolean[NUM_ROWS];
    private boolean[] rightNavigabilityMap = new boolean[NUM_ROWS];

    // Flag to enable/disable horizontal gradient processing
    private boolean horizontalGradientsEnabled = true;

    /**
     * Creates a new PolarHistogramGenerator with default parameters from RobotParametersManager.
     */
    public DepthProcessor() {
        // Get parameters from RobotParametersManager if available, otherwise use defaults
        RobotParametersManager robotParams = RobotParametersManager.getInstance();
        float verticalCloserThreshold = robotParams.getVerticalCloserThreshold();
        float verticalFartherThreshold = robotParams.getVerticalFartherThreshold();
        float maxSafeDistance = robotParams.getMaxSafeDistance();
        int consecutiveThreshold = robotParams.getConsecutiveThreshold();
        int downsampleFactor = robotParams.getDownsampleFactor();
        float depthGradientThreshold = robotParams.getDepthGradientThreshold();
        float tooCloseThreshold = robotParams.getTooCloseThreshold();

        // Initialize with parameters
        this.depthGradientThreshold = depthGradientThreshold;
        this.verticalCloserThreshold = verticalCloserThreshold;
        this.verticalFartherThreshold = verticalFartherThreshold;
        this.maxSafeDistance = maxSafeDistance;
        this.consecutiveThreshold = consecutiveThreshold;
        this.downsampleFactor = downsampleFactor;
        this.tooCloseThreshold = tooCloseThreshold;
        this.depthWidth = 0;
        this.depthHeight = 0;

        // Create thread pool for processing
        processingExecutor = Executors.newSingleThreadExecutor();

        Timber.d("Created PolarHistogramGenerator with parameters from RobotParametersManager: " +
                "verticalCloserThreshold=%.2f mm, verticalFartherThreshold=%.2f mm, maxSafeDistance=%.2f mm, consecutiveThreshold=%d pixels, " +
                "downsampleFactor=%d, depthGradientThreshold=%.2f mm",
                verticalCloserThreshold, verticalFartherThreshold, maxSafeDistance, consecutiveThreshold,
                downsampleFactor, depthGradientThreshold);
    }


    /**
     * Updates the depth data processing using the provided Frame.
     *
     * @param frame The ARCore frame
     * @return true if the processing was updated successfully
     */
    public boolean update(Frame frame) {
        // Check if we have a depth image generator
        if (depthImageGenerator == null || !depthImageGenerator.isInitialized()) {
            Timber.w("Depth generator not initialized or not set");
            return false;
        }

        // Update the depth image generator with the frame
        boolean updated = false;
        try {
            updated = depthImageGenerator.update(frame);
        } catch (Exception e) {
            Timber.e(e, "Error updating depth image generator: %s", e.getMessage());

            // If this is an ONNX generator and it's failing, we should notify the UI
            // to potentially switch to a more stable depth source
            if (depthImageGenerator instanceof com.satinavrobotics.satibot.depth.depth_sources.ONNXDepthImageGenerator) {
                Timber.w("ONNX depth generator failed, consider switching to ARCore depth");
                // We could add a callback here to notify the UI about the failure
            }
            return false;
        }

        if (!updated) {
            // No new depth data available
            return false;
        }

        // Now process the depth data
        return processDepthData();
    }

    /**
     * Updates the depth data processing to detect vertical closer and farther gradients.
     *
     * @param depthGenerator The depth image generator providing the depth data
     * @param confidenceThreshold Threshold for confidence values (0.0-1.0)
     * @return true if the processing was updated successfully
     */
    public boolean update(DepthImageGenerator depthGenerator, float confidenceThreshold) {
        if (depthGenerator == null || !depthGenerator.isInitialized()) {
            return false;
        }

        ByteBuffer depthBuffer = depthGenerator.getDepthImageData();
        ByteBuffer confidenceBuffer = depthGenerator.getConfidenceImageData();

        if (depthBuffer == null || confidenceBuffer == null) {
            return false;
        }

        depthWidth = depthGenerator.getWidth();
        depthHeight = depthGenerator.getHeight();

        if (depthWidth <= 0 || depthHeight <= 0) {
            return false;
        }

        isProcessing.set(true);

        try {
            processDepthData(depthBuffer, confidenceBuffer, confidenceThreshold);
            isProcessing.set(false);
            return true;
        } catch (Exception e) {
            isProcessing.set(false);
            return false;
        }
    }

    /**
     * Processes the depth data from the internal depth image generator.
     *
     * @return true if the processing was updated successfully
     */
    private boolean processDepthData() {
        if (depthImageGenerator == null || !depthImageGenerator.isInitialized()) {
            Timber.w("Depth generator not initialized or not set");
            return false;
        }

        ByteBuffer depthBuffer = depthImageGenerator.getDepthImageData();
        ByteBuffer confidenceBuffer = depthImageGenerator.getConfidenceImageData();

        if (depthBuffer == null || confidenceBuffer == null) {
            return false;
        }

        depthWidth = depthImageGenerator.getWidth();
        depthHeight = depthImageGenerator.getHeight();

        if (depthWidth <= 0 || depthHeight <= 0) {
            return false;
        }

        isProcessing.set(true);

        try {
            processDepthData(depthBuffer, confidenceBuffer, confidenceThreshold);
            isProcessing.set(false);
            return true;
        } catch (Exception e) {
            isProcessing.set(false);
            return false;
        }
    }


    /**
     * Processes depth data to detect vertical closer and farther gradients for visualization.
     * Optimized for performance to ensure real-time visualization.
     * Only marks pixels as vertically closer/farther when there's a consistent tendency of pixels getting closer/farther,
     * rather than marking individual noisy values.
     * Uses downsampling to improve performance.
     *
     * @param depthBuffer The depth buffer (16-bit values in millimeters)
     * @param confidenceBuffer The confidence buffer (8-bit values)
     * @param confidenceThreshold Threshold for confidence values (0.0-1.0)
     */
    private void processDepthData(ByteBuffer depthBuffer, ByteBuffer confidenceBuffer, float confidenceThreshold) {
        // Reset position of buffers
        depthBuffer.rewind();
        confidenceBuffer.rewind();

        ShortBuffer depthShortBuffer = depthBuffer.asShortBuffer();

        if (depthWidth <= 0 || depthHeight <= 0 ||
            depthShortBuffer.capacity() <= 0 || confidenceBuffer.capacity() <= 0) {
            return;
        }

        // Create or reuse arrays to store depth values and analysis results
        if (lastDepthArray == null || lastDepthArray.length != depthHeight ||
            lastDepthArray[0].length != depthWidth) {
            lastDepthArray = new short[depthHeight][depthWidth];
            filteredDepthArray = new short[depthHeight][depthWidth];
            lastVerticalCloserPixel = new boolean[depthHeight][depthWidth];
            lastVerticalFartherPixel = new boolean[depthHeight][depthWidth];
            lastHorizontalGradientPixel = new boolean[depthHeight][depthWidth];
            lastTooClosePixel = new boolean[depthHeight][depthWidth];
        } else {
            // Clear the pixel arrays for reuse
            for (int y = 0; y < depthHeight; y++) {
                Arrays.fill(lastVerticalCloserPixel[y], false);
                Arrays.fill(lastVerticalFartherPixel[y], false);
                Arrays.fill(lastHorizontalGradientPixel[y], false);
                Arrays.fill(lastTooClosePixel[y], false);
            }
        }

        // Fill the depth array with raw depth values directly from the buffer
        // This is faster than using get() for each pixel
        for (int y = 0; y < depthHeight; y++) {
            for (int x = 0; x < depthWidth; x++) {
                int index = y * depthWidth + x;
                if (index < depthShortBuffer.capacity()) {
                    lastDepthArray[y][x] = depthShortBuffer.get(index);
                }
            }
        }

        // Apply downsampling using mean values
        if (downsampleFactor > 1) {
            int downsampledWidth = depthWidth / downsampleFactor;
            int downsampledHeight = depthHeight / downsampleFactor;

            // Create a downsampled depth array using the dedicated method
            short[][] downsampledDepthArray = downsampleDepthArray(lastDepthArray, depthWidth, depthHeight, downsampleFactor);

            // Create downsampled vertical closer and farther arrays
            boolean[][] downsampledVerticalCloserPixel = new boolean[downsampledHeight][downsampledWidth];
            boolean[][] downsampledVerticalFartherPixel = new boolean[downsampledHeight][downsampledWidth];

            int adjustedThreshold = Math.max(1, consecutiveThreshold / downsampleFactor);
            processVerticalGradientsUnified(
                    downsampledDepthArray,
                    downsampledWidth,
                    downsampledHeight,
                    adjustedThreshold,
                    downsampledVerticalCloserPixel,
                    downsampledVerticalFartherPixel);

            // Map the downsampled results back to the original resolution
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    // Calculate corresponding position in downsampled array
                    int downsampledY = y / downsampleFactor;
                    int downsampledX = x / downsampleFactor;

                    // Check bounds to avoid index out of range
                    if (downsampledY < downsampledHeight && downsampledX < downsampledWidth) {
                        // Copy the results from the downsampled arrays
                        lastVerticalCloserPixel[y][x] = downsampledVerticalCloserPixel[downsampledY][downsampledX];
                        lastVerticalFartherPixel[y][x] = downsampledVerticalFartherPixel[downsampledY][downsampledX];
                    }
                }
            }

            // Copy the downsampled depth values to the filtered array (upsampling)
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    int downsampledY = y / downsampleFactor;
                    int downsampledX = x / downsampleFactor;

                    if (downsampledY < downsampledHeight && downsampledX < downsampledWidth) {
                        filteredDepthArray[y][x] = downsampledDepthArray[downsampledY][downsampledX];
                    }
                }
            }

            // Process all filtered depth checks in unified manner (too close + horizontal gradients)
            processFilteredDepthChecksUnified();

            // Compute left and right navigability maps
            computeLeftRightNavigabilityMaps();
        } else {
            // No downsampling, just copy the raw depth values to the filtered array
            for (int y = 0; y < depthHeight; y++) {
                System.arraycopy(lastDepthArray[y], 0, filteredDepthArray[y], 0, depthWidth);
            }

            // Process vertical gradients using unified method
            processVerticalGradientsUnified(
                    filteredDepthArray,
                    depthWidth,
                    depthHeight,
                    consecutiveThreshold,
                    lastVerticalCloserPixel,
                    lastVerticalFartherPixel);

            // Process all filtered depth checks in unified manner (too close + horizontal gradients)
            processFilteredDepthChecksUnified();

            // Compute left and right navigability maps
            computeLeftRightNavigabilityMaps();
        }
    }

    /**
     * Processes horizontal gradients in the depth image.
     * Detects significant changes in depth along horizontal scanlines and marks them as obstacles.
     * Will skip processing if horizontal gradients are disabled.
     */
    private void processHorizontalGradients() {
        if (!horizontalGradientsEnabled) {
            Timber.d("Horizontal gradients disabled, skipping processing");
            // Clear any existing horizontal gradient data
            if (lastHorizontalGradientPixel != null) {
                for (int y = 0; y < depthHeight; y++) {
                    Arrays.fill(lastHorizontalGradientPixel[y], false);
                }
            }
            return;
        }

        if (filteredDepthArray == null || depthWidth <= 0 || depthHeight <= 0) {
            Timber.w("Cannot process horizontal gradients: invalid depth array or dimensions");
            return;
        }

        // Clear previous horizontal gradient data
        if (lastHorizontalGradientPixel != null) {
            for (int y = 0; y < depthHeight; y++) {
                Arrays.fill(lastHorizontalGradientPixel[y], false);
            }
        }

        Timber.d("Processing horizontal gradients with threshold: %.1f mm", depthGradientThreshold);

        int gradientCount = 0;

        // For each row in the image
        for (int y = 0; y < depthHeight; y++) {
            // Process from left to right
            for (int x = 0; x < depthWidth - 1; x++) {
                // Get the current depth value
                float currentDepth = filteredDepthArray[y][x];

                // Skip invalid depth values (0 or negative)
                if (currentDepth <= 0) {
                    continue;
                }

                // Get the depth value of the next pixel to the right
                float nextDepth = filteredDepthArray[y][x + 1];

                // Skip if next depth is invalid
                if (nextDepth <= 0) {
                    continue;
                }

                // Calculate absolute depth difference
                float depthDifference = Math.abs(currentDepth - nextDepth);

                // Check if the gradient exceeds the threshold
                if (depthDifference > depthGradientThreshold) {
                    // Mark both pixels and surrounding pixels as horizontal gradient pixels for better visibility
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = y + dy;
                        if (ny >= 0 && ny < depthHeight) {
                            // Mark a 3x3 area around the gradient for better visibility
                            for (int dx = -1; dx <= 2; dx++) {
                                int nx = x + dx;
                                if (nx >= 0 && nx < depthWidth) {
                                    lastHorizontalGradientPixel[ny][nx] = true;
                                    gradientCount++;
                                }
                            }
                        }
                    }
                }
            }
        }


    }



    /**
     * Processes all filtered depth checks in a unified manner using a single loop.
     * This method combines too close detection and horizontal gradient detection
     * to improve performance by reducing redundant iterations over the filtered depth array.
     * Both checks use the filteredDepthArray for consistency.
     */
    private void processFilteredDepthChecksUnified() {
        if (filteredDepthArray == null || depthWidth <= 0 || depthHeight <= 0) {
            return;
        }

        // Handle horizontal gradients disabled case
        boolean processHorizontalGradients = horizontalGradientsEnabled;
        if (!processHorizontalGradients) {
            if (lastHorizontalGradientPixel != null) {
                for (int y = 0; y < depthHeight; y++) {
                    Arrays.fill(lastHorizontalGradientPixel[y], false);
                }
            }
        }

        // Single loop to process both too close and horizontal gradient detection
        for (int y = 0; y < depthHeight; y++) {
            for (int x = 0; x < depthWidth; x++) {
                float currentDepth = filteredDepthArray[y][x];

                if (currentDepth <= 0) {
                    continue;
                }

                // Check 1: Too close detection
                if (currentDepth < tooCloseThreshold) {
                    lastTooClosePixel[y][x] = true;
                }

                // Check 2: Horizontal gradient detection
                if (processHorizontalGradients && x < depthWidth - 1) {
                    float nextDepth = filteredDepthArray[y][x + 1];

                    if (nextDepth > 0) {
                        float depthDifference = Math.abs(currentDepth - nextDepth);

                        if (depthDifference > depthGradientThreshold) {
                            for (int dy = -1; dy <= 1; dy++) {
                                int ny = y + dy;
                                if (ny >= 0 && ny < depthHeight) {
                                    for (int dx = -1; dx <= 2; dx++) {
                                        int nx = x + dx;
                                        if (nx >= 0 && nx < depthWidth) {
                                            lastHorizontalGradientPixel[ny][nx] = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Processes vertical gradients in a unified manner using a single loop.
     * This method combines vertical closer and vertical farther detection
     * to improve performance by reducing redundant iterations over the depth array.
     *
     * @param depthArray The depth array to process
     * @param width The width of the depth array
     * @param height The height of the depth array
     * @param consecutiveThreshold Number of consecutive pixels needed to detect a trend
     * @param verticalCloserPixels Output array for vertical closer pixels
     * @param verticalFartherPixels Output array for vertical farther pixels
     */
    private void processVerticalGradientsUnified(short[][] depthArray, int width, int height,
                                               int consecutiveThreshold,
                                               boolean[][] verticalCloserPixels,
                                               boolean[][] verticalFartherPixels) {

        // Process depth gradients along vertical scanlines
        // Use the full width of the image for complete visualization
        int startCol = 0;
        int endCol = width;

        // For each column, check for both "closer next" and "farther next" pixels starting from the bottom
        for (int x = startCol; x < endCol; x++) {
            // Process from bottom to top (higher y values are at the bottom of the image)
            int consecutiveCloserCount = 0;
            int consecutiveFartherCount = 0;
            int closerStartY = -1;
            int fartherStartY = -1;

            for (int y = height - 1; y > consecutiveThreshold; y--) {
                // Get the current depth value (used by both checks)
                float currentDepth = depthArray[y][x];

                // Skip invalid depth values (0 or negative)
                if (currentDepth <= 0) {
                    consecutiveCloserCount = 0;
                    consecutiveFartherCount = 0;
                    continue;
                }

                // Check if the current depth is beyond the maximum safe distance
                boolean isTooFar = currentDepth > maxSafeDistance;
                if (isTooFar) {
                    // Mark the current pixel for both checks
                    verticalCloserPixels[y][x] = true;
                    verticalFartherPixels[y][x] = true;
                    // Stop traversability check for this column
                    break;
                }

                // Get the depth value of the pixel above (used by both checks)
                float nextDepth = depthArray[y - 1][x];

                // Skip if next depth is invalid
                if (nextDepth <= 0) {
                    consecutiveCloserCount = 0;
                    consecutiveFartherCount = 0;
                    continue;
                }

                // Calculate depth difference once for both checks
                float depthDifference = currentDepth - nextDepth;

                // Check 1: Vertical closer (potential drop-off)
                boolean isVerticalCloser = depthDifference > verticalCloserThreshold;
                if (isVerticalCloser) {
                    // If this is the first closer pixel in a sequence, record its position
                    if (consecutiveCloserCount == 0) {
                        closerStartY = y;
                    }
                    consecutiveCloserCount++;

                    // If we've found enough consecutive closer pixels, mark them all
                    if (consecutiveCloserCount >= consecutiveThreshold) {
                        // Mark all pixels in the sequence
                        for (int i = 0; i < consecutiveCloserCount; i++) {
                            int pixelY = closerStartY - i;
                            if (pixelY >= 0) {
                                verticalCloserPixels[pixelY][x] = true;
                            }
                        }
                        // Stop traversability check for this column
                        break;
                    }
                } else {
                    // Reset the counter if we don't have a closer pixel
                    consecutiveCloserCount = 0;
                }

                // Check 2: Vertical farther (potential step-up)
                boolean isVerticalFarther = depthDifference < -verticalFartherThreshold;
                if (isVerticalFarther) {
                    // If this is the first farther pixel in a sequence, record its position
                    if (consecutiveFartherCount == 0) {
                        fartherStartY = y;
                    }
                    consecutiveFartherCount++;

                    // If we've found enough consecutive farther pixels, mark them all
                    if (consecutiveFartherCount >= consecutiveThreshold) {
                        // Mark all pixels in the sequence
                        for (int i = 0; i < consecutiveFartherCount; i++) {
                            int pixelY = fartherStartY - i;
                            if (pixelY >= 0) {
                                verticalFartherPixels[pixelY][x] = true;
                            }
                        }
                        // Stop traversability check for this column
                        break;
                    }
                } else {
                    // Reset the counter if we don't have a farther pixel
                    consecutiveFartherCount = 0;
                }
            }
        }
    }

    /**
     * Gets information about pixels that are too close for safe navigation.
     * These represent areas that are closer than the safe threshold.
     *
     * @return 2D boolean array where true indicates a "too close" pixel, or null if not available
     */
    public boolean[][] getTooClosePixelInfo() {
        // Create a new array to avoid exposing internal data
        if (depthWidth <= 0 || depthHeight <= 0) {
            return null;
        }

        boolean[][] tooCloseInfo = new boolean[depthHeight][depthWidth];

        // If we have too close pixel data, return it
        if (lastTooClosePixel != null) {
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    if (y < lastTooClosePixel.length && x < lastTooClosePixel[y].length) {
                        tooCloseInfo[y][x] = lastTooClosePixel[y][x];
                    }
                }
            }
        }

        return tooCloseInfo;
    }

    /**
     * Gets the navigability map for the left window.
     * Each element represents whether the corresponding row is navigable.
     *
     * @return Array of boolean values where true indicates a navigable row, or null if not available
     */
    public boolean[] getLeftNavigabilityMap() {
        if (depthWidth <= 0 || depthHeight <= 0) {
            return null;
        }
        // Return a copy to avoid exposing internal data
        return Arrays.copyOf(leftNavigabilityMap, leftNavigabilityMap.length);
    }

    /**
     * Gets the navigability map for the right window.
     * Each element represents whether the corresponding row is navigable.
     *
     * @return Array of boolean values where true indicates a navigable row, or null if not available
     */
    public boolean[] getRightNavigabilityMap() {
        if (depthWidth <= 0 || depthHeight <= 0) {
            return null;
        }
        // Return a copy to avoid exposing internal data
        return Arrays.copyOf(rightNavigabilityMap, rightNavigabilityMap.length);
    }

    /**
     * Gets information about pixels where the next depth value is closer than the current one.
     * These represent potential drop-offs or edges that could be hazardous for navigation.
     *
     * @return 2D boolean array where true indicates a "vertical closer" pixel, or null if not available
     */
    public boolean[][] getVerticalCloserPixelInfo() {
        // Create a new array to avoid exposing internal data
        if (depthWidth <= 0 || depthHeight <= 0) {
            return null;
        }

        boolean[][] verticalCloserInfo = new boolean[depthHeight][depthWidth];

        // If we have vertical closer pixel data, return it
        if (lastVerticalCloserPixel != null) {
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    if (y < lastVerticalCloserPixel.length && x < lastVerticalCloserPixel[y].length) {
                        verticalCloserInfo[y][x] = lastVerticalCloserPixel[y][x];
                    }
                }
            }
        }

        return verticalCloserInfo;
    }

    /**
     * Gets information about pixels where the next depth value is farther than the current one.
     * These represent potential step-ups or edges that could be hazardous for navigation.
     *
     * @return 2D boolean array where true indicates a "vertical farther" pixel, or null if not available
     */
    public boolean[][] getVerticalFartherPixelInfo() {
        // Create a new array to avoid exposing internal data
        if (depthWidth <= 0 || depthHeight <= 0) {
            return null;
        }

        boolean[][] verticalFartherInfo = new boolean[depthHeight][depthWidth];

        // If we have vertical farther pixel data, return it
        if (lastVerticalFartherPixel != null) {
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    if (y < lastVerticalFartherPixel.length && x < lastVerticalFartherPixel[y].length) {
                        verticalFartherInfo[y][x] = lastVerticalFartherPixel[y][x];
                    }
                }
            }
        }

        return verticalFartherInfo;
    }

    /**
     * Gets information about pixels where horizontal depth gradient exceeds the threshold.
     * These represent potential obstacles or edges in the horizontal direction.
     *
     * @return 2D boolean array where true indicates a significant horizontal gradient, or null if not available
     */
    public boolean[][] getHorizontalGradientInfo() {
        // Create a new array to avoid exposing internal data
        if (depthWidth <= 0 || depthHeight <= 0) {
            return null;
        }

        boolean[][] horizontalGradientInfo = new boolean[depthHeight][depthWidth];

        // If we have horizontal gradient pixel data, return it
        if (lastHorizontalGradientPixel != null) {
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    if (y < lastHorizontalGradientPixel.length && x < lastHorizontalGradientPixel[y].length) {
                        horizontalGradientInfo[y][x] = lastHorizontalGradientPixel[y][x];
                    }
                }
            }
        }

        return horizontalGradientInfo;
    }


    /**
     * Sets whether horizontal gradient processing is enabled.
     * @param enabled true to enable horizontal gradient processing, false to disable
     */
    public void setHorizontalGradientsEnabled(boolean enabled) {
        this.horizontalGradientsEnabled = enabled;
    }

    /**
     * Sets the depth image generator to use.
     * @param generator The depth image generator to use
     * @param context The application context, needed for initialization
     * @throws IOException If initialization fails
     */
    public void setDepthImageGenerator(DepthImageGenerator generator, Context context) throws IOException {
        // Release the old generator if it exists
        if (this.depthImageGenerator != null) {
            this.depthImageGenerator.release();
        }

        // Set and initialize the new generator
        this.depthImageGenerator = generator;
        if (this.depthImageGenerator != null && !this.depthImageGenerator.isInitialized()) {
            this.depthImageGenerator.initialize(context);
        }
    }

    /**
     * Sets the confidence threshold for depth processing.
     * @param threshold The confidence threshold (0.0-1.0)
     */
    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = threshold;
    }

    /**
     * Sets the too close threshold for depth processing.
     * @param thresholdMm The too close threshold in millimeters
     */
    public void setTooCloseThreshold(float thresholdMm) {
        this.tooCloseThreshold = thresholdMm;
    }

    /**
     * Gets the depth image generator.
     * @return The depth image generator
     */
    public DepthImageGenerator getDepthImageGenerator() {
        return depthImageGenerator;
    }

    /**
     * Gets the depth image data from the depth image generator.
     * @return The depth image data as a ByteBuffer, or null if not available
     */
    public ByteBuffer getDepthImageData() {
        if (depthImageGenerator != null && depthImageGenerator.isInitialized()) {
            return depthImageGenerator.getDepthImageData();
        }
        return null;
    }

    /**
     * Gets the confidence image data from the depth image generator.
     * @return The confidence image data as a ByteBuffer, or null if not available
     */
    public ByteBuffer getConfidenceImageData() {
        if (depthImageGenerator != null && depthImageGenerator.isInitialized()) {
            return depthImageGenerator.getConfidenceImageData();
        }
        return null;
    }

    /**
     * Gets the width of the depth image.
     * @return The width in pixels
     */
    public int getWidth() {
        if (depthImageGenerator != null && depthImageGenerator.isInitialized()) {
            return depthImageGenerator.getWidth();
        }
        return depthWidth;
    }

    /**
     * Gets the height of the depth image.
     * @return The height in pixels
     */
    public int getHeight() {
        if (depthImageGenerator != null && depthImageGenerator.isInitialized()) {
            return depthImageGenerator.getHeight();
        }
        return depthHeight;
    }

    /**
     * Releases resources used by the histogram generator.
     */
    public void release() {
        if (processingExecutor != null) {
            processingExecutor.shutdown();
            processingExecutor = null;
        }

        // Release the depth image generator
        if (depthImageGenerator != null) {
            depthImageGenerator.release();
            depthImageGenerator = null;
        }
    }

    /**
     * Process the AR frame before rendering.
     * Implements the ArCoreProcessor interface method.
     *
     * @param frame The ARCore frame
     * @param camera The ARCore camera
     * @param resolvedAnchors List of resolved anchors
     * @return ProcessedFrameData containing the processed data for rendering
     */
    @Override
    public ProcessedFrameData update(Frame frame, Camera camera,
                                    List<MapResolvingManager.ResolvedAnchor> resolvedAnchors) {
        // Process depth data first
        boolean depthUpdated = update(frame);

        // Create and return ProcessedFrameData
        return createProcessedFrameData(frame, camera, resolvedAnchors);
    }

    /**
     * Creates a ProcessedFrameData object from the current state.
     * This method can be called directly to avoid redundant processing.
     *
     * @param frame The ARCore frame
     * @param camera The ARCore camera
     * @param resolvedAnchors List of resolved anchors
     * @return ProcessedFrameData containing the processed data for rendering
     */
    public ProcessedFrameData createProcessedFrameData(Frame frame, Camera camera,
                                                     List<MapResolvingManager.ResolvedAnchor> resolvedAnchors) {
        // Get tracking state and pose from camera
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

        // Check if we have depth data
        boolean hasDepthData = depthImageGenerator != null &&
                              depthImageGenerator.isInitialized() &&
                              lastDepthArray != null;

        // Create ProcessedFrameData with depth information if available
        if (hasDepthData) {
            return new ProcessedFrameData(
                frame,
                trackingState,
                viewMatrix,
                projectionMatrix,
                currentPose,
                resolvedAnchors,
                getDepthImageData(),
                getConfidenceImageData(),
                getWidth(),
                getHeight(),
                getVerticalCloserPixelInfo(),
                getVerticalFartherPixelInfo(),
                getHorizontalGradientInfo(),
                getTooClosePixelInfo()
            );
        } else {
            // Return basic frame data without depth information
            return new ProcessedFrameData(
                frame,
                trackingState,
                viewMatrix,
                projectionMatrix,
                currentPose,
                resolvedAnchors
            );
        }
    }

    /**
     * Downsamples a depth array using mean values.
     * This method takes a high-resolution depth array and creates a lower-resolution version
     * by averaging blocks of pixels. Only valid depth values (> 0) are included in the average.
     *
     * @param originalDepthArray The original high-resolution depth array
     * @param originalWidth The width of the original depth array
     * @param originalHeight The height of the original depth array
     * @param downsampleFactor The factor by which to downsample (e.g., 2 = half resolution)
     * @return A downsampled depth array
     */
    private short[][] downsampleDepthArray(short[][] originalDepthArray, int originalWidth, int originalHeight, int downsampleFactor) {
        // Calculate downsampled dimensions
        int downsampledWidth = originalWidth / downsampleFactor;
        int downsampledHeight = originalHeight / downsampleFactor;

        // Create a downsampled depth array
        short[][] downsampledDepthArray = new short[downsampledHeight][downsampledWidth];

        // Calculate mean values for each block
        for (int y = 0; y < downsampledHeight; y++) {
            for (int x = 0; x < downsampledWidth; x++) {
                // Calculate the corresponding block in the original image
                int startY = y * downsampleFactor;
                int startX = x * downsampleFactor;
                int endY = Math.min(startY + downsampleFactor, originalHeight);
                int endX = Math.min(startX + downsampleFactor, originalWidth);

                // Calculate mean value for this block
                int sum = 0;
                int count = 0;

                for (int blockY = startY; blockY < endY; blockY++) {
                    for (int blockX = startX; blockX < endX; blockX++) {
                        short value = originalDepthArray[blockY][blockX];
                        // Only include valid depth values in the mean
                        if (value > 0) {
                            sum += value;
                            count++;
                        }
                    }
                }

                // Store the mean value
                if (count > 0) {
                    downsampledDepthArray[y][x] = (short)(sum / count);
                } else {
                    downsampledDepthArray[y][x] = 0; // No valid values in this block
                }
            }
        }

        return downsampledDepthArray;
    }

    /**
     * Computes "vertical closer" pixels in a depth array.
     * These are pixels where the next depth value (in the upward direction) is closer by more than the threshold,
     * indicating potential drop-offs or edges that could be hazardous for navigation.
     * Only marks pixels as "vertical closer" when there's a consistent tendency of pixels getting closer,
     * rather than marking individual noisy values.
     *
     * @param depthArray The depth array to process
     * @param width The width of the depth array
     * @param height The height of the depth array
     * @param consecutiveThreshold Number of consecutive pixels needed to detect a trend
     * @param verticalCloserThreshold Threshold for considering a pixel as "vertical closer" in millimeters
     * @param maxSafeDistance Maximum safe distance in millimeters
     * @return A boolean array where true indicates a "vertical closer" pixel
     */
    private boolean[][] computeVerticalCloserPixels(short[][] depthArray, int width, int height,
                                                   int consecutiveThreshold, float verticalCloserThreshold,
                                                   float maxSafeDistance) {
        // Create a boolean array to store the results
        boolean[][] verticalCloserPixels = new boolean[height][width];

        // Process depth gradients along vertical scanlines
        // Use the full width of the image for complete visualization
        int startCol = 0;
        int endCol = width;

        // For each column, check for "closer next" pixels (drop-offs) starting from the bottom
        for (int x = startCol; x < endCol; x++) {
            // Process from bottom to top (higher y values are at the bottom of the image)
            int consecutiveCloserCount = 0;
            int startY = -1;

            for (int y = height - 1; y > consecutiveThreshold; y--) {
                // Get the current depth value
                float currentDepth = depthArray[y][x];

                // Skip invalid depth values (0 or negative)
                if (currentDepth <= 0) {
                    consecutiveCloserCount = 0;
                    continue;
                }

                // Check if the current depth is beyond the maximum safe distance
                boolean isTooFar = currentDepth > maxSafeDistance;
                if (isTooFar) {
                    // Mark the current pixel
                    verticalCloserPixels[y][x] = true;
                    // Stop traversability check for this column
                    break;
                }

                // Get the depth value of the pixel above
                float nextDepth = depthArray[y - 1][x];

                // Skip if next depth is invalid
                if (nextDepth <= 0) {
                    consecutiveCloserCount = 0;
                    continue;
                }

                // Check if pixel above is closer by more than the threshold (potential drop-off)
                float depthDifference = currentDepth - nextDepth;
                boolean isVerticalCloser = depthDifference > verticalCloserThreshold;

                if (isVerticalCloser) {
                    // If this is the first closer pixel in a sequence, record its position
                    if (consecutiveCloserCount == 0) {
                        startY = y;
                    }
                    consecutiveCloserCount++;

                    // If we've found enough consecutive closer pixels, mark them all
                    if (consecutiveCloserCount >= consecutiveThreshold) {
                        // Mark all pixels in the sequence
                        for (int i = 0; i < consecutiveCloserCount; i++) {
                            int pixelY = startY - i;
                            if (pixelY >= 0) {
                                verticalCloserPixels[pixelY][x] = true;
                            }
                        }
                        // Stop traversability check for this column
                        break;
                    }
                } else {
                    // Reset the counter if we don't have a closer pixel
                    consecutiveCloserCount = 0;
                }
            }
        }

        return verticalCloserPixels;
    }

    /**
     * Computes "vertical farther" pixels in a depth array.
     * These are pixels where the next depth value (in the upward direction) is farther by more than the threshold,
     * indicating potential step-ups or edges that could be hazardous for navigation.
     * Only marks pixels as "vertical farther" when there's a consistent tendency of pixels getting farther,
     * rather than marking individual noisy values.
     *
     * @param depthArray The depth array to process
     * @param width The width of the depth array
     * @param height The height of the depth array
     * @param consecutiveThreshold Number of consecutive pixels needed to detect a trend
     * @param verticalFartherThreshold Threshold for considering a pixel as "vertical farther" in millimeters
     * @param maxSafeDistance Maximum safe distance in millimeters
     * @return A boolean array where true indicates a "vertical farther" pixel
     */
    private boolean[][] computeVerticalFartherPixels(short[][] depthArray, int width, int height,
                                                    int consecutiveThreshold, float verticalFartherThreshold,
                                                    float maxSafeDistance) {
        // Create a boolean array to store the results
        boolean[][] verticalFartherPixels = new boolean[height][width];

        // Process depth gradients along vertical scanlines
        // Use the full width of the image for complete visualization
        int startCol = 0;
        int endCol = width;

        // For each column, check for "vertical farther" pixels (step-ups) starting from the bottom
        for (int x = startCol; x < endCol; x++) {
            // Process from bottom to top (higher y values are at the bottom of the image)
            int consecutiveFartherCount = 0;
            int startY = -1;

            for (int y = height - 1; y > consecutiveThreshold; y--) {
                // Get the current depth value
                float currentDepth = depthArray[y][x];

                // Skip invalid depth values (0 or negative)
                if (currentDepth <= 0) {
                    consecutiveFartherCount = 0;
                    continue;
                }

                // Check if the current depth is beyond the maximum safe distance
                boolean isTooFar = currentDepth > maxSafeDistance;
                if (isTooFar) {
                    // Mark the current pixel
                    verticalFartherPixels[y][x] = true;
                    // Stop traversability check for this column
                    break;
                }

                // Get the depth value of the pixel above
                float nextDepth = depthArray[y - 1][x];

                // Skip if next depth is invalid
                if (nextDepth <= 0) {
                    consecutiveFartherCount = 0;
                    continue;
                }

                // Check if pixel above is farther by more than the threshold (potential step-up)
                float depthDifference = nextDepth - currentDepth;
                boolean isVerticalFarther = depthDifference > verticalFartherThreshold;

                if (isVerticalFarther) {
                    // If this is the first farther pixel in a sequence, record its position
                    if (consecutiveFartherCount == 0) {
                        startY = y;
                    }
                    consecutiveFartherCount++;

                    // If we've found enough consecutive farther pixels, mark them all
                    if (consecutiveFartherCount >= consecutiveThreshold) {
                        // Mark all pixels in the sequence
                        for (int i = 0; i < consecutiveFartherCount; i++) {
                            int pixelY = startY - i;
                            if (pixelY >= 0) {
                                verticalFartherPixels[pixelY][x] = true;
                            }
                        }
                        // Stop traversability check for this column
                        break;
                    }
                } else {
                    // Reset the counter if we don't have a farther pixel
                    consecutiveFartherCount = 0;
                }
            }
        }

        return verticalFartherPixels;
    }

    /**
     * Computes navigability map for a specified window region.
     * This method analyzes the depth data within the given bounds and determines
     * navigability for each row based on obstacle density.
     *
     * @param startX Left boundary of the window (inclusive)
     * @param endX Right boundary of the window (inclusive)
     * @param topY Top boundary of the analysis region
     * @param bottomY Bottom boundary of the analysis region
     * @param numRows Number of rows to divide the region into
     * @return Array of boolean values where true indicates a navigable row
     */
    private boolean[] computeNavigabilityMap(int startX, int endX, int topY, int bottomY, int numRows) {
        boolean[] navigabilityMap = new boolean[numRows];

        // Ensure valid bounds
        startX = Math.max(0, startX);
        endX = Math.min(depthWidth - 1, endX);
        topY = Math.max(0, topY);
        bottomY = Math.min(depthHeight - 1, bottomY);

        if (startX >= endX || topY >= bottomY) {
            // Invalid bounds, return all false
            Arrays.fill(navigabilityMap, false);
            return navigabilityMap;
        }

        int rowHeight = (bottomY - topY) / numRows;
        if (rowHeight <= 0) {
            rowHeight = 1;
        }

        int navigabilityThreshold = RobotParametersManager.getInstance().getNavigabilityThreshold();
        float freeThreshold = (100 - navigabilityThreshold) / 100.0f;

        for (int row = 0; row < numRows; row++) {
            int rowTopY = topY + row * rowHeight;
            int rowBottomY = Math.min(rowTopY + rowHeight, bottomY);

            int obstacleCount = 0;
            int totalPixels = 0;

            // Count obstacles in this row within the specified window
            for (int x = startX; x <= endX; x++) {
                for (int y = rowTopY; y <= rowBottomY; y++) {
                    if (y < lastVerticalCloserPixel.length && x < lastVerticalCloserPixel[y].length &&
                        y < lastVerticalFartherPixel.length && x < lastVerticalFartherPixel[y].length &&
                        y < lastHorizontalGradientPixel.length && x < lastHorizontalGradientPixel[y].length) {
                        totalPixels++;
                        if (lastVerticalCloserPixel[y][x] || lastVerticalFartherPixel[y][x] || lastHorizontalGradientPixel[y][x]) {
                            obstacleCount++;
                        }
                    }
                }
            }

            // Calculate navigability for this row
            float freePixelRatio = totalPixels > 0 ? 1.0f - ((float)obstacleCount / totalPixels) : 0;
            navigabilityMap[row] = freePixelRatio >= freeThreshold;
        }

        return navigabilityMap;
    }

    /**
     * Computes navigability maps for the left and right windows.
     * This method calculates the window boundaries based on robot bounds and
     * updates the left and right navigability maps.
     */
    private void computeLeftRightNavigabilityMaps() {
        if (depthWidth <= 0 || depthHeight <= 0) {
            // Initialize with all false if no valid depth data
            Arrays.fill(leftNavigabilityMap, false);
            Arrays.fill(rightNavigabilityMap, false);
            return;
        }

        // Get robot bounds
        float[] boundsRelative = RobotParametersManager.getInstance().calculateRobotBoundsRelative();
        float leftXRatio = boundsRelative[0];
        float rightXRatio = boundsRelative[1];

        // Convert to pixel coordinates
        int robotBoundsLeftX = Math.max(0, Math.round(leftXRatio * depthWidth));
        int robotBoundsRightX = Math.min(depthWidth - 1, Math.round(rightXRatio * depthWidth));

        // Define the analysis region (same as used in NavMapOverlay)
        int bottomY = depthHeight - 1;
        int topY = (int)(depthHeight * (1 - TOP_PERCENTAGE));

        // Calculate window boundaries with potential overlap
        // Left window: from 0 to robot bounds left edge (or with overlap if needed)
        int leftWindowStartX = 0;
        int leftWindowEndX = robotBoundsLeftX;

        // Right window: from robot bounds right edge to width-1 (or with overlap if needed)
        int rightWindowStartX = robotBoundsRightX;
        int rightWindowEndX = depthWidth - 1;

        // Handle cases where screen space doesn't fit into side bounds by allowing overlap
        // Ensure minimum window width of at least 10% of screen width
        int minWindowWidth = Math.max(1, depthWidth / 10);

        if (leftWindowEndX - leftWindowStartX < minWindowWidth) {
            leftWindowEndX = Math.min(depthWidth - 1, leftWindowStartX + minWindowWidth);
        }

        if (rightWindowEndX - rightWindowStartX < minWindowWidth) {
            rightWindowStartX = Math.max(0, rightWindowEndX - minWindowWidth);
        }

        // Compute navigability maps for both windows
        leftNavigabilityMap = computeNavigabilityMap(leftWindowStartX, leftWindowEndX, topY, bottomY, NUM_ROWS);
        rightNavigabilityMap = computeNavigabilityMap(rightWindowStartX, rightWindowEndX, topY, bottomY, NUM_ROWS);
    }
}
