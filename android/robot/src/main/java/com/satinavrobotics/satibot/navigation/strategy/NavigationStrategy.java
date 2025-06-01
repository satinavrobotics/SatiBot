package com.satinavrobotics.satibot.navigation.strategy;

import com.satinavrobotics.satibot.navigation.ControlCommand;
import com.satinavrobotics.satibot.navigation.NavigationContext;

/**
 * Interface for navigation strategies that can be plugged into the UnifiedNavigationController.
 * Each strategy implements a specific navigation behavior (obstacle avoidance, waypoint following, etc.)
 */
public interface NavigationStrategy {
    
    /**
     * Calculate the control command based on the current navigation context
     * 
     * @param context Current navigation context containing all sensor data, pose, and parameters
     * @return Control command to send to the vehicle
     */
    ControlCommand calculateControl(NavigationContext context);
    
    /**
     * Check if this strategy has completed its objective
     * 
     * @param context Current navigation context
     * @return true if the strategy is complete and should be switched
     */
    boolean isComplete(NavigationContext context);
    
    /**
     * Reset the strategy state (e.g., clear derivative terms, reset internal state)
     * Should be called when starting a new navigation task or switching strategies
     */
    void reset();
    
    /**
     * Get the name/type of this strategy for logging and UI purposes
     * 
     * @return Strategy name (e.g., "Obstacle Avoidance", "Waypoint Following")
     */
    String getStrategyName();
    
    /**
     * Check if this strategy can handle the current navigation context
     * Used by the controller to determine which strategy to activate
     * 
     * @param context Current navigation context
     * @return true if this strategy can handle the current situation
     */
    boolean canHandle(NavigationContext context);
    
    /**
     * Get the priority of this strategy (higher number = higher priority)
     * Used when multiple strategies can handle the same context
     * 
     * @return Priority value (0-100, where 100 is highest priority)
     */
    int getPriority();
}
