package com.satinavrobotics.satibot.navigation;

/**
 * Represents a control command with linear and angular velocities.
 * This is the output of navigation strategies and input to the vehicle controller.
 */
public class ControlCommand {
    private final float linearVelocity;
    private final float angularVelocity;
    private final boolean isStop;
    
    /**
     * Create a control command with specified velocities
     * 
     * @param linearVelocity Linear velocity (-1.0 to 1.0)
     * @param angularVelocity Angular velocity (-1.0 to 1.0)
     */
    public ControlCommand(float linearVelocity, float angularVelocity) {
        this.linearVelocity = Math.max(-1.0f, Math.min(1.0f, linearVelocity));
        this.angularVelocity = Math.max(-1.0f, Math.min(1.0f, angularVelocity));
        this.isStop = (linearVelocity == 0.0f && angularVelocity == 0.0f);
    }
    
    /**
     * Create a stop command
     */
    public static ControlCommand stop() {
        return new ControlCommand(0.0f, 0.0f);
    }
    
    /**
     * Create a forward command
     * 
     * @param speed Linear speed (0.0 to 1.0)
     */
    public static ControlCommand forward(float speed) {
        return new ControlCommand(Math.abs(speed), 0.0f);
    }
    
    /**
     * Create a turn command
     * 
     * @param angularSpeed Angular speed (-1.0 to 1.0), positive = right, negative = left
     */
    public static ControlCommand turn(float angularSpeed) {
        return new ControlCommand(0.0f, angularSpeed);
    }
    
    /**
     * Create a move command with both linear and angular components
     * 
     * @param linearSpeed Linear speed (-1.0 to 1.0)
     * @param angularSpeed Angular speed (-1.0 to 1.0)
     */
    public static ControlCommand move(float linearSpeed, float angularSpeed) {
        return new ControlCommand(linearSpeed, angularSpeed);
    }
    
    public float getLinearVelocity() {
        return linearVelocity;
    }
    
    public float getAngularVelocity() {
        return angularVelocity;
    }
    
    public boolean isStop() {
        return isStop;
    }
    
    @Override
    public String toString() {
        return String.format("ControlCommand{linear=%.3f, angular=%.3f, stop=%s}", 
                           linearVelocity, angularVelocity, isStop);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ControlCommand that = (ControlCommand) obj;
        return Float.compare(that.linearVelocity, linearVelocity) == 0 &&
               Float.compare(that.angularVelocity, angularVelocity) == 0;
    }
    
    @Override
    public int hashCode() {
        int result = Float.hashCode(linearVelocity);
        result = 31 * result + Float.hashCode(angularVelocity);
        return result;
    }
}
