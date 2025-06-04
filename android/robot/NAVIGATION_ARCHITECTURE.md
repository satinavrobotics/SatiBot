# Navigation Module User Guide

This guide explains how to use the navigation module, its interfaces, and how the different components work together to provide autonomous robot navigation.

## Overview

The navigation module uses a **strategy pattern** architecture with a single `UnifiedNavigationController` that manages pluggable navigation strategies. This provides a clean, transparent, and extensible navigation system.

## Core Components

### 1. UnifiedNavigationController

The main controller that orchestrates all navigation behavior. It manages strategy selection, processes sensor data, and sends control commands to the vehicle.

**Key Features:**
- Single point of control for all navigation
- Automatic strategy selection based on priority and capability
- Centralized data management through NavigationContext
- Event-driven navigation state updates

### 2. NavigationStrategy Interface

Defines the contract for all navigation behaviors. Each strategy implements specific navigation logic.

<augment_code_snippet path="android/robot/src/main/java/com/satinavrobotics/satibot/navigation/strategy/NavigationStrategy.java" mode="EXCERPT">
````java
public interface NavigationStrategy {
    ControlCommand calculateControl(NavigationContext context);
    boolean isComplete(NavigationContext context);
    boolean canHandle(NavigationContext context);
    int getPriority();
    String getStrategyName();
    void reset();
}
````
</augment_code_snippet>

### 3. NavigationContext

Centralized data container that holds all information needed for navigation decisions.

<augment_code_snippet path="android/robot/src/main/java/com/satinavrobotics/satibot/navigation/NavigationContext.java" mode="EXCERPT">
````java
public class NavigationContext {
    // Navigation states
    public enum NavigationState {
        IDLE, TURNING, MOVING, AVOIDING, COMPLETED
    }

    // Core data
    private boolean[] navigabilityData;
    private Pose currentPose;
    private JSONObject targetWaypoint;
    private float targetHeading;

    // Parameters
    private float maxLinearSpeed = 0.25f;
    private float maxAngularSpeed = 0.75f;
    private ControllerParameters parameters;
}
````
</augment_code_snippet>

### 4. ControlCommand

Standardized command structure for vehicle control with linear and angular velocities.

<augment_code_snippet path="android/robot/src/main/java/com/satinavrobotics/satibot/navigation/ControlCommand.java" mode="EXCERPT">
````java
public class ControlCommand {
    private final float linearVelocity;  // -1.0 to 1.0
    private final float angularVelocity; // -1.0 to 1.0

    public static ControlCommand stop();
    public static ControlCommand forward(float speed);
    public static ControlCommand turn(float angularSpeed);
    public static ControlCommand move(float linearSpeed, float angularSpeed);
}
````
</augment_code_snippet>

## Navigation Strategies

### 1. CombinedNavigationStrategy (Priority: 100)

The recommended strategy that intelligently combines waypoint following with obstacle avoidance.

**How it works:**
- Monitors navigability data to detect obstacles
- Uses waypoint following when path is clear
- Switches to obstacle avoidance when obstacles detected
- Informs obstacle avoidance of target waypoint direction

**Configuration:**
<augment_code_snippet path="android/robot/src/main/java/com/satinavrobotics/satibot/navigation/strategy/CombinedNavigationStrategy.java" mode="EXCERPT">
````java
public CombinedNavigationStrategy() {
    this.waypointStrategy = new WaypointFollowingStrategy();
    this.obstacleStrategy = new ObstacleAvoidanceStrategy();

    // Configure obstacle avoidance for integration
    obstacleStrategy.setTraversabilityCostWeight(3.0f);
    obstacleStrategy.setHeadingDeviationCostWeight(1.0f);
    obstacleStrategy.setCostBasedNavigationEnabled(true);
}
````
</augment_code_snippet>

### 2. WaypointFollowingStrategy (Priority: 80)

Pure waypoint navigation with turn-in-place behavior.

