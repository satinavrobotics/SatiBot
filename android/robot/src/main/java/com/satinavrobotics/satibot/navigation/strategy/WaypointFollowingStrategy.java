package com.satinavrobotics.satibot.navigation.strategy;

import com.google.ar.core.Pose;
import com.satinavrobotics.satibot.navigation.ControlCommand;
import com.satinavrobotics.satibot.navigation.NavigationContext;
import com.satinavrobotics.satibot.navigation.NavigationUtils;
import com.satinavrobotics.satibot.navigation.controller.RuleBasedWaypointController;
import com.satinavrobotics.satibot.navigation.controller.WaypointController;

import org.json.JSONException;
import org.json.JSONObject;

import timber.log.Timber;

/**
 * Navigation strategy for following waypoints using turn-in-place behavior.
 * This strategy migrates functionality from the old WaypointNavigationController.
 */
public class WaypointFollowingStrategy implements NavigationStrategy {
    private static final String TAG = WaypointFollowingStrategy.class.getSimpleName();
    
    // Navigation parameters
    private static final float MAX_TURN_SPEED = 0.75f;
    private static final float MAX_MOVE_SPEED = 0.25f;
    private static final float MIN_MOVE_SPEED = 0.15f;
    
    // Controller for waypoint navigation calculations
    private WaypointController waypointController;
    
    // State tracking
    private float lastTurningError = 0.0f;
    private float lastCorrectionError = 0.0f;
    private boolean firstTurningUpdate = true;
    private boolean firstCorrectionUpdate = true;
    
    public WaypointFollowingStrategy() {
        // Initialize with default rule-based controller
        this.waypointController = new RuleBasedWaypointController(
            com.satinavrobotics.satibot.vehicle.pd.ControllerParameters.createDefaultRuleBased()
        );
    }
    
    @Override
    public ControlCommand calculateControl(NavigationContext context) {
        JSONObject waypoint = context.getTargetWaypoint();
        Pose currentPose = context.getCurrentPose();
        
        if (waypoint == null || currentPose == null) {
            Timber.w("Missing waypoint or pose data");
            return ControlCommand.stop();
        }
        
        try {
            // Calculate distance and angle to waypoint
            float waypointX = (float) waypoint.getDouble("x");
            float waypointZ = (float) waypoint.getDouble("z");
            
            float[] translation = new float[3];
            currentPose.getTranslation(translation, 0);
            float currentX = translation[0];
            float currentZ = translation[2];
            
            float deltaX = waypointX - currentX;
            float deltaZ = waypointZ - currentZ;
            float distanceToWaypoint = (float) Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            
            // Calculate angle to waypoint
            float targetAngle = (float) Math.atan2(deltaX, deltaZ);
            float[] quaternion = new float[4];
            currentPose.getRotationQuaternion(quaternion, 0);
            float currentYaw = (float) Math.atan2(2.0f * (quaternion[3] * quaternion[1] + quaternion[0] * quaternion[2]),
                                                 1.0f - 2.0f * (quaternion[1] * quaternion[1] + quaternion[2] * quaternion[2]));
            
            float angleDifference = NavigationUtils.normalizeAngle(targetAngle - currentYaw);
            
            // Check if we've reached the waypoint
            if (distanceToWaypoint < context.getPositionThresholdMeters()) {
                Timber.d("Waypoint reached (distance: %.2f m)", distanceToWaypoint);
                return ControlCommand.stop(); // Strategy will be marked as complete
            }
            
            // Process based on current state
            switch (context.getCurrentState()) {
                case TURNING:
                    return processTurning(angleDifference, context);
                case MOVING:
                    return processMoving(distanceToWaypoint, angleDifference, context);
                default:
                    return ControlCommand.stop();
            }
            
        } catch (JSONException e) {
            Timber.e(e, "Error processing waypoint coordinates");
            return ControlCommand.stop();
        }
    }
    
    @Override
    public boolean isComplete(NavigationContext context) {
        JSONObject waypoint = context.getTargetWaypoint();
        Pose currentPose = context.getCurrentPose();
        
        if (waypoint == null || currentPose == null) {
            return false;
        }
        
        try {
            float waypointX = (float) waypoint.getDouble("x");
            float waypointZ = (float) waypoint.getDouble("z");
            
            float[] translation = new float[3];
            currentPose.getTranslation(translation, 0);
            float currentX = translation[0];
            float currentZ = translation[2];
            
            float deltaX = waypointX - currentX;
            float deltaZ = waypointZ - currentZ;
            float distanceToWaypoint = (float) Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            
            return distanceToWaypoint < context.getPositionThresholdMeters();
            
        } catch (JSONException e) {
            Timber.e(e, "Error checking waypoint completion");
            return false;
        }
    }
    
