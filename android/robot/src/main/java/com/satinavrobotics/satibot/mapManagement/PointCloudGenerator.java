package com.satinavrobotics.satibot.mapManagement;

import android.media.Image;
import android.util.Log;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import android.opengl.Matrix;

public class PointCloudGenerator {
    private static final String TAG = PointCloudGenerator.class.getSimpleName();

    // Default parameters for outlier detection
    private static final int DEFAULT_K_NEIGHBORS = 10;
    private static final float DEFAULT_STDDEV_MULT = 0.1f;

    // Default parameters for median filtering
    private static final int DEFAULT_MEDIAN_KERNEL_SIZE = 7;

    // Asynchronous outlier detector
    private static AsyncOutlierDetector asyncOutlierDetector;
    private static final AtomicBoolean outlierDetectionEnabled = new AtomicBoolean(true);
    private static final AtomicBoolean medianFilterEnabled = new AtomicBoolean(true);

    /**
     * Generates a world-space point cloud from the ARCore frame's 16-bit depth image.
     *
     * @param frame The ARCore frame containing depth data
     * @return List of 3D points in world space with color information
     * @throws NotYetAvailableException If depth data is not available
     */
    public static List<float[]> generatePointCloud(Frame frame) throws NotYetAvailableException {
        return generatePointCloud(frame, 0.5f, 1);
    }

    /**
     * Generates a world-space point cloud from the ARCore frame's 16-bit depth image
     * with confidence threshold filtering and subsampling.
     *
     * @param frame The ARCore frame containing depth data
     * @param confidenceThreshold Threshold for confidence values (0.0-1.0)
     * @param subsampleFactor Factor by which to subsample the depth image (1 = no subsampling, 2 = half resolution, etc.)
     * @return List of 3D points in world space with color information
     * @throws NotYetAvailableException If depth data is not available
     */
    public static List<float[]> generatePointCloud(Frame frame, float confidenceThreshold, int subsampleFactor)
            throws NotYetAvailableException {
        return generateColoredPointCloud(frame, confidenceThreshold, subsampleFactor, true);
    }

    /**
     * Generates a world-space point cloud from the ARCore frame's 16-bit depth image
     * with confidence threshold filtering, subsampling, and optional color information.
     *
     * @param frame The ARCore frame containing depth data
     * @param confidenceThreshold Threshold for confidence values (0.0-1.0)
     * @param subsampleFactor Factor by which to subsample the depth image (1 = no subsampling, 2 = half resolution, etc.)
     * @param includeColor Whether to include color information from the camera image
     * @return List of 3D points in world space with color information (if requested)
     * @throws NotYetAvailableException If depth data is not available
     */
    public static List<float[]> generateColoredPointCloud(Frame frame, float confidenceThreshold,
                                                         int subsampleFactor, boolean includeColor)
            throws NotYetAvailableException {
        return generateColoredPointCloud(frame, confidenceThreshold, subsampleFactor, includeColor, true);
    }