**Behavior:**
- **TURNING**: Rotates in place toward waypoint until aligned
- **MOVING**: Moves forward toward waypoint with course correction
- Uses configurable WaypointController for control algorithms

### 3. ObstacleAvoidanceStrategy (Priority: 50)

Cost-based obstacle avoidance navigation.

**Features:**
- Analyzes navigability maps to find clear paths
- Uses cost functions for traversability and heading deviation
- Supports both simple and cost-based navigation modes

## Usage Examples

### Basic Setup

The simplest way to set up navigation:

```java
// Create unified controller
UnifiedNavigationController controller = new UnifiedNavigationController(vehicle, waypointsManager);

// Add combined strategy (recommended for most use cases)
CombinedNavigationStrategy strategy = new CombinedNavigationStrategy();
controller.addStrategy(strategy);

// Set up navigation listener
controller.setNavigationListener(new UnifiedNavigationController.NavigationListener() {
    @Override
    public void onNavigationStateChanged(NavigationContext.NavigationState state, JSONObject waypoint) {
        // Handle state changes (TURNING, MOVING, AVOIDING, etc.)
        updateUI(state, waypoint);
    }

    @Override
    public void onNavigationCompleted() {
        // All waypoints reached
        showCompletionMessage();
    }

    @Override
    public void onNavigationError(String error) {
        // Handle navigation errors
        showError(error);
    }
});

// Start navigation
controller.startNavigation();
```

### Integration with UI Components

How to integrate with DepthNavigationFragment:

<augment_code_snippet path="android/robot/src/main/java/com/satinavrobotics/satibot/robot/DepthNavigationFragment.java" mode="EXCERPT">
````java
// Create UnifiedNavigationController
unifiedNavigationController = new UnifiedNavigationController(vehicle, waypointsManager);

// Create combined navigation strategy
combinedNavigationStrategy = new CombinedNavigationStrategy();
unifiedNavigationController.addStrategy(combinedNavigationStrategy);

// Set up navigation listener for UI updates
unifiedNavigationController.setNavigationListener(new UnifiedNavigationController.NavigationListener() {
    @Override
    public void onNavigationStateChanged(NavigationContext.NavigationState state, JSONObject waypoint) {
        requireActivity().runOnUiThread(() -> {
            switch (state) {
                case TURNING:
                    navigationStatusText.setText("Turning to waypoint");
                    break;
                case MOVING:
                    navigationStatusText.setText("Moving to waypoint");
                    break;
                case AVOIDING:
                    navigationStatusText.setText("Avoiding obstacles");
                    break;
            }
        });
    }
});
````
</augment_code_snippet>

### Data Flow Integration

The controller receives data from multiple sources:

```java
// Update pose from ARCore
controller.updateCurrentPose(pose);

// Update navigability data from depth processing
controller.updateNavigabilityData(navigabilityData, leftNavMap, rightNavMap);

// The controller automatically processes navigation when data is updated
```

## Configuration and Customization

### Navigation Parameters

Configure navigation behavior through NavigationContext:

```java
NavigationContext context = controller.getContext();

// Speed limits
context.setMaxLinearSpeed(0.3f);    // Max forward speed
context.setMaxAngularSpeed(0.8f);   // Max turning speed

// Navigation thresholds
context.setRotationThresholdDegrees(15.0f);  // Heading accuracy for waypoint approach
context.setPositionThresholdMeters(0.15f);   // Distance threshold for waypoint completion

// Controller parameters
ControllerParameters params = ControllerParameters.createDefaultRuleBased();
context.setParameters(params);
```

### Waypoint Controller Configuration

The WaypointFollowingStrategy uses configurable controllers for different navigation behaviors:

```java
// Access the waypoint strategy from combined strategy
CombinedNavigationStrategy combined = (CombinedNavigationStrategy) controller.getActiveStrategy();
WaypointFollowingStrategy waypointStrategy = combined.getWaypointStrategy();

// Switch to PD controller for smoother navigation
WaypointController pdController = WaypointControllerFactory.createPDController();
waypointStrategy.setWaypointController(pdController);

// Or use rule-based controller for predictable behavior
WaypointController ruleController = WaypointControllerFactory.createRuleBasedController();
waypointStrategy.setWaypointController(ruleController);
```

