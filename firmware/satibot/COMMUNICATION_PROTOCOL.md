# OpenBot Communication Protocol

This document describes the communication protocol used between the OpenBot firmware and the phone application.

## Overview

The OpenBot firmware communicates with the phone application using a simple text-based protocol over either Serial (USB) or Bluetooth. Each message consists of a single character prefix (command) followed by data, and ends with a newline character.

## Messages from Phone to Robot

Messages sent from the phone to the robot have the following format:
```
<command><data>\n
```

Where:
- `<command>` is a single character indicating the type of message
- `<data>` is the message payload (format depends on the command)
- `\n` is the newline character (ASCII 10) that marks the end of the message

### Command Types (Phone to Robot)

| Command | Description | Data Format | Example |
|---------|-------------|-------------|---------|
| `c` | Control message (set velocities) | `<linear_velocity>,<angular_velocity>` | `c0.5,0.2\n` |
| `f` | Feature request | (none) | `f\n` |
| `h` | Heartbeat | `<interval_ms>` | `h1000\n` |
| `p` | PID controller parameters | `<enabled>,<kp>,<ki>,<kd>` | `p1,2.5,0.0,0.1\n` |
| `m` | Motor control parameters | `<kp>,<kd>,<noControlScale>,<normalControlScale>,<rotationScale>,<velocityBias>,<rotationBias>` or empty to query | `m20.0,4.0,2.0,6.5,6.0,0.75,0.0\n` or `m\n` |
| `s` | Emergency stop | `<enable>` where 1=enable stop, 0=disable stop | `s1\n` (enable stop) or `s0\n` (disable stop) |

## Messages from Robot to Phone

Messages sent from the robot to the phone have the following format:
```
<command><data>\n
```

Where:
- `<command>` is a single character indicating the type of message
- `<data>` is the message payload (format depends on the command)
- `\n` is the newline character (ASCII 10) that marks the end of the message

### Command Types (Robot to Phone)

| Command | Description | Data Format | Example |
|---------|-------------|-------------|---------|
| `e` | Wheel encoder angular velocity | `<angular_velocity>` | `e0.523599\n` |
| `i` | IMU angular velocity | `<angular_velocity>` | `i0.785398\n` |
| `k` | Kalman filter fused angular velocity | `<angular_velocity>` | `k0.654321\n` |
| `p` | Current PWM values | `<left_pwm>,<right_pwm>` | `p128,128\n` |
| `c` | Wheel encoder counts | `<left_count>,<right_count>` | `c1024,1024\n` |
| `h` | Heading adjustment | `<heading_adjustment>` | `h0.123456\n` |
| `ch` | Current heading | `<current_heading>` | `ch1.570796\n` |
| `th` | Target heading | `<target_heading>` | `th1.570796\n` |
| `v` | Normalized linear velocity | `<normalized_velocity>` | `v0.750000\n` |
| `a` | Target angular velocity | `<angular_velocity>` | `a0.523599\n` |
| `f` | Feature response | `<robot_type>:<feature1>:<feature2>:...` | `fDIY_ESP32:ls:\n` |
| `m` | Motor control parameters response | `<kp>,<kd>,<noControlScale>,<normalControlScale>,<rotationScale>,<velocityBias>,<rotationBias>` | `m20.000000,4.000000,2.000000,6.500000,6.000000,0.750000,0.000000\n` |
| `r` | Ready signal | (none) | `r\n` |

## Data Types

- **Linear velocity**: Normalized value between -1.0 and 1.0
- **Angular velocity**: Value in radians per second
- **Heading**: Value in radians
- **PWM values**: Integer values representing motor power (typically -255 to 255)
- **Wheel encoder counts**: Integer values representing wheel rotation counts
- **Interval**: Time in milliseconds
- **Motor control parameters**:
  - **kp**: Proportional gain for PID controller
  - **kd**: Derivative gain for PID controller
  - **noControlScale**: Scale factor for motor adjustments in no-control mode
  - **normalControlScale**: Scale factor for motor adjustments in normal control mode
  - **rotationScale**: Scale factor for motor adjustments in pure rotation mode
  - **velocityBias**: Bias value added to velocity for scaling calculations
  - **rotationBias**: Bias value added to rotation adjustments (typically 0.0)

## Protocol Flow

1. When the robot starts up, it sends a ready signal (`r`) to indicate it's ready to receive commands
2. The phone sends a feature request (`f`) to discover the robot's capabilities
3. The robot responds with its features (`f<features>`)
4. The phone sends control commands (`c`) to control the robot's movement
5. The phone sends heartbeat messages (`h`) periodically to maintain the connection
6. The robot continuously sends sensor data (various commands) to the phone

## Emergency Stop Functionality

The emergency stop command (`s`) provides immediate vehicle stopping without velocity ramping:

- **Enable stop** (`s1\n`): Immediately sets target velocities to zero, activates stop pins (HIGH), and prevents motor movement
- **Disable stop** (`s0\n`): Deactivates stop pins (LOW) and allows normal motor operation to resume

When emergency stop is enabled:
- All motor PWM outputs are set to 0
- Stop pins are set HIGH to engage hardware-level motor stopping
- The vehicle cannot move until stop is disabled
- Target velocities are immediately set to zero without ramping
- **Auto-disable**: If a control message (`c`) arrives after the stop has been enabled for at least 1 second, the stop will be automatically disabled

## Notes

- All floating-point values are sent with 6 decimal places of precision
- The robot will stop if it doesn't receive a heartbeat message within the specified interval
- The PID controller is always enabled in the current implementation
- Sending an empty `m` command (just `m\n`) will return the current motor control parameters
- Motor control parameters can be set via Bluetooth for real-time tuning without recompiling firmware
- Emergency stop provides immediate stopping without velocity ramping for safety
