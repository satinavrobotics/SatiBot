# Velocity Controller

This document explains how to use the Velocity Controller in the SatiBot firmware.

## Overview

The Velocity Controller allows the robot to:

1. Maintain a target angular velocity by automatically adjusting the motor controls based on feedback from the IMU. This helps the robot maintain a more consistent turning rate, especially on uneven surfaces or when the motors have different performance characteristics.

2. Calculate the normalized linear velocity (0.0 to 1.0) based on the current PWM values sent to the motors, using the formula: `(currentPwmLeft + currentPwmRight) / 2 / 255.0`

## How to Enable the Velocity Controller

There are two ways to enable the velocity controller:

### 1. Enable in Config.h

Edit the `Config.h` file and set the `PID_CONTROLLER_MODE` flag to `true` (this flag name is kept for backward compatibility):

```cpp
// Enable/Disable velocity controller (true/false)
#define PID_CONTROLLER_MODE true
```

This will enable the velocity controller by default when the robot starts up.

### 2. Enable via Bluetooth/Serial Command

You can enable or disable the velocity controller at runtime by sending a command with the following format:

```
p<enabled>,<kp>,<ki>,<kd>
```

Where:
- `<enabled>` is 1 to enable or 0 to disable the controller
- `<kp>` is the proportional gain (optional, use 0 to keep current value)
- `<ki>` is the integral gain (optional, use 0 to keep current value)
- `<kd>` is the derivative gain (optional, use 0 to keep current value)

Examples:
- `p1,2.0,0.5,0.1` - Enable the controller with Kp=2.0, Ki=0.5, Kd=0.1
- `p1,0,0,0` - Enable the controller with current PID values
- `p0,0,0,0` - Disable the controller

## How It Works

1. The controller calculates a target angular velocity based on the current PWM values sent to the motors
2. It calculates the normalized linear velocity (0.0 to 1.0) based on the current PWM values
3. It reads the actual angular velocity from the IMU
4. It applies PID control to calculate the necessary adjustment to the motor controls
5. It applies this adjustment to maintain the target angular velocity

## Tuning the Velocity Controller

The default PID values (Kp=2.0, Ki=0.5, Kd=0.1) are a starting point and may need adjustment for optimal performance. Here's how to tune the controller:

1. Start with a low Kp value (e.g., 1.0) and set Ki and Kd to 0
2. Gradually increase Kp until the robot responds quickly to changes in target angular velocity but doesn't oscillate
3. Add a small Ki value (e.g., 0.1) to eliminate steady-state error
4. Add a small Kd value (e.g., 0.05) to reduce overshoot and dampen oscillations

You can monitor the controller's performance in debug mode by setting `DEBUG_MODE` to `true` in `Config.h`.

## Testing the Velocity Controller

You can test the PID controller using the built-in test mode:

1. Enter test mode by pressing 't' in the Serial Monitor
2. Select option 7 (Velocity Controller Test)

The test will cycle through different target angular velocities and show the error between the target and actual values.

## Troubleshooting

If the velocity controller is not working as expected:

1. Check that the IMU is properly initialized and providing valid readings
2. Verify that the velocity controller is enabled (either in Config.h or via command)
3. Try different PID values to find the optimal settings for your robot
4. Enable debug mode to see detailed information about the controller's performance