### Obstacle Avoidance Tuning

Configure obstacle avoidance behavior:

```java
ObstacleAvoidanceStrategy obstacleStrategy = new ObstacleAvoidanceStrategy();

// Cost function weights
obstacleStrategy.setTraversabilityCostWeight(5.0f);  // Higher = avoid obstacles more
obstacleStrategy.setHeadingDeviationCostWeight(2.0f); // Higher = prefer straight paths

// Enable cost-based navigation for smarter pathfinding
obstacleStrategy.setCostBasedNavigationEnabled(true);
```

## Strategy Priority and Selection

The controller automatically selects strategies based on priority and capability:

| Strategy | Priority | Capability |
|----------|----------|------------|
| CombinedNavigationStrategy | 100 | Waypoint following + obstacle avoidance |
| WaypointFollowingStrategy | 80 | Pure waypoint navigation |
| ObstacleAvoidanceStrategy | 50 | Obstacle avoidance only |

**Selection Logic:**
1. Controller evaluates all strategies using `canHandle(context)`
2. Selects highest priority strategy that can handle current situation
3. Switches strategies automatically when conditions change

### Strategy Capability Conditions

**CombinedNavigationStrategy** can handle:
- Has target waypoint AND navigability data available

**WaypointFollowingStrategy** can handle:
- Has target waypoint (regardless of obstacles)

**ObstacleAvoidanceStrategy** can handle:
- Has navigability data (regardless of waypoints)

## Data Flow Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────────────┐
│   DepthProcessor │───▶│   NavMapOverlay  │───▶│ UnifiedNavigationController │
└─────────────────┘    └──────────────────┘    └─────────────────────────┘
                                                              │
┌─────────────────┐                                          ▼
│     ARCore      │─────────────────────────────────▶┌──────────────────┐
│   (Pose Data)   │                                  │ NavigationContext │
└─────────────────┘                                  └──────────────────┘
                                                              │
┌─────────────────┐                                          ▼
│ WaypointsManager │─────────────────────────────────▶┌──────────────────┐
│   (Waypoints)   │                                  │ NavigationStrategy │
└─────────────────┘                                  └──────────────────┘
                                                              │
                                                              ▼
                                                      ┌──────────────────┐
                                                      │  ControlCommand  │
                                                      └──────────────────┘
                                                              │
                                                              ▼
                                                      ┌──────────────────┐
                                                      │     Vehicle      │
                                                      └──────────────────┘
```

### Data Update Flow

1. **Pose Updates**: ARCore provides robot pose → `updateCurrentPose(pose)`
2. **Depth Data**: DepthProcessor generates navigability maps → `updateNavigabilityData(...)`
3. **Waypoints**: WaypointsManager provides target waypoints → loaded automatically
4. **Processing**: Controller processes navigation on each pose/data update
5. **Output**: ControlCommand sent to vehicle for execution

## Navigation States and Lifecycle

### Navigation States

The NavigationContext tracks the current navigation state:

- **IDLE**: Not actively navigating
- **TURNING**: Rotating in place toward waypoint
- **MOVING**: Moving forward toward waypoint
- **AVOIDING**: Avoiding obstacles while navigating
- **COMPLETED**: All waypoints reached

### Lifecycle Management

```java
// Start navigation
controller.startNavigation();  // Loads first waypoint, sets state to TURNING

// During navigation
// - State automatically transitions: TURNING → MOVING → (AVOIDING) → repeat for next waypoint
// - Strategy selection happens automatically based on conditions

// Stop navigation
controller.stopNavigation();   // Stops vehicle, resets strategies, sets state to IDLE

