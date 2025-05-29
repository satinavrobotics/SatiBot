// Created by Matthias Mueller - Intel Intelligent Systems Lab - 2020

package com.satinavrobotics.satibot.env;

import android.util.Pair;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.satinavrobotics.satibot.utils.Enums.DriveMode;
import com.satinavrobotics.satibot.vehicle.Control;

public class GameController {
  private DriveMode driveMode;

  // Current controller state
  private float currentLinearVelocity = 0.0f;
  private float currentAngularVelocity = 0.0f;

  // Trigger and joystick state
  private float rightTriggerValue = 0.0f;
  private float leftTriggerValue = 0.0f;
  private float steeringValue = 0.0f;

  // Sensitivity settings
  private float triggerSensitivity = 1.0f; // Lower value = less sensitive (0.0 to 1.0)
  private float steeringSensitivity = 1.0f; // Lower value = less sensitive (0.0 to 1.0)

  // Deadzone settings - ignore inputs below these thresholds
  private float triggerDeadzone = 0.01f; // Values below this are treated as zero
  private float steeringDeadzone = 0.01f; // Values below this are treated as zero

  public GameController(DriveMode driveMode) {
    this.driveMode = driveMode;
  }

  public void setDriveMode(DriveMode mode) {
    driveMode = mode;
  }

  public DriveMode getDriveMode() {
    return driveMode;
  }

  /**
   * Get the current control state based on stored controller values
   * @return Control object with current linear and angular velocities
   */
  public Control getCurrentControl() {
    return new Control(currentLinearVelocity, currentAngularVelocity);
  }

  /**
   * Reset all controller values to zero
   */
  public void resetControllerState() {
    currentLinearVelocity = 0.0f;
    currentAngularVelocity = 0.0f;
    rightTriggerValue = 0.0f;
    leftTriggerValue = 0.0f;
    steeringValue = 0.0f;
  }

  /**
   * Set the trigger sensitivity
   * @param sensitivity Value between 0.0 (least sensitive) and 1.0 (most sensitive)
   */
  public void setTriggerSensitivity(float sensitivity) {
    this.triggerSensitivity = Math.max(0.0f, Math.min(1.0f, sensitivity));
  }

  /**
   * Get the current trigger sensitivity
   * @return Current trigger sensitivity (0.0 to 1.0)
   */
  public float getTriggerSensitivity() {
    return triggerSensitivity;
  }

  /**
   * Set the steering sensitivity
   * @param sensitivity Value between 0.0 (least sensitive) and 1.0 (most sensitive)
   */
  public void setSteeringSensitivity(float sensitivity) {
    this.steeringSensitivity = Math.max(0.0f, Math.min(1.0f, sensitivity));
  }

  /**
   * Get the current steering sensitivity
   * @return Current steering sensitivity (0.0 to 1.0)
   */
  public float getSteeringSensitivity() {
    return steeringSensitivity;
  }

  /**
   * Set the trigger deadzone
   * @param deadzone Value between 0.0 and 1.0 (values below this are ignored)
   */
  public void setTriggerDeadzone(float deadzone) {
    this.triggerDeadzone = Math.max(0.0f, Math.min(0.5f, deadzone));
  }

  /**
   * Get the current trigger deadzone
   * @return Current trigger deadzone (0.0 to 0.5)
   */
  public float getTriggerDeadzone() {
    return triggerDeadzone;
  }

  /**
   * Set the steering deadzone
   * @param deadzone Value between 0.0 and 0.5 (values below this are ignored)
   */
  public void setSteeringDeadzone(float deadzone) {
    this.steeringDeadzone = Math.max(0.0f, Math.min(0.5f, deadzone));
  }

  /**
   * Get the current steering deadzone
   * @return Current steering deadzone (0.0 to 0.5)
   */
  public float getSteeringDeadzone() {
    return steeringDeadzone;
  }

  private static float getCenteredAxis(MotionEvent event, int axis, int historyPos) {

    if (event == null || event.getDevice() == null) return 0;
    final InputDevice.MotionRange range = event.getDevice().getMotionRange(axis, event.getSource());

    // A joystick at rest does not always report an absolute position of
    // (0,0). Use the getFlat() method to determine the range of values
    // bounding the joystick axis center.
    if (range != null) {
      final float flat = range.getFlat();
      final float value =
          historyPos < 0
              ? event.getAxisValue(axis)
              : event.getHistoricalAxisValue(axis, historyPos);

      // Ignore axis values that are within the 'flat' region of the
      // joystick axis center.
      if (Math.abs(value) > flat) {
        return value;
      }
    }
    return 0;
  }

  public Control processButtonInput(KeyEvent event) {
    // Process button input and update controller state
    switch (event.getKeyCode()) {
      case KeyEvent.KEYCODE_BUTTON_A:
        //        Toast.makeText(OpenBotApplication.getContext(), "A recognized",
        // Toast.LENGTH_SHORT).show();
        break;
      case KeyEvent.KEYCODE_BUTTON_B:
        //        Toast.makeText(OpenBotApplication.getContext(), "B recognized",
        // Toast.LENGTH_SHORT).show();
        break;
      case KeyEvent.KEYCODE_BUTTON_Y:
        //        Toast.makeText(OpenBotApplication.getContext(), "Y recognized",
        // Toast.LENGTH_SHORT).show();
        break;
      case KeyEvent.KEYCODE_BUTTON_X:
        //        Toast.makeText(OpenBotApplication.getContext(), "X recognized",
        // Toast.LENGTH_SHORT).show();
        break;
      case KeyEvent.KEYCODE_BUTTON_L1:
        //        Toast.makeText(OpenBotApplication.getContext(), "L1 recognized",
        // Toast.LENGTH_SHORT).show();
        break;
      case KeyEvent.KEYCODE_BUTTON_R1:
        //        Toast.makeText(OpenBotApplication.getContext(), "R1 recognized",
        // Toast.LENGTH_SHORT).show();
        break;
      default:
        //        Toast.makeText(
        //                OpenBotApplication.getContext(),
        //                "Key " + event.getKeyCode() + " not recognized",
        //                Toast.LENGTH_SHORT)
        //            .show();
        break;
    }

    // Return the current control state
    return getCurrentControl();
  }

