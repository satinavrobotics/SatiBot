package com.satinavrobotics.satibot.navigation;

/**
 * Utility class for navigation calculations to ensure consistency across all navigation components.
 * This class provides unified methods for angle calculations, turn direction determination,
 * and coordinate system conversions used in waypoint navigation.
 */
public class NavigationUtils {

    /**
     * Extract yaw angle from quaternion using ARCore coordinate system
     * ARCore uses: +X right, +Y up, +Z backward (camera looking down -Z)
     * Yaw is rotation around Y axis
     *
     * @param quaternion Quaternion array [x, y, z, w]
     * @return Yaw angle in radians, where 0 is facing -Z (forward), positive is clockwise when viewed from above
     */
    public static float getYawFromQuaternion(float[] quaternion) {
        float x = quaternion[0];
        float y = quaternion[1];
        float z = quaternion[2];
        float w = quaternion[3];

        // Calculate yaw from quaternion using proper ARCore coordinate system
        // This gives us rotation around Y-axis (yaw)
        float siny_cosp = 2 * (w * y + z * x);
        float cosy_cosp = 1 - 2 * (y * y + z * z);
        float yaw = (float) Math.atan2(siny_cosp, cosy_cosp);

        // Normalize to [-PI, PI] range
        return normalizeAngle(yaw);
    }

    /**
     * Calculate angle to waypoint using ARCore coordinate system
     * 
     * @param deltaX X distance to waypoint (positive = right)
     * @param deltaZ Z distance to waypoint (positive = backward, negative = forward)
     * @return Angle to waypoint in radians, where 0 is facing -Z (forward)
     */
    public static float calculateAngleToWaypoint(float deltaX, float deltaZ) {
        // In ARCore: +X is right, +Z is backward, so forward is -Z
        // atan2(-deltaZ, deltaX) gives angle from +X axis to target, counter-clockwise positive
        // We want angle from -Z axis (forward direction), so we adjust by -PI/2
        float angleToWaypoint = (float) Math.atan2(-deltaZ, deltaX) - (float)(Math.PI / 2);
        return normalizeAngle(angleToWaypoint);
    }

    /**
     * Calculate heading error for navigation
     * 
     * @param currentYaw Current robot yaw in radians
     * @param targetYaw Target yaw (angle to waypoint) in radians
     * @return Heading error in radians
     *         Positive = need to turn right
     *         Negative = need to turn left
     */
    public static float calculateHeadingError(float currentYaw, float targetYaw) {
        return normalizeAngle(currentYaw - targetYaw);
    }

    /**
     * Determine angular velocity for turning based on heading error
     * 
     * @param headingError Heading error in radians (from calculateHeadingError)
     * @param maxTurnSpeed Maximum turn speed (0.0 to 1.0)
     * @return Angular velocity (-1.0 to 1.0)
     *         Positive = turn right
     *         Negative = turn left
     */
    public static float calculateAngularVelocity(float headingError, float maxTurnSpeed) {
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));
        
        // Calculate turn speed based on angle difference
        float turnSpeed = Math.min(maxTurnSpeed, headingErrorDegrees / 90.0f * maxTurnSpeed);
        turnSpeed = Math.max(0.1f, turnSpeed); // Minimum turn speed
        
        // Determine turn direction: positive headingError means turn right, negative means turn left
        return headingError > 0 ? turnSpeed : -turnSpeed;
    }

    /**
     * Calculate course correction angular velocity for minor adjustments while moving
     * 
     * @param headingError Heading error in radians (from calculateHeadingError)
     * @param maxCorrectionStrength Maximum correction strength (0.0 to 1.0)
     * @param thresholdDegrees Minimum error threshold in degrees to apply correction
     * @return Angular velocity for course correction (-1.0 to 1.0)
     */
    public static float calculateCourseCorrection(float headingError, float maxCorrectionStrength, float thresholdDegrees) {
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));
        
        if (headingErrorDegrees > thresholdDegrees) {
            float correctionStrength = Math.min(maxCorrectionStrength, headingErrorDegrees / 45.0f * maxCorrectionStrength);
            return headingError > 0 ? correctionStrength : -correctionStrength;
        }
        
        return 0.0f;
    }

    /**
     * Get turn direction indicator for UI display
     * 
     * @param headingError Heading error in radians (from calculateHeadingError)
     * @param significantThresholdDegrees Threshold for showing large arrows
     * @param minorThresholdDegrees Threshold for showing small arrows
     * @return String indicator for turn direction
     */
    public static String getTurnDirectionIndicator(float headingError, float significantThresholdDegrees, float minorThresholdDegrees) {
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));
        
        if (headingErrorDegrees > significantThresholdDegrees) {
            // Significant error - show large arrows
            return headingError > 0 ? " ➤R" : " ⬅L";
        } else if (headingErrorDegrees > minorThresholdDegrees) {
            // Minor error - show small arrows
            return headingError > 0 ? " →" : " ←";
        } else {
            // Well aligned
            return " ✓";
        }
    }

    /**
     * Normalize angle to [-PI, PI] range
     *
     * @param angle Angle in radians
     * @return Normalized angle in radians
     */
    public static float normalizeAngle(float angle) {
        while (angle > Math.PI) {
            angle -= 2 * Math.PI;
        }
        while (angle < -Math.PI) {
            angle += 2 * Math.PI;
        }
        return angle;
    }

    /**
     * Check if robot is aligned with target within threshold
     * 
     * @param headingError Heading error in radians
     * @param thresholdDegrees Alignment threshold in degrees
     * @return true if aligned within threshold
     */
    public static boolean isAligned(float headingError, float thresholdDegrees) {
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));
        return headingErrorDegrees < thresholdDegrees;
    }
}
