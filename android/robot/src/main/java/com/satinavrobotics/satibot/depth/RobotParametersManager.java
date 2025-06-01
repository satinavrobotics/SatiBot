package com.satinavrobotics.satibot.depth;

import android.content.Context;
import android.graphics.PointF;

import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;

import timber.log.Timber;

/**
 * Central manager for robot parameters and camera intrinsics calculations.
 * This class ensures that robot width is stored in only one place and
 * pixel calculations are performed consistently across the application.
 */
public class RobotParametersManager {
    private static final String TAG = RobotParametersManager.class.getSimpleName();

    // Singleton instance
    private static RobotParametersManager instance;

    // SharedPreferencesManager for saving/loading parameters
    private SharedPreferencesManager sharedPreferencesManager;

    // Robot parameters
    private float robotWidthMeters = 0.4f; // Default robot width in meters

    // Navigation threshold parameters
    public static final float DEFAULT_CLOSER_NEXT_THRESHOLD = 20.0f; // Default threshold in mm
    public static final float DEFAULT_MAX_SAFE_DISTANCE = 5000.0f; // Default 3m in mm
    public static final int DEFAULT_CONSECUTIVE_THRESHOLD = 3; // Default consecutive pixels threshold
    public static final int DEFAULT_DOWNSAMPLE_FACTOR = 8; // Default downsample factor
    public static final float DEFAULT_DEPTH_GRADIENT_THRESHOLD = 200.0f; // Default horizontal gradient threshold in mm
    public static final int DEFAULT_NAVIGABILITY_THRESHOLD = 3; // Default navigability threshold (5% obstacles)
    public static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.5f; // Default confidence threshold (0.0-1.0)

    private float closerNextThreshold = DEFAULT_CLOSER_NEXT_THRESHOLD;
    private float maxSafeDistance = DEFAULT_MAX_SAFE_DISTANCE;
    private int consecutiveThreshold = DEFAULT_CONSECUTIVE_THRESHOLD;
    private int downsampleFactor = DEFAULT_DOWNSAMPLE_FACTOR;
    private float depthGradientThreshold = DEFAULT_DEPTH_GRADIENT_THRESHOLD;
    private int navigabilityThreshold = DEFAULT_NAVIGABILITY_THRESHOLD;
    private float confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;

    // Camera intrinsics
    private CameraIntrinsics cameraIntrinsics;

    // Frame dimensions
    private static final int DEFAULT_FRAME_WIDTH = 640;
    private static final int DEFAULT_FRAME_HEIGHT = 480;
    private int frameWidth = DEFAULT_FRAME_WIDTH;
    private int frameHeight = DEFAULT_FRAME_HEIGHT;

    // Default distance from camera to robot bounds (in meters)
    private static final float DEFAULT_DISTANCE_METERS = 0.5f;

    // Default field of view (in degrees) - used as fallback when camera intrinsics are not available
    private static final float DEFAULT_FOV_DEGREES = 60.0f;

    /**
     * Private constructor to enforce singleton pattern
     */
    private RobotParametersManager() {
        // Initialize with default values
    }

    /**
     * Get the singleton instance of RobotParametersManager
     *
     * @return The singleton instance
     */
    public static synchronized RobotParametersManager getInstance() {
        if (instance == null) {
            instance = new RobotParametersManager();
        }
        return instance;
    }

