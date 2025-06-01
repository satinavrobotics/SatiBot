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

    private final float depthGradientThreshold; // Threshold for depth discontinuity
    private final float closerNextThreshold; // Threshold for considering a pixel as "closer next"
    private final float maxSafeDistance; // Maximum safe distance for navigation
    private final int consecutiveThreshold;

    private int downsampleFactor;



    // Depth image dimensions
    private int depthWidth;
    private int depthHeight;

    // Depth data for visualization
    private short[][] lastDepthArray;
    private short[][] filteredDepthArray; // Median-filtered depth array
    private boolean[][] lastCloserNextPixel; // Tracks pixels where next depth is closer (potential drop-offs)
    private boolean[][] lastHorizontalGradientPixel; // Tracks pixels where horizontal gradient surpasses threshold

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
        float closerNextThreshold = robotParams.getCloserNextThreshold();
        float maxSafeDistance = robotParams.getMaxSafeDistance();
        int consecutiveThreshold = robotParams.getConsecutiveThreshold();
        int downsampleFactor = robotParams.getDownsampleFactor();
        float depthGradientThreshold = robotParams.getDepthGradientThreshold();

        // Initialize with parameters
        this.depthGradientThreshold = depthGradientThreshold;
        this.closerNextThreshold = closerNextThreshold;
        this.maxSafeDistance = maxSafeDistance;
        this.consecutiveThreshold = consecutiveThreshold;
        this.downsampleFactor = downsampleFactor;
        this.depthWidth = 0;
        this.depthHeight = 0;

        // Create thread pool for processing
        processingExecutor = Executors.newSingleThreadExecutor();

        Timber.d("Created PolarHistogramGenerator with parameters from RobotParametersManager: " +
                "closerNextThreshold=%.2f mm, maxSafeDistance=%.2f mm, consecutiveThreshold=%d pixels, " +
                "downsampleFactor=%d, depthGradientThreshold=%.2f mm",
                closerNextThreshold, maxSafeDistance, consecutiveThreshold,
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
        boolean updated = depthImageGenerator.update(frame);

        if (!updated) {
            // No new depth data available
            return false;
        }

        // Now process the depth data
        return processDepthData();
    }

    /**
     * Updates the depth data processing to detect isCloserNext gradients.
     *
     * @param depthGenerator The depth image generator providing the depth data
     * @param confidenceThreshold Threshold for confidence values (0.0-1.0)
     * @return true if the processing was updated successfully
     */
    public boolean update(DepthImageGenerator depthGenerator, float confidenceThreshold) {
        // Allow processing even if another thread is processing
        // This ensures we always get the latest data
        if (depthGenerator == null || !depthGenerator.isInitialized()) {
            Timber.w("Depth generator not initialized");
            return false;
        }

        ByteBuffer depthBuffer = depthGenerator.getDepthImageData();
        ByteBuffer confidenceBuffer = depthGenerator.getConfidenceImageData();

        if (depthBuffer == null || confidenceBuffer == null) {
            Timber.w("Depth or confidence buffer not available");
            return false;
        }

        depthWidth = depthGenerator.getWidth();
        depthHeight = depthGenerator.getHeight();

        if (depthWidth <= 0 || depthHeight <= 0) {
            Timber.e("Invalid depth image dimensions: %d x %d", depthWidth, depthHeight);
            return false;
        }

        isProcessing.set(true);

        try {
            // Process depth data to detect isCloserNext gradients
            processDepthData(depthBuffer, confidenceBuffer, confidenceThreshold);
            isProcessing.set(false);
            return true;
        } catch (Exception e) {
            Timber.e(e, "Error processing depth data: %s", e.getMessage());
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
            Timber.w("Depth or confidence buffer not available");
            return false;
        }

        depthWidth = depthImageGenerator.getWidth();
        depthHeight = depthImageGenerator.getHeight();

        if (depthWidth <= 0 || depthHeight <= 0) {
            Timber.e("Invalid depth image dimensions: %d x %d", depthWidth, depthHeight);
            return false;
        }

        isProcessing.set(true);

        try {
            // Process depth data to detect isCloserNext gradients
            processDepthData(depthBuffer, confidenceBuffer, confidenceThreshold);
            isProcessing.set(false);
            return true;
        } catch (Exception e) {
            Timber.e(e, "Error processing depth data: %s", e.getMessage());
            isProcessing.set(false);
            return false;
        }
    }


    /**
     * Processes depth data to detect isCloserNext gradients for visualization.
     * Optimized for performance to ensure real-time visualization.
     * Only marks pixels as isCloserNext when there's a consistent tendency of pixels getting closer,
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

        // Convert to ShortBuffer for easier access to 16-bit depth values
        ShortBuffer depthShortBuffer = depthBuffer.asShortBuffer();

        // Make sure we have valid dimensions
        if (depthWidth <= 0 || depthHeight <= 0 ||
            depthShortBuffer.capacity() <= 0 || confidenceBuffer.capacity() <= 0) {
            Timber.e("Invalid dimensions or empty buffers, cannot process depth data");
            return;
        }

        // Create or reuse arrays to store depth values and analysis results
        if (lastDepthArray == null || lastDepthArray.length != depthHeight ||
            lastDepthArray[0].length != depthWidth) {
            lastDepthArray = new short[depthHeight][depthWidth];
            filteredDepthArray = new short[depthHeight][depthWidth];
            lastCloserNextPixel = new boolean[depthHeight][depthWidth];
            lastHorizontalGradientPixel = new boolean[depthHeight][depthWidth];
        } else {
            // Clear the closer next pixel array for reuse
            for (int y = 0; y < depthHeight; y++) {
                Arrays.fill(lastCloserNextPixel[y], false);
                Arrays.fill(lastHorizontalGradientPixel[y], false);
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
            // Calculate downsampled dimensions
            int downsampledWidth = depthWidth / downsampleFactor;
            int downsampledHeight = depthHeight / downsampleFactor;

            Timber.d("Downsampling depth image by factor %d: %dx%d -> %dx%d",
                    downsampleFactor, depthWidth, depthHeight, downsampledWidth, downsampledHeight);

            // Create a downsampled depth array using the dedicated method
            short[][] downsampledDepthArray = downsampleDepthArray(lastDepthArray, depthWidth, depthHeight, downsampleFactor);

            // Create a downsampled isCloserNext array
            boolean[][] downsampledCloserNextPixel = new boolean[downsampledHeight][downsampledWidth];

            // Process depth gradients on the downsampled array
            // Compute closer next pixels using the dedicated method
            // Adjust the consecutive threshold based on the downsampling factor
            int adjustedThreshold = Math.max(1, consecutiveThreshold / downsampleFactor);
            downsampledCloserNextPixel = computeCloserNextPixels(
                    downsampledDepthArray,
                    downsampledWidth,
                    downsampledHeight,
                    adjustedThreshold,
                    closerNextThreshold,
                    maxSafeDistance);

            // TODO: itt miért map-eljük vissza?
            // Map the downsampled results back to the original resolution
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    // Calculate corresponding position in downsampled array
                    int downsampledY = y / downsampleFactor;
                    int downsampledX = x / downsampleFactor;

                    // Check bounds to avoid index out of range
                    if (downsampledY < downsampledHeight && downsampledX < downsampledWidth) {
                        // Copy the result from the downsampled array
                        lastCloserNextPixel[y][x] = downsampledCloserNextPixel[downsampledY][downsampledX];
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

            // Process horizontal gradients even in downsampled case
            processHorizontalGradients();

            // Compute left and right navigability maps
            computeLeftRightNavigabilityMaps();
        } else {
            // No downsampling, just copy the raw depth values to the filtered array
            for (int y = 0; y < depthHeight; y++) {
                System.arraycopy(lastDepthArray[y], 0, filteredDepthArray[y], 0, depthWidth);
            }

            // Process depth gradients along vertical scanlines
            // Compute closer next pixels using the dedicated method
            boolean[][] closerNextPixels = computeCloserNextPixels(
                    filteredDepthArray,
                    depthWidth,
                    depthHeight,
                    consecutiveThreshold,
                    closerNextThreshold,
                    maxSafeDistance);

            // Copy the results to the lastCloserNextPixel array
            for (int y = 0; y < depthHeight; y++) {
                if (depthWidth >= 0)
                    System.arraycopy(closerNextPixels[y], 0, lastCloserNextPixel[y], 0, depthWidth);
            }

            // Process horizontal gradients
            processHorizontalGradients();

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

        Timber.d("Detected %d horizontal gradient pixels", gradientCount);
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
     * @return 2D boolean array where true indicates a "closer next" pixel, or null if not available
     */
    public boolean[][] getCloserNextPixelInfo() {
        // Create a new array to avoid exposing internal data
        if (depthWidth <= 0 || depthHeight <= 0) {
            return null;
        }

        boolean[][] closerNextInfo = new boolean[depthHeight][depthWidth];

        // If we have closer next pixel data, return it
        if (lastCloserNextPixel != null) {
            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    if (y < lastCloserNextPixel.length && x < lastCloserNextPixel[y].length) {
                        closerNextInfo[y][x] = lastCloserNextPixel[y][x];
                    }
                }
            }
        }

        return closerNextInfo;
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
        Timber.d("Horizontal gradients %s", enabled ? "enabled" : "disabled");
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
                getCloserNextPixelInfo(),
                getHorizontalGradientInfo()
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
     * Computes "closer next" pixels in a depth array.
     * These are pixels where the next depth value (in the upward direction) is closer by more than the threshold,
     * indicating potential drop-offs or edges that could be hazardous for navigation.
     * Only marks pixels as "closer next" when there's a consistent tendency of pixels getting closer,
     * rather than marking individual noisy values.
     *
     * @param depthArray The depth array to process
     * @param width The width of the depth array
     * @param height The height of the depth array
     * @param consecutiveThreshold Number of consecutive pixels needed to detect a trend
     * @param closerNextThreshold Threshold for considering a pixel as "closer next" in millimeters
     * @param maxSafeDistance Maximum safe distance in millimeters
     * @return A boolean array where true indicates a "closer next" pixel
     */
    private boolean[][] computeCloserNextPixels(short[][] depthArray, int width, int height,
                                               int consecutiveThreshold, float closerNextThreshold,
                                               float maxSafeDistance) {
        // Create a boolean array to store the results
        boolean[][] closerNextPixels = new boolean[height][width];

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
                    closerNextPixels[y][x] = true;
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
                boolean isCloserNext = depthDifference > closerNextThreshold;

                if (isCloserNext) {
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
                                closerNextPixels[pixelY][x] = true;
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

        return closerNextPixels;
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
                    if (y < lastCloserNextPixel.length && x < lastCloserNextPixel[y].length &&
                        y < lastHorizontalGradientPixel.length && x < lastHorizontalGradientPixel[y].length) {
                        totalPixels++;
                        if (lastCloserNextPixel[y][x] || lastHorizontalGradientPixel[y][x]) {
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
