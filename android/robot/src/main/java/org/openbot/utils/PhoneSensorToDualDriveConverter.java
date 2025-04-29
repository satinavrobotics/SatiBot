package org.openbot.utils;

public class PhoneSensorToDualDriveConverter {
    private final float g = 9.81f;
    private static final float MAX = 1.0f;
    private static final float MIN = -1.0f;
    private static final float FORWARD_SPEED = 0.5f; // Default forward speed
    private static final float WHEEL_BASE = 0.15f; // Same as in VelocityConverter

    public DualDriveValues convert(Float azimuth, Float pitch, Float roll) {
        float linear = 0f;
        float angular = 0f;

        if (inDeadZone(roll)) {
            return new DualDriveValues(0f, 0f);
        }

        // Set linear velocity (forward speed)
        linear = FORWARD_SPEED;

        // Set angular velocity based on pitch
        angular = (pitch / (g / 2)) * 2.0f; // Scale for reasonable turning

        // Convert to left/right wheel speeds for backward compatibility
        float leftSpeed = linear - (angular * WHEEL_BASE / 2);
        float rightSpeed = linear + (angular * WHEEL_BASE / 2);

        return new DualDriveValues(leftSpeed, rightSpeed, linear, angular);
    }

    public boolean inDeadZone(float roll) {
        return isWithin(roll, 0f, 1f);
    }

    private boolean isWithin(Float value, Float desiredValue, Float tolerance) {
        if (value == null || desiredValue == null || tolerance == null) {
            return false;
        }
        return value >= (desiredValue - tolerance) && value <= (desiredValue + tolerance);
    }

    public static class DualDriveValues {
        private float left;
        private float right;
        private float linear;
        private float angular;

        public DualDriveValues(float left, float right) {
            this.left = clean(left);
            this.right = clean(right);
            // Calculate linear and angular from left and right
            this.linear = (left + right) / 2;
            this.angular = (right - left) / WHEEL_BASE;
        }

        public DualDriveValues(float left, float right, float linear, float angular) {
            this.left = clean(left);
            this.right = clean(right);
            this.linear = clean(linear);
            this.angular = clean(angular);
        }

        private float clean(float value) {
            float ret = value;

            if (value > MAX) {
                ret = MAX;
            }
            if (value < MIN) {
                ret = MIN;
            }
            return round(ret, 3);
        }

        private float round(float value, int decimals) {
            float multiplier = 1.0f;
            for (int i = 0; i < decimals; i++) {
                multiplier *= 10;
            }
            return Math.round(value * multiplier) / multiplier;
        }

        public void reset() {
            left = 0f;
            right = 0f;
            linear = 0f;
            angular = 0f;
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
    }
}