// Check status
boolean isActive = controller.isNavigating();
NavigationStrategy active = controller.getActiveStrategy();
NavigationContext.NavigationState state = controller.getContext().getCurrentState();
```

## Error Handling and Recovery

### Common Error Scenarios

1. **No Waypoints Available**
   ```java
   // Error: "No waypoints available"
   // Recovery: Add waypoints to WaypointsManager before starting
   ```

2. **Missing Pose Data**
   ```java
   // Navigation pauses when pose is null
   // Recovery: Ensure ARCore is tracking properly
   ```

3. **No Suitable Strategy**
   ```java
   // Warning: "No suitable navigation strategy found"
   // Recovery: Ensure at least one strategy is added that can handle the situation
   ```

### Error Handling Best Practices

```java
controller.setNavigationListener(new UnifiedNavigationController.NavigationListener() {
    @Override
    public void onNavigationError(String error) {
        Timber.e("Navigation error: %s", error);

        // Handle specific errors
        if (error.contains("No waypoints")) {
            // Prompt user to add waypoints
            showWaypointSetupDialog();
        } else if (error.contains("tracking")) {
            // Show ARCore tracking issues
            showTrackingErrorMessage();
        }

        // Stop navigation on critical errors
        controller.stopNavigation();
    }
});
```

## Debugging and Monitoring

### Logging and Diagnostics

The navigation system provides extensive logging for debugging:

```java
// Enable debug logging
Timber.plant(new Timber.DebugTree());

// Key log messages to monitor:
// - Strategy selection: "Switching from X to Y"
// - Navigation commands: "Navigation: StrategyName -> ControlCommand{...}"
// - State changes: "Navigation state changed: TURNING -> MOVING"
// - Waypoint progress: "Loaded waypoint 2 of 5"
```

### Runtime Inspection

```java
// Check current state
NavigationContext context = controller.getContext();
Timber.d("Current state: %s", context.getCurrentState());
Timber.d("Active strategy: %s", controller.getActiveStrategy().getStrategyName());
Timber.d("Target waypoint: %s", context.getTargetWaypoint());
Timber.d("Waypoint progress: %d/%d", context.getCurrentWaypointIndex(), context.getTotalWaypointCount());

// Check navigation data
boolean[] navData = context.getNavigabilityData();
if (navData != null) {
    int navigableRows = 0;
    for (boolean navigable : navData) {
        if (navigable) navigableRows++;
    }
    float navigabilityRatio = (float) navigableRows / navData.length;
    Timber.d("Navigability: %.2f%% (%d/%d rows)", navigabilityRatio * 100, navigableRows, navData.length);
}
```

### Performance Monitoring

```java
// Monitor navigation timing
NavigationContext context = controller.getContext();
float deltaTime = context.getDeltaTimeSeconds();
Timber.d("Navigation update interval: %.3f seconds", deltaTime);

// Monitor control command frequency
// Should see regular ControlCommand logs during active navigation
```

## Advanced Usage

### Multiple Strategy Configuration

You can add multiple strategies for different scenarios:

```java
UnifiedNavigationController controller = new UnifiedNavigationController(vehicle, waypointsManager);

// Add multiple strategies in order of preference
controller.addStrategy(new CombinedNavigationStrategy());           // Priority 100
controller.addStrategy(new WaypointFollowingStrategy());            // Priority 80
controller.addStrategy(new ObstacleAvoidanceStrategy());            // Priority 50

// Controller will automatically select the best strategy for each situation
```

### Custom Strategy Implementation

Create custom navigation strategies by implementing the NavigationStrategy interface:

```java
public class EmergencyStopStrategy implements NavigationStrategy {

    @Override
    public ControlCommand calculateControl(NavigationContext context) {
        // Always return stop command
        return ControlCommand.stop();
    }

    @Override
    public boolean isComplete(NavigationContext context) {
        return false; // Never completes - always available
    }

    @Override
    public boolean canHandle(NavigationContext context) {
        // Handle emergency conditions (e.g., low battery, critical errors)
        return isEmergencyCondition(context);
    }

    @Override
    public int getPriority() {
        return 200; // Highest priority - overrides all other strategies
    }