    /**
     * Generates a world-space point cloud from the ARCore frame's 16-bit depth image
     * with confidence threshold filtering, subsampling, optional color information,
     * and timestamp verification.
     *
     * @param frame The ARCore frame containing depth data
     * @param confidenceThreshold Threshold for confidence values (0.0-1.0)
     * @param subsampleFactor Factor by which to subsample the depth image (1 = no subsampling, 2 = half resolution, etc.)
     * @param includeColor Whether to include color information from the camera image
     * @param checkTimestamps Whether to verify that depth and camera image timestamps match
     * @return List of 3D points in world space with color information (if requested)
     * @throws NotYetAvailableException If depth data is not available
     */
    public static List<float[]> generateColoredPointCloud(Frame frame, float confidenceThreshold,
                                                         int subsampleFactor, boolean includeColor,
                                                         boolean checkTimestamps)
            throws NotYetAvailableException {
        List<float[]> pointCloud = new ArrayList<>();

        if (subsampleFactor < 1) {
            subsampleFactor = 1; // Ensure minimum of 1 (no subsampling)
        }

        // --- Acquire the 16-bit depth image (millimeters) ---
        Image depthImage = frame.acquireRawDepthImage16Bits();
        if (depthImage == null) {
            Log.w(TAG, "No depth image available");
            return pointCloud;
        }

        long depthTimestamp = depthImage.getTimestamp();

        // --- Acquire the confidence image (0-255) ---
        Image confidenceImage = null;
        byte[] confidenceArray = null;
        try {
            confidenceImage = frame.acquireRawDepthConfidenceImage();
            if (confidenceImage != null) {
                ByteBuffer confidenceBuf = confidenceImage.getPlanes()[0].getBuffer();
                confidenceArray = new byte[confidenceBuf.remaining()];
                confidenceBuf.get(confidenceArray);
            }
        } catch (Exception e) {
            Log.w(TAG, "Confidence image not available: " + e.getMessage());
        }

        boolean hasConfidence = (confidenceImage != null && confidenceArray != null);

        // --- Acquire the camera image for color information ---
        Image cameraImage = null;
        byte[] yuvY = null;
        byte[] yuvU = null;
        byte[] yuvV = null;
        int yRowStride = 0;
        int uvRowStride = 0;
        int uvPixelStride = 0;
        int colorWidth = 0;
        int colorHeight = 0;

        if (includeColor) {
            try {
                cameraImage = frame.acquireCameraImage();
                if (cameraImage != null) {
                    // Check if timestamps match (if required)
                    long cameraTimestamp = cameraImage.getTimestamp();
                    if (checkTimestamps) {
                        // Allow a small tolerance (5ms) for timestamp differences
                        //long timestampDiffMs = Math.abs(depthTimestamp - cameraTimestamp) / 1000000;
                        //if (timestampDiffMs > 20) {
                        //    Log.w(TAG, "Depth and camera timestamps don't match: " +
                        //          "depth=" + depthTimestamp + ", camera=" + cameraTimestamp +
                        //          " (diff=" + timestampDiffMs + "ms)");

                            // Clean up resources
                        //    depthImage.close();
                        //    if (confidenceImage != null) confidenceImage.close();
                        //    cameraImage.close();

                        //    return pointCloud; // Return empty point cloud
                        //}
                    }

                    colorWidth = cameraImage.getWidth();
                    colorHeight = cameraImage.getHeight();
                    Image.Plane yPlane = cameraImage.getPlanes()[0];
                    Image.Plane uPlane = cameraImage.getPlanes()[1];
                    Image.Plane vPlane = cameraImage.getPlanes()[2];
                    yuvY = new byte[yPlane.getBuffer().remaining()];
                    yuvU = new byte[uPlane.getBuffer().remaining()];
                    yuvV = new byte[vPlane.getBuffer().remaining()];
                    yPlane.getBuffer().get(yuvY);
                    uPlane.getBuffer().get(yuvU);
                    vPlane.getBuffer().get(yuvV);
                    yRowStride = yPlane.getRowStride();
                    uvRowStride = uPlane.getRowStride();
                    uvPixelStride = uPlane.getPixelStride();
                }
            } catch (Exception e) {
                Log.w(TAG, "Camera image not available: " + e.getMessage());
                includeColor = false;
            }
        }

        boolean hasColor = includeColor && cameraImage != null && yuvY != null && yuvU != null && yuvV != null;

        Camera camera = frame.getCamera();
        CameraIntrinsics intrinsics = camera.getImageIntrinsics();
        Pose cameraPose = camera.getPose();

        // Original camera image dimensions (for intrinsics scaling)
        int imageWidth  = intrinsics.getImageDimensions()[0];
        int imageHeight = intrinsics.getImageDimensions()[1];
        // Depth image dimensions
        int depthWidth  = depthImage.getWidth();
        int depthHeight = depthImage.getHeight();

        // Scale intrinsics to match depth resolution
        float fx = intrinsics.getFocalLength()[0] * depthWidth  / (float) imageWidth;
        float fy = intrinsics.getFocalLength()[1] * depthHeight / (float) imageHeight;
        float cx = intrinsics.getPrincipalPoint()[0] * depthWidth  / (float) imageWidth;
        float cy = intrinsics.getPrincipalPoint()[1] * depthHeight / (float) imageHeight;

        // Get the depth buffer (each entry is a 16-bit millimeter value)
        ShortBuffer depthBuf = depthImage.getPlanes()[0].getBuffer().asShortBuffer();
        int rowStride = depthImage.getPlanes()[0].getRowStride() / 2;  // in shorts
        int confidenceRowStride = hasConfidence ? confidenceImage.getPlanes()[0].getRowStride() : 0;

        // Apply median filtering to the depth buffer if enabled
        if (medianFilterEnabled.get()) {
            long startTime = System.currentTimeMillis();

            // Apply median filter
            short[] filteredDepth = DepthImageFilter.applyMedianFilter(
                    depthBuf, depthWidth, depthHeight, rowStride, DEFAULT_MEDIAN_KERNEL_SIZE);

            if (filteredDepth != null) {
                // Replace the original depth buffer with the filtered one
                depthBuf = DepthImageFilter.createShortBuffer(filteredDepth);

                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Applied median filtering to depth image in " + duration + "ms");
            }
        }

        // Camera-to-world transform (column-major OpenGL)
        float[] cameraToWorld = new float[16];
        cameraPose.toMatrix(cameraToWorld, 0);

        // Loop through depth pixels with subsampling
        for (int y = 0; y < depthHeight; y += subsampleFactor) {
            for (int x = 0; x < depthWidth; x += subsampleFactor) {
                int idx = y * rowStride + x;

                // Skip if out of bounds
                if (idx >= depthBuf.capacity()) {
                    continue;
                }

                short depthSample = depthBuf.get(idx);
                if (depthSample == 0) {
                    // invalid or no depth
                    continue;
                }

                // Apply confidence threshold if available
                float confidence = 1.0f;
                if (hasConfidence) {
                    int confidenceIdx = y * confidenceRowStride + x;
                    if (confidenceIdx >= confidenceArray.length) {
                        continue;
                    }

                    confidence = (confidenceArray[confidenceIdx] & 0xFF) / 255.0f;
                    if (confidence < confidenceThreshold) {
                        // Skip low confidence points
                        continue;
                    }
                }

                float dMeters = depthSample * 0.001f;  // mm → m

                // Convert to camera-space (ARCore: +X right, +Y up, –Z forward)
                float Xc = (x - cx) * dMeters / fx;
                float Yc = (cy - y) * dMeters / fy;     // flip Y
                float Zc = -dMeters;                     // forward is –Z
                float[] camPt = { Xc, Yc, Zc, 1f };

                // Transform to world-space
                float[] worldPt = new float[4];
                Matrix.multiplyMV(worldPt, 0, cameraToWorld, 0, camPt, 0);

                // Get color from camera image if available
                float r = 1.0f, g = 1.0f, b = 1.0f;  // Default white

                if (hasColor) {
                    // Map depth pixel to color pixel
                    int colorX = x * colorWidth / depthWidth;
                    int colorY = y * colorHeight / depthHeight;

                    // Ensure we're within bounds
                    if (colorX < colorWidth && colorY < colorHeight) {
                        int yIdx = colorY * yRowStride + colorX;
                        int uvIdx = (colorY / 2) * uvRowStride + (colorX / 2) * uvPixelStride;

                        // Ensure indices are within bounds
                        if (yIdx < yuvY.length && uvIdx < yuvU.length && uvIdx < yuvV.length) {
                            byte yVal = yuvY[yIdx];
                            byte uVal = yuvU[uvIdx];
                            byte vVal = yuvV[uvIdx];

                            // Convert YUV to RGB
                            float[] rgb = yuvToRgb(yVal, uVal, vVal);
                            r = rgb[0];
                            g = rgb[1];
                            b = rgb[2];
                        }
                    }
                }

                // Add point with position, color, and confidence
                pointCloud.add(new float[]{
                        worldPt[0],  // x
                        worldPt[1],  // y
                        worldPt[2],  // z
                        r,           // red
                        g,           // green
                        b,           // blue
                        confidence   // confidence
                });
            }
        }

        // Clean up resources
        depthImage.close();
        if (confidenceImage != null) {
            confidenceImage.close();
        }
        if (cameraImage != null) {
            cameraImage.close();
        }

        Log.d(TAG, "Generated point cloud with " + pointCloud.size() + " points (subsample=" +
              subsampleFactor + ", threshold=" + confidenceThreshold + ", colored=" + hasColor + ")");

        return pointCloud;
    }

