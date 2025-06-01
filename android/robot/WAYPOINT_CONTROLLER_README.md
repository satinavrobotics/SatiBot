# Waypoint Controller System

This document describes the new pluggable waypoint controller system that allows switching between different control algorithms for waypoint navigation.

## Overview

The waypoint navigation system has been refactored to use a general interface (`WaypointController`) that allows different control algorithms to be used interchangeably. This provides flexibility to choose the best control strategy for different scenarios.

## Architecture

### Core Interface: `WaypointController`

The `WaypointController` interface defines the contract for all waypoint control algorithms:

```java
public interface WaypointController {
    float calculateTurningAngularVelocity(float headingError, float maxTurnSpeed, float deltaTime);
    float calculateCourseCorrection(float headingError, float maxCorrectionStrength, float thresholdDegrees, float deltaTime);
    void reset();
    String getControllerName();
}
```

### Implementations

#### 1. RuleBasedWaypointController
- **Description**: Implements the original rule-based control logic from `NavigationUtils`
- **Characteristics**:
  - Simple proportional control based on heading error
  - No derivative term
  - Predictable behavior
  - Good for basic navigation scenarios

#### 2. PDWaypointController
- **Description**: Implements PD (Proportional-Derivative) control
- **Characteristics**:
  - Uses both proportional and derivative terms
  - Better stability and reduced overshoot
  - Configurable gains for different scenarios
  - More sophisticated control response

### Parameter Configuration

The `ControllerParameters` class holds configuration for both controller types:

```java
// For PD Controller
ControllerParameters pdParams = new ControllerParameters(
    2.0f,   // turningKp - proportional gain for turning
    0.3f,   // turningKd - derivative gain for turning
    1.0f,   // correctionKp - proportional gain for course correction
    0.1f    // correctionKd - derivative gain for course correction
);

// For Rule-Based Controller
ControllerParameters ruleParams = new ControllerParameters(
    0.1f,   // minTurnSpeed
    1.0f,   // turnSpeedScale
    1.0f    // correctionScale
);
```

## Usage

### Basic Usage

```java
// Get the waypoint following strategy from the unified navigation controller
UnifiedNavigationController controller = // ... your instance
CombinedNavigationStrategy combinedStrategy = // ... get from controller
WaypointFollowingStrategy waypointStrategy = combinedStrategy.getWaypointStrategy();

// Switch to PD controller
WaypointController pdController = WaypointControllerFactory.createPDController();
waypointStrategy.setWaypointController(pdController);

// Switch to rule-based controller
WaypointController ruleController = WaypointControllerFactory.createRuleBasedController();
waypointStrategy.setWaypointController(ruleController);
```

### Factory Methods

The `WaypointControllerFactory` provides convenient methods for creating controllers:

```java
// Default controllers
WaypointController ruleBased = WaypointControllerFactory.createRuleBasedController();
WaypointController pd = WaypointControllerFactory.createPDController();

// Preset configurations
WaypointController conservative = WaypointControllerFactory.createConservativePDController();
WaypointController aggressive = WaypointControllerFactory.createAggressivePDController();

// Custom parameters
WaypointController custom = WaypointControllerFactory.createPDController(2.5f, 0.4f, 1.2f, 0.15f);
```

### Scenario-Based Selection

Different controllers work better for different scenarios:

#### Indoor Navigation (Precise, Smooth)
```java
WaypointController indoor = WaypointControllerFactory.createConservativePDController();
```

#### Outdoor Navigation (Fast, Responsive)
```java
WaypointController outdoor = WaypointControllerFactory.createAggressivePDController();
```

#### Tight Spaces (Minimal Overshoot)
```java
WaypointController tightSpace = WaypointControllerFactory.createPDController(
    1.5f, 0.3f,  // Moderate turning gains
    0.8f, 0.2f   // Conservative correction gains
);
```

#### Legacy Behavior
```java
WaypointController legacy = WaypointControllerFactory.createRuleBasedController();
```

## PD Controller Tuning

### Proportional Gain (Kp)
- **Higher values**: Faster response, more aggressive turning
- **Lower values**: Slower response, smoother movement
- **Typical range**: 0.5 - 3.0

### Derivative Gain (Kd)
- **Higher values**: Better stability, reduced overshoot
- **Lower values**: Less damping, potential oscillation
- **Typical range**: 0.05 - 0.5

### Tuning Guidelines

1. **Start with default parameters**
2. **Adjust proportional gain** for desired response speed
3. **Adjust derivative gain** to reduce overshoot/oscillation
4. **Test in actual navigation scenarios**
5. **Fine-tune based on performance**

## Integration Points

### UnifiedNavigationController Integration

The waypoint controllers are now integrated through the WaypointFollowingStrategy:
- Accept pluggable waypoint controllers
- Calculate deltaTime for derivative calculations
- Reset controller state when starting navigation or switching waypoints
- Log controller type in debug messages

### Migration from Old System

The system has been migrated to the new unified architecture:
- WaypointNavigationController functionality moved to WaypointFollowingStrategy
- Maintains the same waypoint controller interface
- Preserves all existing control algorithms

## Testing

Use the `WaypointControllerExample` class to test different controllers:

```java
// Test controller switching with the new unified system
UnifiedNavigationController controller = // ... your instance
CombinedNavigationStrategy combinedStrategy = // ... get from controller
WaypointFollowingStrategy waypointStrategy = combinedStrategy.getWaypointStrategy();
WaypointControllerExample.demonstrateControllerSwitching(waypointStrategy);

// Test scenario-based controllers
WaypointControllerExample.demonstrateScenarioBasedControllers();

// Test controller response
WaypointControllerExample.testControllerResponse(controller, headingError, maxSpeed);
```

## Future Extensions

The interface design allows for easy addition of new control algorithms:
- PID controllers (adding integral term)
- Fuzzy logic controllers
- Neural network-based controllers
- Adaptive controllers that tune themselves

To add a new controller:
1. Implement the `WaypointController` interface
2. Add factory methods to `WaypointControllerFactory`
3. Add appropriate parameter support to `ControllerParameters`