    @Override
    public String getStrategyName() {
        return "EmergencyStop";
    }

    @Override
    public void reset() {
        // No state to reset
    }

    private boolean isEmergencyCondition(NavigationContext context) {
        // Implement emergency detection logic
        return false;
    }
}

// Add to controller
controller.addStrategy(new EmergencyStopStrategy());
```

### Dynamic Strategy Configuration

Modify strategy behavior at runtime:

```java
// Get active strategy and configure it
NavigationStrategy activeStrategy = controller.getActiveStrategy();

if (activeStrategy instanceof CombinedNavigationStrategy) {
    CombinedNavigationStrategy combined = (CombinedNavigationStrategy) activeStrategy;

    // Access sub-strategies
    ObstacleAvoidanceStrategy obstacle = combined.getObstacleStrategy();
    obstacle.setTraversabilityCostWeight(8.0f); // More aggressive obstacle avoidance

    WaypointFollowingStrategy waypoint = combined.getWaypointStrategy();
    // Switch to PD controller for smoother waypoint following
    waypoint.setWaypointController(WaypointControllerFactory.createPDController());
}
```

### Integration with External Systems

```java
// Custom navigation listener for external system integration
controller.setNavigationListener(new UnifiedNavigationController.NavigationListener() {
    @Override
    public void onNavigationStateChanged(NavigationContext.NavigationState state, JSONObject waypoint) {
        // Send state updates to external monitoring system
        externalMonitor.updateNavigationState(state.name(), waypoint);

        // Log to analytics
        analytics.trackNavigationEvent("state_change", state.name());
    }

    @Override
    public void onNavigationCompleted() {
        // Notify mission control
        missionControl.reportNavigationComplete();

        // Start next mission phase
        startNextMissionPhase();
    }

    @Override
    public void onNavigationError(String error) {
        // Send error to monitoring system
        errorReporting.reportNavigationError(error);

        // Trigger recovery procedures
        recoverySystem.handleNavigationFailure(error);
    }
});
```

## Best Practices

### 1. Strategy Selection
- Use **CombinedNavigationStrategy** for most autonomous navigation scenarios
- Use **WaypointFollowingStrategy** when obstacles are not a concern
- Use **ObstacleAvoidanceStrategy** for manual obstacle avoidance without waypoints

### 2. Configuration
- Set appropriate speed limits based on environment (indoor vs outdoor)
- Tune rotation and position thresholds based on required precision
- Configure obstacle avoidance weights based on safety requirements

### 3. Error Handling
- Always implement NavigationListener for proper error handling
- Monitor navigation state changes for UI updates
- Implement recovery procedures for common failure modes

### 4. Performance
- Monitor navigation update frequency (should be consistent)
- Check for strategy switching frequency (excessive switching indicates configuration issues)
- Validate navigability data quality before navigation

### 5. Testing
- Test each strategy independently before using CombinedNavigationStrategy
- Verify waypoint controller behavior in different scenarios
- Test error conditions and recovery procedures

## Migration Guide

### From Old Navigation System

If migrating from the previous NavigationController/WaypointNavigationController system:

1. **Replace Controllers**:
   ```java
   // Old system
   NavigationController navController = new NavigationController(...);
   WaypointNavigationController waypointController = new WaypointNavigationController(...);

   // New system
   UnifiedNavigationController controller = new UnifiedNavigationController(vehicle, waypointsManager);
   controller.addStrategy(new CombinedNavigationStrategy());
   ```

2. **Update Data Flow**:
   ```java
   // Old: Multiple update calls
   navController.updateNavigabilityData(...);
   waypointController.updatePose(...);

   // New: Single controller updates
   controller.updateNavigabilityData(...);
   controller.updateCurrentPose(...);
   ```

3. **Replace Event Handling**:
   ```java
   // Old: Multiple listeners
   waypointController.setWaypointListener(...);
   navController.setNavigationListener(...);

   // New: Single listener
   controller.setNavigationListener(...);
   ```

The new system provides the same functionality with improved transparency and reduced complexity.
