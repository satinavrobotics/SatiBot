// remote_keyboard.js
/*
 * Developed for the OpenBot project (https://openbot.org) by:
 *
 * Ivo Zivkov
 * izivkov@gmail.com
 *
 * Date: Mon Nov 29 2021
 */

export function RemoteKeyboard (commandHandler) {
  const pressedKeys = new Set()
  let pollInterval = null
  
  // Poll interval in milliseconds
  const POLL_RATE = 25
  
  // Velocity scaling constants
  const BASE_VELOCITY = 8.0
  const VELOCITY_SCALE = POLL_RATE / 1000 // Convert to seconds

  const updateMovement = () => {
    if (!pressedKeys.size) return
    
    let linear = 0
    let angular = 0
    
    // Forward/Backward with scaled velocity
    if (pressedKeys.has('w')) linear = BASE_VELOCITY * VELOCITY_SCALE
    if (pressedKeys.has('s')) linear = -BASE_VELOCITY * VELOCITY_SCALE
    
    // Left/Right with scaled velocity
    if (pressedKeys.has('a')) angular = -5*BASE_VELOCITY * VELOCITY_SCALE
    if (pressedKeys.has('d')) angular = 5*BASE_VELOCITY * VELOCITY_SCALE
    
    // If any movement keys are pressed, send command
    if (pressedKeys.has('w') || pressedKeys.has('s') || 
        pressedKeys.has('a') || pressedKeys.has('d')) {
      commandHandler.gamepadCommand(linear, angular)
    }
  }

  // Start polling when first key is pressed
  const startPolling = () => {
    if (!pollInterval) {
      pollInterval = setInterval(updateMovement, POLL_RATE)
    }
  }

  // Stop polling when no keys are pressed
  const stopPolling = () => {
    if (pollInterval) {
      clearInterval(pollInterval)
      pollInterval = null
      commandHandler.gamepadCommand(0, 0) // Stop movement
    }
  }

  this.processKey = keyPress => {
    switch (keyPress?.type) {
      case 'keyup':
        pressedKeys.delete(keyPress.key)
        if (pressedKeys.size === 0) {
          stopPolling()
        }
        break

      case 'keydown':
        // Only handle movement keys here
        if (['w', 'a', 's', 'd'].includes(keyPress.key)) {
          pressedKeys.add(keyPress.key)
          startPolling()
          break
        }
        
        // Handle other commands as before
        switch (keyPress.key) {
          case 'n':
            commandHandler.sendCommand('NOISE')
            break
          case ' ':
            commandHandler.sendCommand('LOGS')
            break
          case 'ArrowRight':
            commandHandler.sendCommand('INDICATOR_RIGHT')
            break
          case 'ArrowLeft':
            commandHandler.sendCommand('INDICATOR_LEFT')
            break
          case 'ArrowUp':
            commandHandler.sendCommand('INDICATOR_STOP')
            break
          case 'ArrowDown':
            commandHandler.sendCommand('NETWORK')
            break
          case 'm':
            commandHandler.sendCommand('DRIVE_MODE')
            break
          case 'q':
            commandHandler.sendCommand('SPEED_DOWN')
            break
          case 'e':
            commandHandler.sendCommand('SPEED_UP')
            break
          case 'Escape':
            commandHandler.reset()
            break
        }
    }
  }

  this.processGamepad = (gamepad) => {
    if (!gamepad) return;
  
    // Apply deadzone to joystick input
    const applyDeadzone = (value, deadzone = 0.05) =>
      Math.abs(value) < deadzone ? 0 : value;
  
    // Helper to clamp values between -1 and 1
    const clamp = (value, min = -1, max = 1) =>
      Math.max(min, Math.min(max, value));
  
    // Read joystick and trigger values
    const leftJoystickX = applyDeadzone(gamepad.axes[0]);
    const rightTrigger = gamepad.buttons[7].value; // RT for forward
    const leftTrigger = gamepad.buttons[6].value;  // LT for backward
  
    // Compute net thrust (positive = forward, negative = backward)
    const netThrust = rightTrigger - leftTrigger;
  
    // Determine steering: invert when moving backwards for intuitive control
    const steeringDirection = netThrust >= 0 ? 1 : -1;
    const steeringFactor = Math.abs(netThrust) > 0.1 ? 0.75 : 0.5;
    const velocity = 50.0;
    const rawSteering = leftJoystickX * steeringFactor * steeringDirection * velocity;
  

    // Compute linear and angular velocities
    const linearVelocity = clamp(netThrust);
    const angularVelocity = clamp(rawSteering, -velocity, velocity);
  
    // Only send commands if there's significant input
    if (
      Math.abs(linearVelocity) > 0.01 ||
      Math.abs(angularVelocity) > 0.01
    ) {
      commandHandler.gamepadCommand(linearVelocity, angularVelocity);
    } else {
      // Stop when no input
      commandHandler.gamepadCommand(0, 0);
    }
  };
}
