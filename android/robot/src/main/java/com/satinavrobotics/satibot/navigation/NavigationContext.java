package com.satinavrobotics.satibot.navigation;

import com.google.ar.core.Pose;
import com.satinavrobotics.satibot.vehicle.pd.ControllerParameters;

import org.json.JSONObject;

/**
 * Centralized context object containing all data needed for navigation decisions.
 * This eliminates the need for multiple data passing mechanisms between navigation classes.
 */
public class NavigationContext {
    
    // Navigation state enum
    public enum NavigationState {
        IDLE,           // Not navigating
        TURNING,        // Turning towards waypoint
        MOVING,         // Moving towards waypoint
        AVOIDING,       // Avoiding obstacles
        COMPLETED       // Navigation completed
    }
    
    // Sensor data
    private boolean[] navigabilityData;
    private boolean[] leftNavigabilityMap;
    private boolean[] rightNavigabilityMap;
    
    // Position and orientation data
    private Pose currentPose;
    private JSONObject targetWaypoint;
    private float targetHeading; // Target heading in radians
    
    // Navigation parameters
    private float maxLinearSpeed = 0.25f;
    private float maxAngularSpeed = 0.75f;
    private ControllerParameters parameters;
    
    // Timing data
    private long deltaTime; // Time since last update in milliseconds
    private long lastUpdateTime;
    
    // State information
    private NavigationState currentState = NavigationState.IDLE;
    private int currentWaypointIndex = 0;
    private int totalWaypointCount = 0;
    
    // Navigation thresholds
    private float rotationThresholdDegrees = 22.5f;
    private float positionThresholdMeters = 0.2f;
    
    public NavigationContext() {
        this.lastUpdateTime = System.currentTimeMillis();
        this.parameters = ControllerParameters.createDefaultRuleBased();
    }
    
    /**
     * Update timing information
     */
    public void updateTiming() {
        long currentTime = System.currentTimeMillis();
        this.deltaTime = currentTime - lastUpdateTime;
        this.lastUpdateTime = currentTime;
    }
    
    /**
     * Get delta time in seconds for controller calculations
     */
    public float getDeltaTimeSeconds() {
        return deltaTime / 1000.0f;
    }
    
    // Getters and setters
    public boolean[] getNavigabilityData() {
        return navigabilityData;
    }
    
    public void setNavigabilityData(boolean[] navigabilityData) {
        this.navigabilityData = navigabilityData;
    }
    
    public boolean[] getLeftNavigabilityMap() {
        return leftNavigabilityMap;
    }
    
    public void setLeftNavigabilityMap(boolean[] leftNavigabilityMap) {
        this.leftNavigabilityMap = leftNavigabilityMap;
    }
    
    public boolean[] getRightNavigabilityMap() {
        return rightNavigabilityMap;
    }
    
    public void setRightNavigabilityMap(boolean[] rightNavigabilityMap) {
        this.rightNavigabilityMap = rightNavigabilityMap;
    }
    
    public Pose getCurrentPose() {
        return currentPose;
    }
    
    public void setCurrentPose(Pose currentPose) {
        this.currentPose = currentPose;
    }
    
    public JSONObject getTargetWaypoint() {
        return targetWaypoint;
    }
    
    public void setTargetWaypoint(JSONObject targetWaypoint) {
        this.targetWaypoint = targetWaypoint;
    }
    
    public float getTargetHeading() {
        return targetHeading;
    }
    
    public void setTargetHeading(float targetHeading) {
        this.targetHeading = targetHeading;
    }
    
    public float getMaxLinearSpeed() {
        return maxLinearSpeed;
    }
    
    public void setMaxLinearSpeed(float maxLinearSpeed) {
        this.maxLinearSpeed = maxLinearSpeed;
    }
    
    public float getMaxAngularSpeed() {
        return maxAngularSpeed;
    }
    
    public void setMaxAngularSpeed(float maxAngularSpeed) {
        this.maxAngularSpeed = maxAngularSpeed;
    }
    
    public ControllerParameters getParameters() {
        return parameters;
    }
    
    public void setParameters(ControllerParameters parameters) {
        this.parameters = parameters;
    }
    
    public long getDeltaTime() {
        return deltaTime;
    }
    
    public NavigationState getCurrentState() {
        return currentState;
    }
    
    public void setCurrentState(NavigationState currentState) {
        this.currentState = currentState;
    }
    
    public int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }
    
    public void setCurrentWaypointIndex(int currentWaypointIndex) {
        this.currentWaypointIndex = currentWaypointIndex;
    }
    
    public int getTotalWaypointCount() {
        return totalWaypointCount;
    }
    
    public void setTotalWaypointCount(int totalWaypointCount) {
        this.totalWaypointCount = totalWaypointCount;
    }
    
    public float getRotationThresholdDegrees() {
        return rotationThresholdDegrees;
    }
    
    public void setRotationThresholdDegrees(float rotationThresholdDegrees) {
        this.rotationThresholdDegrees = rotationThresholdDegrees;
    }
    
    public float getPositionThresholdMeters() {
        return positionThresholdMeters;
    }
    
    public void setPositionThresholdMeters(float positionThresholdMeters) {
        this.positionThresholdMeters = positionThresholdMeters;
    }
}