    /**
     * Converts YUV color values to RGB (range 0..1)
     *
     * @param y Y component (0-255)
     * @param u U component (0-255)
     * @param v V component (0-255)
     * @return float array with [r, g, b] values in range 0.0-1.0
     */
    private static float[] yuvToRgb(byte y, byte u, byte v) {
        float yf = (y & 0xFF) / 255.0f;
        float uf = ((u & 0xFF) - 128) / 255.0f;
        float vf = ((v & 0xFF) - 128) / 255.0f;
        float r = yf + 1.402f * vf;
        float g = yf - 0.344136f * uf - 0.714136f * vf;
        float b = yf + 1.772f * uf;
        return new float[]{clamp(r), clamp(g), clamp(b)};
    }

    /**
     * Clamps a value to the range 0.0-1.0
     */
    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Initializes the asynchronous outlier detector.
     * This should be called before using outlier detection.
     */
    public static void initOutlierDetector() {
        if (asyncOutlierDetector == null) {
            asyncOutlierDetector = new AsyncOutlierDetector();
            Log.d(TAG, "Initialized asynchronous outlier detector");
        }
    }

    /**
     * Shuts down the asynchronous outlier detector.
     * This should be called when the outlier detector is no longer needed.
     */
    public static void shutdownOutlierDetector() {
        if (asyncOutlierDetector != null) {
            asyncOutlierDetector.shutdown();
            asyncOutlierDetector = null;
            Log.d(TAG, "Shut down asynchronous outlier detector");
        }
    }

