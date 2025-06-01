package com.satinavrobotics.satibot.vehicle;

public record Control(float linear, float angular) {
  /**
   * Create a Control object from linear and angular velocity.
   *
   * @param linear  Linear velocity (-1.0 to 1.0)
   * @param angular Angular velocity (-1.0 to 1.0)
   */
  public Control(float linear, float angular) {
    this.linear = Math.max(-1.f, Math.min(1.f, linear));
    this.angular = Math.max(-1.f, Math.min(1.f, angular));
  }
}
