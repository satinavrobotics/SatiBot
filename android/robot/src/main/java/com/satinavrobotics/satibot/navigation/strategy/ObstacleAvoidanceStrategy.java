package com.satinavrobotics.satibot.navigation.strategy;

import com.satinavrobotics.satibot.navigation.ControlCommand;
import com.satinavrobotics.satibot.navigation.NavigationContext;

import timber.log.Timber;

/**
 * Navigation strategy for obstacle avoidance using cost-based navigation.
 * This strategy migrates functionality from the old NavigationController.
 */
public class ObstacleAvoidanceStrategy implements NavigationStrategy {
    private static final String TAG = ObstacleAvoidanceStrategy.class.getSimpleName();
    
    // Navigation parameters
    private static final float MAX_LINEAR_SPEED = 0.25f;
    private static final float MAX_ANGULAR_SPEED = 0.75f;
    
    // Cost-based navigation parameters
    private float traversabilityCostWeight = 3.0f;
    private float headingDeviationCostWeight = 1.0f;
    private boolean costBasedNavigationEnabled = true;
    
    @Override
    public ControlCommand calculateControl(NavigationContext context) {
        boolean[] navigabilityData = context.getNavigabilityData();
        
        if (navigabilityData == null) {
            Timber.w("No navigability data available");
            return ControlCommand.stop();
        }
        
        // Count navigable rows
        int navigableRowsCount = 0;
        for (boolean isNavigable : navigabilityData) {
            if (isNavigable) {
                navigableRowsCount++;
            }
        }
        
        // If no navigable rows, stop
        if (navigableRowsCount == 0) {
            Timber.d("No navigable rows detected, stopping");
            return ControlCommand.stop();
        }
        
        // Calculate linear speed based on navigability
        float linearSpeed = calculateLinearSpeed(navigableRowsCount, navigabilityData.length);
        
        // Calculate angular speed based on cost-based navigation or simple logic
        float angularSpeed = calculateAngularSpeed(context);
        
        Timber.d("ObstacleAvoidance: navigable=%d/%d, linear=%.2f, angular=%.2f",
                navigableRowsCount, navigabilityData.length, linearSpeed, angularSpeed);
        
        return new ControlCommand(linearSpeed, angularSpeed);
    }
    
    @Override
    public boolean isComplete(NavigationContext context) {
        // Obstacle avoidance never completes on its own - it's always active when needed
        return false;
    }
    
    @Override
    public void reset() {
        // No internal state to reset for this strategy
        Timber.d("ObstacleAvoidanceStrategy reset");
    }
    
    @Override
    public String getStrategyName() {
        return "Obstacle Avoidance";
    }
    
    @Override
    public boolean canHandle(NavigationContext context) {
        // Can always handle obstacle avoidance when navigability data is available
        return context.getNavigabilityData() != null;
    }
    
    @Override
    public int getPriority() {
        // Medium priority - should be overridden by waypoint following when appropriate
        return 50;
    }
    
    /**
     * Calculate linear speed based on navigability
     */
    private float calculateLinearSpeed(int navigableRows, int totalRows) {
        if (totalRows == 0) return 0.0f;
        
        float navigabilityRatio = (float) navigableRows / totalRows;
        
        // Scale speed based on how much of the path is clear
        if (navigabilityRatio > 0.8f) {
            return MAX_LINEAR_SPEED; // Full speed when path is mostly clear
        } else if (navigabilityRatio > 0.5f) {
            return MAX_LINEAR_SPEED * 0.7f; // Reduced speed when path is partially blocked
        } else if (navigabilityRatio > 0.2f) {
            return MAX_LINEAR_SPEED * 0.4f; // Slow speed when path is mostly blocked
        } else {
            return 0.0f; // Stop when path is almost completely blocked
        }
    }
    