    /**
     * Enables or disables outlier detection.
     * @param enabled Whether outlier detection should be applied
     */
    public static void setOutlierDetectionEnabled(boolean enabled) {
        outlierDetectionEnabled.set(enabled);
        Log.d(TAG, "Outlier detection " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Checks if outlier detection is enabled.
     * @return true if enabled, false otherwise
     */
    public static boolean isOutlierDetectionEnabled() {
        return outlierDetectionEnabled.get();
    }

    /**
     * Enables or disables median filtering of depth images.
     * @param enabled Whether median filtering should be applied
     */
    public static void setMedianFilterEnabled(boolean enabled) {
        medianFilterEnabled.set(enabled);
        Log.d(TAG, "Median filtering " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Checks if median filtering is enabled.
     * @return true if enabled, false otherwise
     */
    public static boolean isMedianFilterEnabled() {
        return medianFilterEnabled.get();
    }

    /**
     * Asynchronously detects and removes outliers from a point cloud.
     *
     * @param points The input point cloud to filter
     * @param callback Callback to receive the filtered point cloud
     */
    public static void detectOutliersAsync(List<float[]> points, AsyncOutlierDetector.DetectionCallback callback) {
        // Initialize detector if needed
        if (asyncOutlierDetector == null) {
            initOutlierDetector();
        }

        // Skip if disabled or detector is busy
        if (!outlierDetectionEnabled.get() || asyncOutlierDetector.isProcessing()) {
            if (callback != null) {
                callback.onDetectionComplete(points);
            }
            return;
        }

        // Perform async detection
        asyncOutlierDetector.detectAsync(points, DEFAULT_K_NEIGHBORS, DEFAULT_STDDEV_MULT, callback);
    }
}
