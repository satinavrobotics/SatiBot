package com.satinavrobotics.satibot.navigation.controller;

import com.satinavrobotics.satibot.vehicle.pd.ControllerParameters;

import timber.log.Timber;

/**
 * Rule-based waypoint controller implementation.
 * This implements the original control logic from NavigationUtils.
 */
public class RuleBasedWaypointController implements WaypointController {
    private static final String TAG = RuleBasedWaypointController.class.getSimpleName();

    private final ControllerParameters parameters;

    public RuleBasedWaypointController(ControllerParameters parameters) {
        this.parameters = parameters;
        Timber.d("Initialized RuleBasedWaypointController with parameters: %s", parameters);
    }

    @Override
    public float calculateTurningAngularVelocity(float headingError, float maxTurnSpeed, float deltaTime) {
        // When turning in place toward a waypoint, ignore cost-based navigation
        // and turn directly toward the target. Cost-based navigation should only
        // be used when moving forward to avoid obstacles.

        // Use direct rule-based logic for turning toward waypoint
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));

        // Calculate turn speed based on angle difference (original logic from NavigationUtils)
        float turnSpeed = Math.min(maxTurnSpeed, headingErrorDegrees / 90.0f * maxTurnSpeed * parameters.turnSpeedScale);
        turnSpeed = Math.max(parameters.minTurnSpeed, turnSpeed); // Apply minimum turn speed

        // Determine turn direction: positive headingError means turn right, negative means turn left
        float angularVelocity = headingError > 0 ? turnSpeed : -turnSpeed;

        Timber.d("Direct waypoint turning: error=%.1f°, turnSpeed=%.2f, angular=%.2f",
                headingErrorDegrees, turnSpeed, angularVelocity);

        return angularVelocity;
    }

    @Override
    public float calculateCourseCorrection(float headingError, float maxCorrectionStrength, float thresholdDegrees, float deltaTime) {
        // Use simple rule-based logic for course correction
        float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));

        // Apply threshold check (original logic from NavigationUtils)
        if (headingErrorDegrees > thresholdDegrees) {
            float correctionStrength = Math.min(maxCorrectionStrength,
                                               headingErrorDegrees / 45.0f * maxCorrectionStrength * parameters.correctionScale);
            float angularVelocity = headingError > 0 ? correctionStrength : -correctionStrength;

            Timber.d("Rule-based correction: error=%.1f°, strength=%.2f, angular=%.2f",
                    headingErrorDegrees, correctionStrength, angularVelocity);

            return angularVelocity;
        }

        return 0.0f;
    }

    @Override
    public void reset() {
        // Rule-based controller has no state to reset
        Timber.d("Rule-based controller reset (no state to clear)");
    }

    @Override
    public String getControllerName() {
        return "Rule-Based";
    }
}
