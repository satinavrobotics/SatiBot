package com.satinavrobotics.satibot.vehicle.pd;

/**
 * Data class containing parameters for waypoint navigation controllers
 */
public class ControllerParameters {
    
    // PD Controller parameters for turning
    public final float turningKp;           // Proportional gain for turning
    public final float turningKd;           // Derivative gain for turning
    
    // PD Controller parameters for course correction
    public final float correctionKp;        // Proportional gain for course correction
    public final float correctionKd;        // Derivative gain for course correction
    
    // Rule-based controller parameters
    public final float minTurnSpeed;        // Minimum turn speed (0.0 to 1.0)
    public final float turnSpeedScale;      // Scale factor for turn speed calculation
    public final float correctionScale;     // Scale factor for course correction
    
    /**
     * Constructor for PD controller parameters
     */
    public ControllerParameters(float turningKp, float turningKd, float correctionKp, float correctionKd) {
        this.turningKp = turningKp;
        this.turningKd = turningKd;
        this.correctionKp = correctionKp;
        this.correctionKd = correctionKd;
        
        // Default rule-based parameters (not used for PD controller)
        this.minTurnSpeed = 0.1f;
        this.turnSpeedScale = 1.0f;
        this.correctionScale = 1.0f;
    }
    
    /**
     * Constructor for rule-based controller parameters
     */
    public ControllerParameters(float minTurnSpeed, float turnSpeedScale, float correctionScale) {
        this.minTurnSpeed = minTurnSpeed;
        this.turnSpeedScale = turnSpeedScale;
        this.correctionScale = correctionScale;
        
        // Default PD parameters (not used for rule-based controller)
        this.turningKp = 1.0f;
        this.turningKd = 0.1f;
        this.correctionKp = 0.5f;
        this.correctionKd = 0.05f;
    }
    
    /**
     * Constructor with all parameters (for flexibility)
     */
    public ControllerParameters(float turningKp, float turningKd, float correctionKp, float correctionKd,
                               float minTurnSpeed, float turnSpeedScale, float correctionScale) {
        this.turningKp = turningKp;
        this.turningKd = turningKd;
        this.correctionKp = correctionKp;
        this.correctionKd = correctionKd;
        this.minTurnSpeed = minTurnSpeed;
        this.turnSpeedScale = turnSpeedScale;
        this.correctionScale = correctionScale;
    }
    
    /**
     * Create default parameters for PD controller
     */
    public static ControllerParameters createDefaultPD() {
        return new ControllerParameters(
            2.0f,   // turningKp - aggressive proportional gain for turning
            0.3f,   // turningKd - moderate derivative gain for turning
            1.0f,   // correctionKp - moderate proportional gain for course correction
            0.1f    // correctionKd - small derivative gain for course correction
        );
    }
    
    /**
     * Create default parameters for rule-based controller
     */
    public static ControllerParameters createDefaultRuleBased() {
        return new ControllerParameters(
            0.1f,   // minTurnSpeed - minimum turn speed
            1.0f,   // turnSpeedScale - scale factor for turn speed
            1.0f    // correctionScale - scale factor for course correction
        );
    }
    
    @Override
    public String toString() {
        return String.format("ControllerParameters{turningKp=%.2f, turningKd=%.2f, correctionKp=%.2f, correctionKd=%.2f, " +
                           "minTurnSpeed=%.2f, turnSpeedScale=%.2f, correctionScale=%.2f}",
                           turningKp, turningKd, correctionKp, correctionKd, minTurnSpeed, turnSpeedScale, correctionScale);
    }
}