    /**
     * Initialize the RobotParametersManager with a context for SharedPreferences access
     * and load saved parameters.
     *
     * @param context Application context
     */
    public void initialize(Context context) {
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = new SharedPreferencesManager(context);
            loadParametersFromPreferences();
        }
    }

    /**
     * Load all parameters from SharedPreferences
     */
    public void loadParametersFromPreferences() {
        if (sharedPreferencesManager == null) {
            Timber.w("Cannot load parameters: SharedPreferencesManager not initialized");
            return;
        }

        // Load all parameters from SharedPreferences
        robotWidthMeters = sharedPreferencesManager.getRobotWidthMeters();
        closerNextThreshold = sharedPreferencesManager.getCloserNextThreshold();
        maxSafeDistance = sharedPreferencesManager.getMaxSafeDistance();
        consecutiveThreshold = sharedPreferencesManager.getConsecutiveThreshold();
        downsampleFactor = sharedPreferencesManager.getDownsampleFactor();
        depthGradientThreshold = sharedPreferencesManager.getDepthGradientThreshold();
        navigabilityThreshold = sharedPreferencesManager.getNavigabilityThreshold();
        confidenceThreshold = sharedPreferencesManager.getConfidenceThreshold();

        Timber.d("Loaded parameters from SharedPreferences: " +
                "robotWidth=%.2fm, closerNext=%.1fmm, maxSafe=%.1fmm, consecutive=%d, " +
                "downsample=%d, depthGradient=%.1fmm, navigability=%d%%",
                robotWidthMeters, closerNextThreshold, maxSafeDistance, consecutiveThreshold,
                downsampleFactor, depthGradientThreshold, navigabilityThreshold);
    }

    /**
     * Save all parameters to SharedPreferences
     */
    public void saveParametersToPreferences() {
        if (sharedPreferencesManager == null) {
            Timber.w("Cannot save parameters: SharedPreferencesManager not initialized");
            return;
        }

        // Save all parameters to SharedPreferences
        sharedPreferencesManager.setRobotWidthMeters(robotWidthMeters);
        sharedPreferencesManager.setCloserNextThreshold(closerNextThreshold);
        sharedPreferencesManager.setMaxSafeDistance(maxSafeDistance);
        sharedPreferencesManager.setConsecutiveThreshold(consecutiveThreshold);
        sharedPreferencesManager.setDownsampleFactor(downsampleFactor);
        sharedPreferencesManager.setDepthGradientThreshold(depthGradientThreshold);
        sharedPreferencesManager.setNavigabilityThreshold(navigabilityThreshold);
        sharedPreferencesManager.setConfidenceThreshold(confidenceThreshold);

        Timber.d("Saved parameters to SharedPreferences");
    }

    /**
     * Set the robot width in meters
     *
     * @param widthMeters Robot width in meters
     */
    public void setRobotWidthMeters(float widthMeters) {
        // Ensure the input is a valid float value
        if (Float.isNaN(widthMeters) || Float.isInfinite(widthMeters)) {
            Timber.w("Invalid robot width value: %s, using default", widthMeters);
            widthMeters = 0.4f; // Use default value if invalid
        }

        // Ensure minimum width and store the value
        this.robotWidthMeters = Math.max(0.1f, widthMeters);

        // Log the change with more detail
        Timber.d("Set robot width to %.2f meters (input value was %.2f)",
                this.robotWidthMeters, widthMeters);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setRobotWidthMeters(this.robotWidthMeters);
        }
    }

    /**
     * Get the robot width in meters
     *
     * @return Robot width in meters
     */
    public float getRobotWidthMeters() {
        return robotWidthMeters;
    }

    /**
     * Set the camera intrinsics for accurate robot bounds calculation
     *
     * @param cameraIntrinsics Camera intrinsics from ARCore
     */
    public void setCameraIntrinsics(CameraIntrinsics cameraIntrinsics) {
        this.cameraIntrinsics = cameraIntrinsics;
    }

    /**
     * Get the camera intrinsics
     *
     * @return Camera intrinsics or null if not set
     */
    public CameraIntrinsics getCameraIntrinsics() {
        return cameraIntrinsics;
    }

    /**
     * Calculate robot bounds as relative values (0.0-1.0) using camera intrinsics if available,
     * or fallback to approximation using default field of view.
     * Uses the stored frame width from the most recent frame.
     *
     * @return An array containing [leftRatio, rightRatio] as relative values (0.0-1.0)
     */
    public float[] calculateRobotBoundsRelative() {
        float centerRatio = 0.5f; // Center of the frame
        float leftRatio, rightRatio;

        // Validate robot width before calculation
        if (Float.isNaN(robotWidthMeters) || Float.isInfinite(robotWidthMeters) || robotWidthMeters <= 0) {
            Timber.w("Invalid robot width for bounds calculation: %s, using default", robotWidthMeters);
            robotWidthMeters = 0.4f; // Use default value if invalid
        }

        // Validate frame width
        if (frameWidth <= 0) {
            Timber.w("Invalid frame width: %d, using default", frameWidth);
            frameWidth = DEFAULT_FRAME_WIDTH;
        }

        // Calculate robot width using camera intrinsics if available
        if (cameraIntrinsics != null) {
            // Use camera intrinsics for accurate calculation
            PointF fl = cameraIntrinsics.getFocalLength();

            // Validate focal length
            if (fl == null || fl.x <= 0 || fl.y <= 0) {
                Timber.w("Invalid focal length from camera intrinsics, falling back to approximation");
                // Fall back to approximation
                float fovRadians = (float) Math.toRadians(DEFAULT_FOV_DEGREES);
                float focalLengthPixels = frameWidth / (2 * (float) Math.tan(fovRadians / 2));
                float robotWidthPixels = (robotWidthMeters * focalLengthPixels) / DEFAULT_DISTANCE_METERS;
                float robotWidthRatio = robotWidthPixels / frameWidth;

                leftRatio = centerRatio - (robotWidthRatio / 2);
                rightRatio = centerRatio + (robotWidthRatio / 2);

                Timber.d("Using approximation for robot bounds: fov=%.0f°, width=%.2fm, ratio=%.3f, frameWidth=%d",
                        DEFAULT_FOV_DEGREES, robotWidthMeters, robotWidthRatio, frameWidth);
            } else {
                // Use camera intrinsics for calculation
                float focalLengthPixels = (fl.x + fl.y) / 2;
                float robotWidthPixels = (robotWidthMeters * focalLengthPixels) / DEFAULT_DISTANCE_METERS;
                float robotWidthRatio = robotWidthPixels / frameWidth;

                leftRatio = centerRatio - (robotWidthRatio / 2);
                rightRatio = centerRatio + (robotWidthRatio / 2);

                Timber.d("Using camera intrinsics for robot bounds: fx=%.2f, width=%.2fm, ratio=%.3f, frameWidth=%d",
                        focalLengthPixels, robotWidthMeters, robotWidthRatio, frameWidth);
            }
        } else {
            // Fallback to approximation if intrinsics not available
            float fovRadians = (float) Math.toRadians(DEFAULT_FOV_DEGREES);
            float focalLengthPixels = frameWidth / (2 * (float) Math.tan(fovRadians / 2));
            float robotWidthPixels = (robotWidthMeters * focalLengthPixels) / DEFAULT_DISTANCE_METERS;
            float robotWidthRatio = robotWidthPixels / frameWidth;

            leftRatio = centerRatio - (robotWidthRatio / 2);
            rightRatio = centerRatio + (robotWidthRatio / 2);

            Timber.d("Using approximation for robot bounds: fov=%.0f°, width=%.2fm, ratio=%.3f, frameWidth=%d",
                    DEFAULT_FOV_DEGREES, robotWidthMeters, robotWidthRatio, frameWidth);
        }

        // Clamp values to ensure they're within 0.0-1.0 range
        leftRatio = Math.max(0.0f, Math.min(1.0f, leftRatio));
        rightRatio = Math.max(0.0f, Math.min(1.0f, rightRatio));

        // Ensure minimum width for visibility
        float minWidth = 0.1f; // Minimum 10% of screen width
        if (rightRatio - leftRatio < minWidth) {
            float currentWidth = rightRatio - leftRatio;
            float additionalWidth = minWidth - currentWidth;
            leftRatio = Math.max(0.0f, leftRatio - (additionalWidth / 2));
            rightRatio = Math.min(1.0f, rightRatio + (additionalWidth / 2));

            Timber.d("Adjusted robot bounds to ensure minimum width: left=%.3f, right=%.3f",
                    leftRatio, rightRatio);
        }

        return new float[] { leftRatio, rightRatio };
    }


    /**
     * Get the closer next threshold in millimeters.
     * This threshold determines when a pixel is considered to be "closer next"
     * based on the difference in depth between consecutive pixels.
     *
     * @return Closer next threshold in millimeters
     */
    public float getCloserNextThreshold() {
        return closerNextThreshold;
    }

    /**
     * Set the closer next threshold in millimeters.
     *
     * @param thresholdMm Threshold in millimeters
     */
    public void setCloserNextThreshold(float thresholdMm) {
        this.closerNextThreshold = Math.max(1.0f, thresholdMm); // Ensure minimum threshold
        Timber.d("Set closer next threshold to %.2f mm", this.closerNextThreshold);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setCloserNextThreshold(this.closerNextThreshold);
        }
    }

    /**
     * Get the maximum safe distance in millimeters.
     * This is the maximum distance at which obstacles are considered for navigation.
     *
     * @return Maximum safe distance in millimeters
     */
    public float getMaxSafeDistance() {
        return maxSafeDistance;
    }

    /**
     * Set the maximum safe distance in millimeters.
     *
     * @param distanceMm Maximum safe distance in millimeters
     */
    public void setMaxSafeDistance(float distanceMm) {
        this.maxSafeDistance = Math.max(100.0f, distanceMm); // Ensure minimum distance (10cm)
        Timber.d("Set maximum safe distance to %.2f mm", this.maxSafeDistance);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setMaxSafeDistance(this.maxSafeDistance);
        }
    }

    /**
     * Get the consecutive threshold in pixels.
     * This is the number of consecutive pixels that need to show a closer trend to be considered valid.
     *
     * @return Consecutive threshold in pixels
     */
    public int getConsecutiveThreshold() {
        return consecutiveThreshold;
    }

    /**
     * Set the consecutive threshold in pixels.
     *
     * @param threshold Number of consecutive pixels needed to detect a trend
     */
    public void setConsecutiveThreshold(int threshold) {
        this.consecutiveThreshold = Math.max(1, threshold); // Ensure minimum threshold
        Timber.d("Set consecutive threshold to %d pixels", this.consecutiveThreshold);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setConsecutiveThreshold(this.consecutiveThreshold);
        }
    }

    /**
     * Get the downsample factor.
     * This is the factor by which the depth image is downsampled for processing.
     *
     * @return Downsample factor
     */
    public int getDownsampleFactor() {
        return downsampleFactor;
    }

    /**
     * Set the downsample factor.
     *
     * @param factor Factor by which to downsample the depth image (1 = no downsampling)
     */
    public void setDownsampleFactor(int factor) {
        this.downsampleFactor = Math.max(1, factor); // Ensure minimum factor
        Timber.d("Set downsample factor to %d", this.downsampleFactor);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setDownsampleFactor(this.downsampleFactor);
        }
    }

    /**
     * Get the depth gradient threshold in millimeters.
     * This threshold determines when a horizontal gradient is considered significant.
     *
     * @return Depth gradient threshold in millimeters
     */
    public float getDepthGradientThreshold() {
        return depthGradientThreshold;
    }

    /**
     * Set the depth gradient threshold in millimeters.
     *
     * @param thresholdMm Threshold for depth discontinuity in millimeters
     */
    public void setDepthGradientThreshold(float thresholdMm) {
        this.depthGradientThreshold = Math.max(1.0f, thresholdMm); // Ensure minimum threshold
        Timber.d("Set depth gradient threshold to %.2f mm", this.depthGradientThreshold);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setDepthGradientThreshold(this.depthGradientThreshold);
        }
    }

    /**
     * Get the navigability threshold.
     * This is the percentage of obstacles that make a row non-navigable.
     *
     * @return Navigability threshold (percentage)
     */
    public int getNavigabilityThreshold() {
        return navigabilityThreshold;
    }

    /**
     * Set the navigability threshold.
     *
     * @param threshold Percentage of obstacles that make a row non-navigable (0-100)
     */
    public void setNavigabilityThreshold(int threshold) {
        this.navigabilityThreshold = Math.max(1, threshold); // Ensure minimum threshold
        Timber.d("Set navigability threshold to %d%% obstacles", this.navigabilityThreshold);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setNavigabilityThreshold(this.navigabilityThreshold);
        }
    }

    /**
     * Get the current frame width.
     *
     * @return Current frame width in pixels
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    /**
     * Get the current frame height.
     *
     * @return Current frame height in pixels
     */
    public int getFrameHeight() {
        return frameHeight;
    }

    /**
     * Update the frame dimensions.
     * This should be called whenever a new frame is received with different dimensions.
     *
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     */
    public void updateFrameDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            Timber.w("Invalid frame dimensions: %d x %d, using defaults", width, height);
            return;
        }

        if (width != frameWidth || height != frameHeight) {
            Timber.d("Updating frame dimensions from %d x %d to %d x %d",
                    frameWidth, frameHeight, width, height);
            frameWidth = width;
            frameHeight = height;
        }
    }

    /**
     * Get the confidence threshold for depth processing.
     * This threshold determines which depth values are considered valid based on their confidence.
     *
     * @return Confidence threshold (0.0-1.0)
     */
    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }

    /**
     * Set the confidence threshold for depth processing.
     *
     * @param threshold Confidence threshold (0.0-1.0)
     */
    public void setConfidenceThreshold(float threshold) {
        // Clamp to valid range
        this.confidenceThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
        Timber.d("Set confidence threshold to %.2f", this.confidenceThreshold);

        // Save to SharedPreferences
        if (sharedPreferencesManager != null) {
            sharedPreferencesManager.setConfidenceThreshold(this.confidenceThreshold);
        }
    }
}
