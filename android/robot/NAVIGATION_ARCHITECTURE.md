# Simplified Navigation Architecture

This document describes the new unified navigation architecture that replaces the previous complex system with multiple controllers.

## Overview

The new architecture uses a **strategy pattern** with a single `UnifiedNavigationController` that manages pluggable navigation strategies. This eliminates the complexity and transparency issues of the previous system.

## Architecture Components

### Core Classes

1. **UnifiedNavigationController** - Single point of control for all navigation
2. **NavigationStrategy** - Interface for pluggable navigation behaviors
3. **NavigationContext** - Centralized data container for all navigation information
4. **ControlCommand** - Standardized command structure for vehicle control

### Navigation Strategies

1. **CombinedNavigationStrategy** - Integrates waypoint following with obstacle avoidance (highest priority)
2. **WaypointFollowingStrategy** - Pure waypoint navigation with turn-in-place behavior
3. **ObstacleAvoidanceStrategy** - Cost-based obstacle avoidance navigation

### Data Flow

```
DepthProcessor → NavMapOverlay → UnifiedNavigationController
                                        ↓
                                NavigationStrategy
                                        ↓
                                   ControlCommand
                                        ↓
                                     Vehicle
```

## Key Improvements

### 1. Single Point of Control
- Only `UnifiedNavigationController` sends commands to the vehicle
- Eliminates conflicts between multiple controllers
- Clear ownership of vehicle control

### 2. Transparent Strategy Selection
- Active strategy is always known and logged
- Easy to debug which strategy is making decisions
- Strategy switching is explicit and traceable

### 3. Centralized Data Management
- All navigation data flows through `NavigationContext`
- No duplicate data passing between classes
- Single source of truth for navigation state

### 4. Simplified Communication
- Direct data flow from sensors to controller
- No complex callback chains
- Reduced coupling between components

### 5. Pluggable Behaviors
- Easy to add new navigation strategies
- Strategies can be combined or used independently
- Runtime strategy switching based on conditions

## Usage Examples

### Basic Setup
```java
// Create unified controller
UnifiedNavigationController controller = new UnifiedNavigationController(vehicle, waypointsManager);

// Add combined strategy (recommended)
CombinedNavigationStrategy strategy = new CombinedNavigationStrategy();
controller.addStrategy(strategy);

// Start navigation
controller.startNavigation();
```

### Using Factory
```java
// Create with default configuration
UnifiedNavigationController controller = NavigationFactory.createDefaultNavigationController(vehicle, waypointsManager);

// Set up listener
controller.setNavigationListener(new NavigationListener() {
    @Override
    public void onNavigationStateChanged(NavigationState state, JSONObject waypoint) {
        // Handle state changes
    }
});
```

### Custom Strategy Configuration
```java
// Create custom obstacle avoidance
ObstacleAvoidanceStrategy obstacleStrategy = NavigationFactory.createObstacleAvoidanceStrategy(
    5.0f,  // traversability cost weight
    2.0f,  // heading deviation cost weight
    true   // cost-based navigation enabled
);

controller.addStrategy(obstacleStrategy);
```

## Strategy Priority System

Strategies are selected based on priority and capability:

1. **CombinedNavigationStrategy** (Priority: 100) - Handles waypoint following with obstacle avoidance
2. **WaypointFollowingStrategy** (Priority: 80) - Handles pure waypoint following
3. **ObstacleAvoidanceStrategy** (Priority: 50) - Handles obstacle avoidance only

The controller automatically selects the highest priority strategy that can handle the current situation.

## Migration from Old System

### Removed Classes
- `NavigationController` - Functionality moved to `ObstacleAvoidanceStrategy`
- `WaypointNavigationController` - Functionality moved to `WaypointFollowingStrategy` and `UnifiedNavigationController`

### Updated Classes
- `DepthNavigationFragment` - Now uses `UnifiedNavigationController`
- Navigation data flow simplified and centralized

### Preserved Interfaces
- `WaypointController` - Still used by `WaypointFollowingStrategy`
- `WaypointsManager` - Unchanged
- `NavigationUtils` - Unchanged

## Benefits

1. **Reduced Complexity** - Single controller instead of multiple interacting controllers
2. **Improved Transparency** - Always clear which strategy is active
3. **Better Testability** - Strategies can be tested independently
4. **Easier Debugging** - Single point of control with clear logging
5. **Enhanced Flexibility** - Easy to add new behaviors or modify existing ones
6. **Eliminated Race Conditions** - No more conflicts between multiple controllers

## Configuration

The system can be configured through:

1. **NavigationFactory** - Provides pre-configured setups
2. **Strategy Configuration** - Individual strategy parameters
3. **NavigationContext** - Runtime parameters and thresholds

## Debugging

To debug navigation issues:

1. Check active strategy: `controller.getActiveStrategy().getStrategyName()`
2. Monitor strategy switching in logs
3. Examine navigation context: `controller.getContext()`
4. Review control commands in logs

## Future Extensions

The architecture supports easy addition of new strategies:

1. Implement `NavigationStrategy` interface
2. Add to controller with appropriate priority
3. Strategy will be automatically selected when appropriate

Example new strategies could include:
- GPS-based navigation
- Vision-based navigation
- Emergency stop strategy
- Formation following strategy