  /**
   * Update linear and angular velocities based on trigger and steering values
   */
  private void updateVelocitiesFromTriggers() {
    // Apply non-linear response curve and sensitivity to trigger values
    // Using square function for more fine-grained control at lower values
    float adjustedRightTrigger = (rightTriggerValue * rightTriggerValue) * triggerSensitivity;
    float adjustedLeftTrigger = (leftTriggerValue * leftTriggerValue) * triggerSensitivity;

    // Calculate linear velocity from triggers
    currentLinearVelocity = adjustedRightTrigger - adjustedLeftTrigger;

    // Apply sensitivity to steering value
    // Positive steeringValue means turn right
    // Using a more linear response for steering
    currentAngularVelocity = steeringValue * steeringSensitivity;
  }

  public void processJoystickInput(MotionEvent event, int historyPos) {
      // Get right trigger value - these are continuous values between 0.0 and 1.0
      float rightTrigger = getCenteredAxis(event, MotionEvent.AXIS_GAS, historyPos);
      if (rightTrigger == 0) {
        rightTrigger = getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER, historyPos);
      }

      // Normalize trigger values to ensure they're between 0.0 and 1.0
      rightTrigger = Math.max(0.0f, Math.min(1.0f, rightTrigger));

      // Apply deadzone - values below deadzone are treated as zero
      if (rightTrigger < triggerDeadzone) {
          rightTrigger = 0.0f;
      } else {
          // Rescale the remaining range to 0.0-1.0
          rightTrigger = (rightTrigger - triggerDeadzone) / (1.0f - triggerDeadzone);
      }

      // Store the right trigger value
      rightTriggerValue = rightTrigger;

      // Get left trigger value - these are continuous values between 0.0 and 1.0
      float leftTrigger = getCenteredAxis(event, MotionEvent.AXIS_BRAKE, historyPos);
      if (leftTrigger == 0) {
        leftTrigger = getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER, historyPos);
      }

      // Normalize trigger values to ensure they're between 0.0 and 1.0
      leftTrigger = Math.max(0.0f, Math.min(1.0f, leftTrigger));

      // Apply deadzone - values below deadzone are treated as zero
      if (leftTrigger < triggerDeadzone) {
          leftTrigger = 0.0f;
      } else {
          // Rescale the remaining range to 0.0-1.0
          leftTrigger = (leftTrigger - triggerDeadzone) / (1.0f - triggerDeadzone);
      }

      // Store the left trigger value
      leftTriggerValue = leftTrigger;

      // Calculate the steering magnitude by
      // using the input value from one of these physical controls:
      // the left control stick, hat axis, or the right control stick.
      float steeringOffset = getCenteredAxis(event, MotionEvent.AXIS_X, historyPos);
      if (steeringOffset == 0) {
        steeringOffset = getCenteredAxis(event, MotionEvent.AXIS_HAT_X, historyPos);
      }
      if (steeringOffset == 0) {
        steeringOffset = getCenteredAxis(event, MotionEvent.AXIS_Z, historyPos);
      }

      // Apply deadzone to steering
      if (Math.abs(steeringOffset) < steeringDeadzone) {
          steeringOffset = 0.0f;
      } else {
          // Preserve the sign while rescaling
          float sign = Math.signum(steeringOffset);
          float magnitude = Math.abs(steeringOffset);

          // Rescale the remaining range to 0.0-1.0
          magnitude = (magnitude - steeringDeadzone) / (1.0f - steeringDeadzone);

          // Reapply the sign
          steeringOffset = sign * magnitude;
      }

      // Store the steering value
      steeringValue = steeringOffset;

      // Update velocities based on trigger and steering values
      updateVelocitiesFromTriggers();

      // Return the current control state
    getCurrentControl();
  }

  public static Pair<Float, Float> processJoystickInputLeft(MotionEvent event, int historyPos) {

    // Calculate the horizontal distance to move by
    // using the input value from one of these physical controls:
    // the left control stick, hat axis, or the right control stick.
    float x = getCenteredAxis(event, MotionEvent.AXIS_X, historyPos);

    // Calculate the vertical distance to move by
    // using the input value from one of these physical controls:
    // the left control stick, hat switch, or the right control stick.
    float y = getCenteredAxis(event, MotionEvent.AXIS_Y, historyPos);

    return new Pair<>(x, y);
  }

  public static Pair<Float, Float> processJoystickInputRight(MotionEvent event, int historyPos) {

    // Calculate the horizontal distance to move by
    // using the input value from one of these physical controls:
    // the left control stick, hat axis, or the right control stick.
    float x = getCenteredAxis(event, MotionEvent.AXIS_Z, historyPos);

    // Calculate the vertical distance to move by
    // using the input value from one of these physical controls:
    // the left control stick, hat switch, or the right control stick.
    float y = getCenteredAxis(event, MotionEvent.AXIS_RZ, historyPos);

    return new Pair<>(x, y);
  }
}