    /**
     * Calculate angular speed based on cost-based navigation
     */
    private float calculateAngularSpeed(NavigationContext context) {
        if (!costBasedNavigationEnabled) {
            return 0.0f; // Go straight if cost-based navigation is disabled
        }
        
        boolean[] leftMap = context.getLeftNavigabilityMap();
        boolean[] centerMap = context.getNavigabilityData();
        boolean[] rightMap = context.getRightNavigabilityMap();
        
        if (leftMap == null || rightMap == null || centerMap == null) {
            return 0.0f; // Fallback to straight if maps are not available
        }
        
        // Calculate costs for each direction
        float leftCost = calculateDirectionCost(leftMap, -1.0f, context.getTargetHeading());
        float centerCost = calculateDirectionCost(centerMap, 0.0f, context.getTargetHeading());
        float rightCost = calculateDirectionCost(rightMap, 1.0f, context.getTargetHeading());
        
        // Find the direction with minimum cost
        float minCost = Math.min(leftCost, Math.min(centerCost, rightCost));
        
        // Determine the best direction and calculate angular velocity
        if (minCost == leftCost) {
            // Turn left
            float angularVelocity = -MAX_ANGULAR_SPEED * calculateTurnStrength(leftMap);
            Timber.d("Cost-based navigation: LEFT (cost=%.3f, angular=%.2f)", leftCost, angularVelocity);
            return angularVelocity;
        } else if (minCost == rightCost) {
            // Turn right
            float angularVelocity = MAX_ANGULAR_SPEED * calculateTurnStrength(rightMap);
            Timber.d("Cost-based navigation: RIGHT (cost=%.3f, angular=%.2f)", rightCost, angularVelocity);
            return angularVelocity;
        } else {
            // Go straight
            Timber.d("Cost-based navigation: STRAIGHT (cost=%.3f)", centerCost);
            return 0.0f;
        }
    }
    
    /**
     * Calculate cost for a specific direction
     */
    private float calculateDirectionCost(boolean[] navigabilityMap, float direction, float targetHeading) {
        if (navigabilityMap == null) {
            return Float.MAX_VALUE;
        }
        
        // Calculate traversability cost (higher cost for more obstacles)
        int obstacleCount = 0;
        for (boolean isNavigable : navigabilityMap) {
            if (!isNavigable) {
                obstacleCount++;
            }
        }
        float traversabilityCost = (float) obstacleCount / navigabilityMap.length * traversabilityCostWeight;
        
        // Calculate heading deviation cost (higher cost for deviating from target)
        float headingDeviation = Math.abs(direction - targetHeading);
        float headingCost = headingDeviation * headingDeviationCostWeight;
        
        return traversabilityCost + headingCost;
    }
    
    /**
     * Calculate turn strength based on navigability
     */
    private float calculateTurnStrength(boolean[] navigabilityMap) {
        if (navigabilityMap == null || navigabilityMap.length == 0) {
            return 0.5f; // Default moderate turn strength
        }
        
        // Calculate how clear the path is in this direction
        int navigableCount = 0;
        for (boolean isNavigable : navigabilityMap) {
            if (isNavigable) {
                navigableCount++;
            }
        }
        
        float clearness = (float) navigableCount / navigabilityMap.length;
        
        // More clearness = stronger turn (more confident in this direction)
        return Math.max(0.3f, Math.min(1.0f, clearness));
    }
    
    // Configuration methods
    public void setTraversabilityCostWeight(float weight) {
        this.traversabilityCostWeight = weight;
    }
    
    public void setHeadingDeviationCostWeight(float weight) {
        this.headingDeviationCostWeight = weight;
    }
    
    public void setCostBasedNavigationEnabled(boolean enabled) {
        this.costBasedNavigationEnabled = enabled;
    }
    
    public boolean isCostBasedNavigationEnabled() {
        return costBasedNavigationEnabled;
    }
    
    /**
     * Get current cost values for debugging
     */
    public float[] getCurrentCostValues(NavigationContext context) {
        if (!costBasedNavigationEnabled) {
            return null;
        }
        
        boolean[] leftMap = context.getLeftNavigabilityMap();
        boolean[] centerMap = context.getNavigabilityData();
        boolean[] rightMap = context.getRightNavigabilityMap();
        
        if (leftMap == null || rightMap == null || centerMap == null) {
            return null;
        }
        
        float leftCost = calculateDirectionCost(leftMap, -1.0f, context.getTargetHeading());
        float centerCost = calculateDirectionCost(centerMap, 0.0f, context.getTargetHeading());
        float rightCost = calculateDirectionCost(rightMap, 1.0f, context.getTargetHeading());
        
        return new float[]{leftCost, centerCost, rightCost};
    }
}
