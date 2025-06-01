package com.satinavrobotics.satibot.navigation.controller;

/**
 * Interface for waypoint navigation control algorithms.
 * Implementations provide different strategies for calculating control commands
 * to navigate towards waypoints.
 */
public interface WaypointController {
    
    /**
     * Calculate angular velocity for turning towards a waypoint
     * 
     * @param headingError Heading error in radians (positive = need to turn right, negative = need to turn left)
     * @param maxTurnSpeed Maximum turn speed (0.0 to 1.0)
     * @param deltaTime Time since last update in seconds (for derivative calculations)
     * @return Angular velocity (-1.0 to 1.0), positive = turn right, negative = turn left
     */
    float calculateTurningAngularVelocity(float headingError, float maxTurnSpeed, float deltaTime);
    
    /**
     * Calculate angular velocity for course correction while moving towards a waypoint
     * 
     * @param headingError Heading error in radians (positive = need to turn right, negative = need to turn left)
     * @param maxCorrectionStrength Maximum correction strength (0.0 to 1.0)
     * @param thresholdDegrees Minimum error threshold in degrees to apply correction
     * @param deltaTime Time since last update in seconds (for derivative calculations)
     * @return Angular velocity for course correction (-1.0 to 1.0)
     */
    float calculateCourseCorrection(float headingError, float maxCorrectionStrength, float thresholdDegrees, float deltaTime);
    
    /**
     * Reset the controller state (e.g., clear derivative terms)
     * Should be called when starting navigation to a new waypoint
     */
    void reset();
    
    /**
     * Get the name/type of this controller for logging and UI purposes
     * 
     * @return Controller name (e.g., "Rule-Based", "PD Controller")
     */
    String getControllerName();
}
