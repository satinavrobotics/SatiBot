package com.satinavrobotics.satibot.navigation;

import android.os.Handler;
import android.os.Looper;

import com.google.ar.core.Pose;
import com.satinavrobotics.satibot.navigation.strategy.NavigationStrategy;
import com.satinavrobotics.satibot.vehicle.Vehicle;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * Unified navigation controller that manages all navigation behaviors through pluggable strategies.
 * This replaces the previous NavigationController and WaypointNavigationController with a single,
 * transparent control system.
 */
public class UnifiedNavigationController {
    private static final String TAG = UnifiedNavigationController.class.getSimpleName();
    
    // Core components
    private final Vehicle vehicle;
    private final WaypointsManager waypointsManager;
    private final Handler mainHandler;
    
    // Navigation context and strategies
    private final NavigationContext context;
    private final List<NavigationStrategy> strategies;
    private NavigationStrategy activeStrategy;
    
    // State tracking
    private boolean isNavigating = false;
    private NavigationListener navigationListener;
    
    /**
     * Interface for receiving navigation updates
     */
    public interface NavigationListener {
        void onNavigationStateChanged(NavigationContext.NavigationState state, JSONObject currentWaypoint);
        void onNavigationCompleted();
        void onNavigationError(String error);
    }
    
    /**
     * Constructor for UnifiedNavigationController
     * 
     * @param vehicle The vehicle to control
     * @param waypointsManager Manager for waypoint queue
     */
    public UnifiedNavigationController(Vehicle vehicle, WaypointsManager waypointsManager) {
        this.vehicle = vehicle;
        this.waypointsManager = waypointsManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.context = new NavigationContext();
        this.strategies = new ArrayList<>();
        
        Timber.d("UnifiedNavigationController initialized");
    }
    
    /**
     * Add a navigation strategy to the controller
     * 
     * @param strategy The strategy to add
     */
    public void addStrategy(NavigationStrategy strategy) {
        strategies.add(strategy);
        Timber.d("Added navigation strategy: %s", strategy.getStrategyName());
    }
    
    /**
     * Remove a navigation strategy from the controller
     * 
     * @param strategy The strategy to remove
     */
    public void removeStrategy(NavigationStrategy strategy) {
        strategies.remove(strategy);
        if (activeStrategy == strategy) {
            activeStrategy = null;
        }
        Timber.d("Removed navigation strategy: %s", strategy.getStrategyName());
    }
    
    /**
     * Start navigation with the current waypoints
     */
    public void startNavigation() {
        if (!waypointsManager.hasNextWaypoint()) {
            Timber.w("No waypoints available for navigation");
            notifyNavigationError("No waypoints available");
            return;
        }
        
        isNavigating = true;
        context.setCurrentState(NavigationContext.NavigationState.IDLE);
        context.setTotalWaypointCount(waypointsManager.getWaypointCount());
        context.setCurrentWaypointIndex(0);
        
        // Load the first waypoint
        loadNextWaypoint();
        
        Timber.i("Navigation started with %d waypoints", context.getTotalWaypointCount());
    }
    
    /**
     * Stop navigation and halt the vehicle
     */
    public void stopNavigation() {
        isNavigating = false;
        context.setCurrentState(NavigationContext.NavigationState.IDLE);
        
        // Reset active strategy
        if (activeStrategy != null) {
            activeStrategy.reset();
            activeStrategy = null;
        }
        
        // Stop the vehicle
        sendControlCommand(ControlCommand.stop());
        
        Timber.i("Navigation stopped");
    }
    
    /**
     * Update the current pose from ARCore
     * 
     * @param pose The current robot pose
     */
    public void updateCurrentPose(Pose pose) {
        context.setCurrentPose(pose);
        
        // Process navigation if we're actively navigating
        if (isNavigating) {
            processNavigation();
        }
    }
    
    /**
     * Update navigability data from depth processing
     * 
     * @param navigabilityData Array of boolean values indicating if each row is navigable
     * @param leftNavigabilityMap Left window navigability map
     * @param rightNavigabilityMap Right window navigability map
     */
    public void updateNavigabilityData(boolean[] navigabilityData, 
                                     boolean[] leftNavigabilityMap, 
                                     boolean[] rightNavigabilityMap) {
        context.setNavigabilityData(navigabilityData);
        context.setLeftNavigabilityMap(leftNavigabilityMap);
        context.setRightNavigabilityMap(rightNavigabilityMap);
        
        // Process navigation if we're actively navigating
        if (isNavigating && (context.getCurrentState() == NavigationContext.NavigationState.MOVING ||
                           context.getCurrentState() == NavigationContext.NavigationState.AVOIDING)) {
            processNavigation();
        }
    }
    
