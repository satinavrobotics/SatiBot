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
  this.processKey = keyPress => {
    switch (keyPress?.type) {
      case 'keyup':
        // keep track of what keys are currently pressed
        pressedKeys.delete(keyPress.key)
        if (['w', 's'].includes(keyPress.key)) {
          commandHandler.reset()
          break
        }

        if (['a', 'd'].includes(keyPress.key)) {
          if (pressedKeys.has('w')) {
            commandHandler.goForward()
            break
          }
          if (pressedKeys.has('s')) {
            commandHandler.goBackward()
            break
          }
          commandHandler.reset()
        }
        break

      case 'keydown':
        pressedKeys.add(keyPress.key)
        switch (keyPress.key) {
          case 'w':
            if (pressedKeys.has('a')) {
              commandHandler.forwardLeft()
              break
            }
            if (pressedKeys.has('d')) {
              commandHandler.forwardRight()
              break
            }
            commandHandler.goForward()
            break
          case 's':
            if (pressedKeys.has('a')) {
              commandHandler.backwardLeft()
              break
            }
            if (pressedKeys.has('d')) {
              commandHandler.backwardRight()
              break
            }
            commandHandler.goBackward()
            break
          case 'a':
            if (pressedKeys.has('w')) {
              commandHandler.forwardLeft()
            } else if (pressedKeys.has('s')) {
              commandHandler.backwardLeft()
            } else {
              commandHandler.rotateLeft()
            }
            break
          case 'd':
            if (pressedKeys.has('w')) {
              commandHandler.forwardRight()
            } else if (pressedKeys.has('s')) {
              commandHandler.backwardRight()
            } else {
              commandHandler.rotateRight()
            }
            break
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
    const applyDeadzone = (value, deadzone = 0.05) => {
      return Math.abs(value) < deadzone ? 0 : value;
    };
    
    // Get joystick and trigger values
    const leftJoystickX = applyDeadzone(gamepad.axes[0]);
    const rightTrigger = gamepad.buttons[7].value; // Right trigger (RT)
    const leftTrigger = gamepad.buttons[6].value;  // Left trigger (LT)
    
    // Calculate thrust based on trigger values
    const forwardThrust = rightTrigger; // RT for forward
    const backwardThrust = leftTrigger; // LT for backward
    
    // Net thrust (positive for forward, negative for backward)
    const netThrust = forwardThrust - backwardThrust;
    
    // Calculate steering based on left joystick horizontal axis
    // Scale steering effect based on thrust for better control
    const steeringFactor = Math.abs(netThrust) > 0.1 ? 0.5 : 1.0;
    const steering = leftJoystickX * steeringFactor;
    
    // Calculate left and right drive values with proper clamping
    let leftDrive = netThrust + steering;
    let rightDrive = netThrust - steering;
    
    // Normalize values to ensure they stay within [-1, 1] range
    const max = Math.max(1, Math.abs(leftDrive), Math.abs(rightDrive));
    if (max > 1) {
      leftDrive /= max;
      rightDrive /= max;
    }
    
    // Only send commands if there's actual input
    if (Math.abs(leftDrive) > 0.01 || Math.abs(rightDrive) > 0.01) {
      commandHandler.gamepadCommand(leftDrive, rightDrive);
    } else {
      // Send zero command to stop when no input
      commandHandler.gamepadCommand(0, 0);
    }
  };
}
