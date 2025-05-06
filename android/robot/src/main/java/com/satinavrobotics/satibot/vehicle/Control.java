package com.satinavrobotics.satibot.vehicle;

import com.satinavrobotics.satibot.utils.VelocityConverter;

public class Control {
  private final float left;
  private final float right;
  private final float linear;
  private final float angular;

  /**
   * Create a Control object from left and right wheel speeds.
   *
   * @param left Left wheel speed (-1.0 to 1.0)
   * @param right Right wheel speed (-1.0 to 1.0)
   */
  public Control(float left, float right) {
    this.left = Math.max(-1.f, Math.min(1.f, left));
    this.right = Math.max(-1.f, Math.min(1.f, right));

    // Calculate linear and angular velocity from wheel speeds
    float[] velocities = VelocityConverter.toVelocities(left, right);
    this.linear = velocities[0];
    this.angular = velocities[1];
  }

  /**
   * Create a Control object from linear and angular velocity.
   *
   * @param linear Linear velocity (-1.0 to 1.0)
   * @param angular Angular velocity (-1.0 to 1.0)
   * @param isVelocity Flag to distinguish from the left/right constructor
   */
  public Control(float linear, float angular, boolean isVelocity) {
    this.linear = Math.max(-1.f, Math.min(1.f, linear));
    this.angular = Math.max(-1.f, Math.min(1.f, angular));

    // Calculate wheel speeds from linear and angular velocity
    float[] wheelSpeeds = VelocityConverter.toWheelSpeeds(linear, angular);
    this.left = wheelSpeeds[0];
    this.right = wheelSpeeds[1];
  }

  public float getLeft() {
    return left;
  }

  public float getRight() {
    return right;
  }

  public float getLinear() {
    return linear;
  }

  public float getAngular() {
    return angular;
  }

  public Control mirror() {
    return new Control(this.right, this.left);
  }
}