    @Override
    public void reset() {
        if (waypointController != null) {
            waypointController.reset();
        }
        lastTurningError = 0.0f;
        lastCorrectionError = 0.0f;
        firstTurningUpdate = true;
        firstCorrectionUpdate = true;
        Timber.d("WaypointFollowingStrategy reset");
    }
    
    @Override
    public String getStrategyName() {
        return "Waypoint Following";
    }
    
    @Override
    public boolean canHandle(NavigationContext context) {
        // Can handle when we have a target waypoint and are in turning or moving state
        return context.getTargetWaypoint() != null && 
               (context.getCurrentState() == NavigationContext.NavigationState.TURNING ||
                context.getCurrentState() == NavigationContext.NavigationState.MOVING);
    }
    
    @Override
    public int getPriority() {
        // High priority when actively following waypoints
        return 80;
    }
    
    /**
     * Process turning towards waypoint
     */
    private ControlCommand processTurning(float headingError, NavigationContext context) {
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));
        
        // Check if we're aligned enough to start moving
        if (headingErrorDegrees <= context.getRotationThresholdDegrees()) {
            context.setCurrentState(NavigationContext.NavigationState.MOVING);
            Timber.d("Rotation complete, switching to MOVING state");
            return ControlCommand.stop(); // Brief stop before moving
        }
        
        // Calculate angular velocity using the waypoint controller
        float angularVelocity = waypointController.calculateTurningAngularVelocity(
            headingError, MAX_TURN_SPEED, context.getDeltaTimeSeconds());
        
        Timber.d("Turning (%s): heading error = %.1f degrees, angular velocity = %.2f",
                waypointController.getControllerName(), headingErrorDegrees, angularVelocity);
        
        return ControlCommand.turn(angularVelocity);
    }
    
    /**
     * Process moving towards waypoint
     */
    private ControlCommand processMoving(float distanceToWaypoint, float headingError, NavigationContext context) {
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));
        
        // Check if we need to turn again (significant heading error)
        if (headingErrorDegrees > context.getRotationThresholdDegrees() * 2.0f) {
            context.setCurrentState(NavigationContext.NavigationState.TURNING);
            Timber.d("Large heading error detected, switching back to TURNING state");
            return ControlCommand.stop(); // Brief stop before turning
        }
        
        // Calculate speed based on distance and navigability
        float linearSpeed = calculateLinearSpeed(distanceToWaypoint, context);
        
        // Apply minor course corrections while moving
        float angularVelocity = waypointController.calculateCourseCorrection(
            headingError, 0.2f, 5.0f, context.getDeltaTimeSeconds());
        
        Timber.d("Moving (%s): distance = %.2f m, linear = %.2f, angular = %.2f",
                waypointController.getControllerName(), distanceToWaypoint, linearSpeed, angularVelocity);
        
        return ControlCommand.move(linearSpeed, angularVelocity);
    }
    
    /**
     * Calculate linear speed based on distance to waypoint and navigability
     */
    private float calculateLinearSpeed(float distanceToWaypoint, NavigationContext context) {
        // Base speed calculation based on distance
        float baseSpeed;
        if (distanceToWaypoint > 1.0f) {
            baseSpeed = MAX_MOVE_SPEED;
        } else if (distanceToWaypoint > 0.5f) {
            baseSpeed = MAX_MOVE_SPEED * 0.8f;
        } else {
            baseSpeed = MIN_MOVE_SPEED;
        }
        
        // Adjust speed based on navigability (obstacle avoidance)
        boolean[] navigabilityData = context.getNavigabilityData();
        if (navigabilityData != null) {
            int navigableRows = 0;
            for (boolean isNavigable : navigabilityData) {
                if (isNavigable) {
                    navigableRows++;
                }
            }
            
            float navigabilityRatio = (float) navigableRows / navigabilityData.length;
            
            // Reduce speed if path is not clear
            if (navigabilityRatio < 0.3f) {
                baseSpeed = 0.0f; // Stop if path is mostly blocked
            } else if (navigabilityRatio < 0.6f) {
                baseSpeed *= 0.5f; // Slow down if path is partially blocked
            } else if (navigabilityRatio < 0.8f) {
                baseSpeed *= 0.7f; // Slight reduction if path has some obstacles
            }
        }
        
        return baseSpeed;
    }
    
    /**
     * Set the waypoint controller implementation
     */
    public void setWaypointController(WaypointController controller) {
        this.waypointController = controller;
        Timber.d("Waypoint controller set to: %s", controller.getControllerName());
    }
    
    /**
     * Get the current waypoint controller
     */
    public WaypointController getWaypointController() {
        return waypointController;
    }
}
