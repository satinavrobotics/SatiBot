package com.satinavrobotics.satibot.utils;

/**
 * Utility class for converting between linear/angular velocity and left/right wheel speeds.
 */
public class VelocityConverter {
    
    // Wheel base width (distance between wheels) - this should be calibrated for your robot
    private static final float DEFAULT_WHEEL_BASE = 0.15f; // in meters
    private static float wheelBase = DEFAULT_WHEEL_BASE;
    
    /**
     * Set the wheel base width for the robot.
     * @param width Width between wheels in meters
     */
    public static void setWheelBase(float width) {
        wheelBase = width;
    }
    
    /**
     * Convert linear and angular velocity to left and right wheel speeds.
     * 
     * @param linear Linear velocity (forward/backward)
     * @param angular Angular velocity (rotation)
     * @return Array with [leftSpeed, rightSpeed]
     */
    public static float[] toWheelSpeeds(float linear, float angular) {
        // For a differential drive robot:
        // leftSpeed = linear - (angular * wheelBase / 2)
        // rightSpeed = linear + (angular * wheelBase / 2)
        
        float leftSpeed = linear - (angular * wheelBase / 2);
        float rightSpeed = linear + (angular * wheelBase / 2);
        
        // Normalize to range [-1, 1]
        float maxSpeed = Math.max(Math.abs(leftSpeed), Math.abs(rightSpeed));
        if (maxSpeed > 1.0f) {
            leftSpeed /= maxSpeed;
            rightSpeed /= maxSpeed;
        }
        
        return new float[] {leftSpeed, rightSpeed};
    }
    
    /**
     * Convert left and right wheel speeds to linear and angular velocity.
     * 
     * @param leftSpeed Left wheel speed
     * @param rightSpeed Right wheel speed
     * @return Array with [linearVelocity, angularVelocity]
     */
    public static float[] toVelocities(float leftSpeed, float rightSpeed) {
        // For a differential drive robot:
        // linear = (rightSpeed + leftSpeed) / 2
        // angular = (rightSpeed - leftSpeed) / wheelBase
        
        float linear = (rightSpeed + leftSpeed) / 2;
        float angular = (rightSpeed - leftSpeed) / wheelBase;
        
        return new float[] {linear, angular};
    }
}