    /**
     * Main navigation processing method
     */
    private void processNavigation() {
        if (!isNavigating || context.getCurrentPose() == null) {
            return;
        }
        
        // Update timing
        context.updateTiming();
        
        // Select the appropriate strategy
        selectActiveStrategy();
        
        if (activeStrategy == null) {
            Timber.w("No suitable navigation strategy found");
            sendControlCommand(ControlCommand.stop());
            return;
        }
        
        // Check if current strategy is complete
        if (activeStrategy.isComplete(context)) {
            handleStrategyCompletion();
            return;
        }
        
        // Calculate and send control command
        ControlCommand command = activeStrategy.calculateControl(context);
        sendControlCommand(command);
        
        Timber.d("Navigation: %s -> %s", activeStrategy.getStrategyName(), command);
    }
    
    /**
     * Select the most appropriate strategy for the current context
     */
    private void selectActiveStrategy() {
        NavigationStrategy bestStrategy = null;
        int highestPriority = -1;
        
        for (NavigationStrategy strategy : strategies) {
            if (strategy.canHandle(context) && strategy.getPriority() > highestPriority) {
                bestStrategy = strategy;
                highestPriority = strategy.getPriority();
            }
        }
        
        // Switch strategy if needed
        if (bestStrategy != activeStrategy) {
            if (activeStrategy != null) {
                Timber.d("Switching from %s to %s", activeStrategy.getStrategyName(), 
                        bestStrategy != null ? bestStrategy.getStrategyName() : "none");
            }
            
            activeStrategy = bestStrategy;
            if (activeStrategy != null) {
                activeStrategy.reset();
            }
        }
    }
    
    /**
     * Handle completion of the current strategy
     */
    private void handleStrategyCompletion() {
        if (activeStrategy.getStrategyName().contains("Waypoint")) {
            // Waypoint reached, load next one
            loadNextWaypoint();
        } else {
            // Other strategy completed, continue with current waypoint
            Timber.d("Strategy %s completed", activeStrategy.getStrategyName());
        }
    }
    
    /**
     * Load the next waypoint from the queue
     */
    private void loadNextWaypoint() {
        if (waypointsManager.hasNextWaypoint()) {
            JSONObject nextWaypoint = waypointsManager.peekNextWaypointInLocalCoordinates();
            context.setTargetWaypoint(nextWaypoint);
            context.setCurrentWaypointIndex(context.getCurrentWaypointIndex() + 1);
            context.setCurrentState(NavigationContext.NavigationState.TURNING);
            
            // Reset all strategies for new waypoint
            for (NavigationStrategy strategy : strategies) {
                strategy.reset();
            }
            
            Timber.i("Loaded waypoint %d of %d", context.getCurrentWaypointIndex(), context.getTotalWaypointCount());
            notifyNavigationStateChanged(context.getCurrentState(), nextWaypoint);
        } else {
            // All waypoints completed
            context.setCurrentState(NavigationContext.NavigationState.COMPLETED);
            stopNavigation();
            notifyNavigationCompleted();
            Timber.i("All waypoints completed");
        }
    }
    
    /**
     * Send control command to vehicle
     * 
     * @param command The control command to send
     */
    private void sendControlCommand(ControlCommand command) {
        mainHandler.post(() -> {
            try {
                vehicle.setControlVelocity(command.getLinearVelocity(), command.getAngularVelocity());
                vehicle.sendControl();
                Timber.d("Control command sent: %s", command);
            } catch (Exception e) {
                Timber.e(e, "Error sending control command");
            }
        });
    }
    
    // Getters and setters
    public NavigationContext getContext() {
        return context;
    }
    
    public boolean isNavigating() {
        return isNavigating;
    }
    
    public NavigationStrategy getActiveStrategy() {
        return activeStrategy;
    }
    
    public void setNavigationListener(NavigationListener listener) {
        this.navigationListener = listener;
    }
    
    // Notification methods
    private void notifyNavigationStateChanged(NavigationContext.NavigationState state, JSONObject waypoint) {
        if (navigationListener != null) {
            navigationListener.onNavigationStateChanged(state, waypoint);
        }
    }
    
    private void notifyNavigationCompleted() {
        if (navigationListener != null) {
            navigationListener.onNavigationCompleted();
        }
    }
    
    private void notifyNavigationError(String error) {
        if (navigationListener != null) {
            navigationListener.onNavigationError(error);
        }
    }
}
