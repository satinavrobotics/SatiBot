package com.satinavrobotics.satibot.navigation.strategy;

import com.google.ar.core.Pose;
import com.satinavrobotics.satibot.navigation.ControlCommand;
import com.satinavrobotics.satibot.navigation.NavigationContext;
import com.satinavrobotics.satibot.navigation.NavigationUtils;

import org.json.JSONException;
import org.json.JSONObject;

import timber.log.Timber;

/**
 * Combined navigation strategy that integrates waypoint following with obstacle avoidance.
 * This strategy provides the highest priority and most sophisticated navigation behavior.
 */
public class CombinedNavigationStrategy implements NavigationStrategy {
    private static final String TAG = CombinedNavigationStrategy.class.getSimpleName();
    
    // Delegate strategies
    private final WaypointFollowingStrategy waypointStrategy;
    private final ObstacleAvoidanceStrategy obstacleStrategy;
    
    // Navigation parameters
    private static final float OBSTACLE_DETECTION_THRESHOLD = 0.3f; // Threshold for switching to obstacle avoidance
    
    public CombinedNavigationStrategy() {
        this.waypointStrategy = new WaypointFollowingStrategy();
        this.obstacleStrategy = new ObstacleAvoidanceStrategy();
        
        // Configure obstacle avoidance for integration with waypoint following
        obstacleStrategy.setTraversabilityCostWeight(3.0f);
        obstacleStrategy.setHeadingDeviationCostWeight(1.0f);
        obstacleStrategy.setCostBasedNavigationEnabled(true);
    }
    
    @Override
    public ControlCommand calculateControl(NavigationContext context) {
        // First check if we need obstacle avoidance
        if (needsObstacleAvoidance(context)) {
            // Update target heading for obstacle avoidance based on waypoint direction
            updateTargetHeadingFromWaypoint(context);
            
            // Use obstacle avoidance with waypoint-informed heading
            ControlCommand obstacleCommand = obstacleStrategy.calculateControl(context);
            Timber.d("CombinedStrategy: Using obstacle avoidance - %s", obstacleCommand);
            return obstacleCommand;
        } else {
            // Use waypoint following when path is clear
            ControlCommand waypointCommand = waypointStrategy.calculateControl(context);
            Timber.d("CombinedStrategy: Using waypoint following - %s", waypointCommand);
            return waypointCommand;
        }
    }
    
    @Override
    public boolean isComplete(NavigationContext context) {
        // Complete when waypoint following is complete
        return waypointStrategy.isComplete(context);
    }
    
    @Override
    public void reset() {
        waypointStrategy.reset();
        obstacleStrategy.reset();
        Timber.d("CombinedNavigationStrategy reset");
    }
    
    @Override
    public String getStrategyName() {
        return "Combined Waypoint + Obstacle Avoidance";
    }
    
    @Override
    public boolean canHandle(NavigationContext context) {
        // Can handle when waypoint following can handle (has waypoint and appropriate state)
        return waypointStrategy.canHandle(context);
    }
    
    @Override
    public int getPriority() {
        // Highest priority - this is the most sophisticated strategy
        return 100;
    }
    
    /**
     * Determine if obstacle avoidance is needed based on navigability data
     */
    private boolean needsObstacleAvoidance(NavigationContext context) {
        boolean[] navigabilityData = context.getNavigabilityData();
        
        if (navigabilityData == null) {
            return false; // No data, assume clear
        }
        
        // Count navigable rows
        int navigableRows = 0;
        for (boolean isNavigable : navigabilityData) {
            if (isNavigable) {
                navigableRows++;
            }
        }
        
        float navigabilityRatio = (float) navigableRows / navigabilityData.length;
        
        // Switch to obstacle avoidance if path is significantly blocked
        boolean needsAvoidance = navigabilityRatio < OBSTACLE_DETECTION_THRESHOLD;
        
        if (needsAvoidance) {
            Timber.d("Obstacle detected: navigability ratio %.2f < threshold %.2f", 
                    navigabilityRatio, OBSTACLE_DETECTION_THRESHOLD);
        }
        
        return needsAvoidance;
    }
    
    /**
     * Update the target heading in the context based on the current waypoint direction
     */
    private void updateTargetHeadingFromWaypoint(NavigationContext context) {
        JSONObject waypoint = context.getTargetWaypoint();
        Pose currentPose = context.getCurrentPose();
        
        if (waypoint == null || currentPose == null) {
            context.setTargetHeading(0.0f); // Default to forward
            return;
        }
        
        try {
            // Calculate direction to waypoint
            float waypointX = (float) waypoint.getDouble("x");
            float waypointZ = (float) waypoint.getDouble("z");
            
            float[] translation = new float[3];
            currentPose.getTranslation(translation, 0);
            float currentX = translation[0];
            float currentZ = translation[2];
            
            float deltaX = waypointX - currentX;
            float deltaZ = waypointZ - currentZ;
            
            // Calculate angle to waypoint
            float targetAngle = (float) Math.atan2(deltaX, deltaZ);
            
            // Get current yaw
            float[] quaternion = new float[4];
            currentPose.getRotationQuaternion(quaternion, 0);
            float currentYaw = (float) Math.atan2(2.0f * (quaternion[3] * quaternion[1] + quaternion[0] * quaternion[2]),
                                                 1.0f - 2.0f * (quaternion[1] * quaternion[1] + quaternion[2] * quaternion[2]));
            
            // Calculate relative heading (normalized to [-PI, PI])
            float relativeHeading = NavigationUtils.normalizeAngle(targetAngle - currentYaw);
            
            // Set target heading for obstacle avoidance
            context.setTargetHeading(relativeHeading);
            
            Timber.d("Updated target heading for obstacle avoidance: %.1f degrees", 
                    Math.toDegrees(relativeHeading));
            
        } catch (JSONException e) {
            Timber.e(e, "Error calculating target heading from waypoint");
            context.setTargetHeading(0.0f); // Default to forward
        }
    }
    
    /**
     * Get the waypoint following strategy for configuration
     */
    public WaypointFollowingStrategy getWaypointStrategy() {
        return waypointStrategy;
    }
    
    /**
     * Get the obstacle avoidance strategy for configuration
     */
    public ObstacleAvoidanceStrategy getObstacleStrategy() {
        return obstacleStrategy;
    }
    
    /**
     * Set the obstacle detection threshold
     */
    public void setObstacleDetectionThreshold(float threshold) {
        // Note: This would require making OBSTACLE_DETECTION_THRESHOLD non-final
        // For now, it's a constant, but could be made configurable if needed
        Timber.d("Obstacle detection threshold is currently fixed at %.2f", OBSTACLE_DETECTION_THRESHOLD);
    }
}
