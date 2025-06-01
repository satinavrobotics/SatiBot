package com.satinavrobotics.satibot.robot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;

import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.databinding.FragmentBluetoothControlBinding;

import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.SensorReader;

import java.util.Locale;

public class BluetoothControlFragment extends ControlsFragment {
    private FragmentBluetoothControlBinding binding;

    // Control update interval in milliseconds
    private static final int CONTROL_UPDATE_INTERVAL = 50;

    private Handler controlUpdateHandler;
    private Runnable controlUpdateRunnable;
    private boolean isControlUpdateRunning = false;

    // Control values
    private float currentSteeringValue = 0.0f;
    private float currentVelocityValue = 0.0f;
    private float normalizedLinearVelocity = 0.0f;
    private float targetAngularVelocity = 0.0f;

    // Touch tracking variables
    private float initialTouchX = 0.0f;
    private float initialTouchY = 0.0f;
    private boolean isLeftJoystickActive = false;
    private boolean isRightJoystickActive = false;

    // Haptic feedback
    private Vibrator vibrator;
    private static final float HAPTIC_THRESHOLD = 0.4f; // Lower threshold for haptic feedback (0.0 to 1.0)
    private static final float HAPTIC_MAX_THRESHOLD = 0.9f; // Upper threshold for maximum haptic feedback
    private static final long HAPTIC_DURATION = 500; // Duration of continuous vibration in milliseconds
    private static final long HAPTIC_COOLDOWN = 150; // Shorter cooldown for more continuous feel
    private static final long HAPTIC_COOLDOWN_STEERING = 600; // Longer cooldown for steering to reduce vibration frequency
    private static final int HAPTIC_MIN_AMPLITUDE = 30; // Minimum amplitude for haptic feedback
    private static final int HAPTIC_MAX_AMPLITUDE = 100; // Maximum amplitude for haptic feedback
    private static final int HAPTIC_MAX_AMPLITUDE_STEERING = 50; // Reduced maximum amplitude for steering feedback
    private static final String PREF_HAPTIC_ENABLED = "haptic_feedback_enabled"; // SharedPreferences key
    private boolean hapticFeedbackEnabled = true; // Default to enabled
    private long lastLeftHapticTime = 0;
    private long lastRightHapticTime = 0;
    private boolean isLeftVibrating = false;
    private boolean isRightVibrating = false;
    private float lastLeftIntensity = 0.0f;
    private float lastRightIntensity = 0.0f;

    // --- Stop/Unlock button logic fields ---
    private Handler stopHandler = new Handler(Looper.getMainLooper());
    private Runnable stopReleaseRunnable;
    private boolean isLongPress = false;
    private static final long LONG_PRESS_THRESHOLD = 800; // ms
    private static final long STOP_RELEASE_DELAY = 5000; // ms


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentBluetoothControlBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Force landscape orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Initialize sensor reader
        SensorReader sensorReader = SensorReader.getInstance();
        sensorReader.init(requireContext());

        // Initialize vibrator service
        initializeVibrator();

        // Load haptic feedback setting
        loadHapticSetting();

        initializeControls();
        setupConnectionToggles();
        setupWheelControls();
        setupHapticToggle();

        // Make wheel controls visible
        binding.wheelControlsLayout.setVisibility(View.VISIBLE);

        // Initialize joystick positions after layout is ready
        view.post(() -> {
            if (binding != null) {
                // Initialize joystick positions
                binding.forwardSeekBar.setProgress(50);
                binding.rightSeekBar.setProgress(50);
            }
        });

        // Add a global layout listener to position thumbs after layout is complete
        binding.leftJoystickContainer.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (binding != null) {
                    // Position both joystick thumbs at their center positions
                    updateAccelerationJoystickPosition(50);
                    updateSteeringJoystickPosition(50);

                    // Ensure the thumbs are properly centered
                    binding.accelerationJoystickThumb.post(() -> {
                        if (binding != null) {
                            // Re-center after the layout is fully measured
                            updateAccelerationJoystickPosition(50);
                        }
                    });

                    binding.steeringJoystickThumb.post(() -> {
                        if (binding != null) {
                            // Re-center after the layout is fully measured
                            updateSteeringJoystickPosition(50);
                        }
                    });

                    // Remove the listener to prevent multiple calls
                    binding.leftJoystickContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        });

        // Start control updates for wheel mode
        startControlUpdates();

        // Then initialize heading info display
        updateHeadingInfo();
    }

    private void setupConnectionToggles() {
        binding.usbToggle.setChecked(vehicle.isUsbConnected());
        binding.bleToggle.setChecked(vehicle.bleConnected());

        binding.usbToggle.setOnClickListener(
            v -> {
                binding.usbToggle.setChecked(vehicle.isUsbConnected());
                Navigation.findNavController(requireView()).navigate(R.id.open_usb_fragment);
            });

        binding.bleToggle.setOnClickListener(
            v -> {
                binding.bleToggle.setChecked(vehicle.bleConnected());
                Navigation.findNavController(requireView()).navigate(R.id.open_bluetooth_fragment);
            });
    }

    /**
     * Sets up the haptic feedback toggle button
     */
    private void setupHapticToggle() {
        // Set initial state based on saved preference
        binding.hapticToggle.setChecked(hapticFeedbackEnabled);

        // Set up haptic toggle listener
        binding.hapticToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            hapticFeedbackEnabled = isChecked;
            saveHapticSetting();

            // Provide feedback when enabling
            if (isChecked) {
                // Give a short vibration to confirm haptics are on
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        VibrationEffect effect = VibrationEffect.createOneShot(100, 50);
                        vibrator.vibrate(effect);
                    } else {
                        vibrator.vibrate(100);
                    }
                }
            }
        });
    }

    /**
     * Loads the haptic feedback setting from SharedPreferences
     */
    private void loadHapticSetting() {
        SharedPreferences prefs = requireActivity().getSharedPreferences("robot_settings", Context.MODE_PRIVATE);
        hapticFeedbackEnabled = prefs.getBoolean(PREF_HAPTIC_ENABLED, true); // Default to enabled
    }

    /**
     * Saves the haptic feedback setting to SharedPreferences
     */
    private void saveHapticSetting() {
        SharedPreferences prefs = requireActivity().getSharedPreferences("robot_settings", Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_HAPTIC_ENABLED, hapticFeedbackEnabled).apply();
    }


    private void initializeControls() {
        // Initialize control buttons here
        handleDriveCommand();
    }

    @Override
    protected void processUSBData(String data) {
        // Check if binding, vehicle, or data is null
        if (binding == null || vehicle == null || data == null) {
            return;
        }

        // Check if the data is a heading-related message
        if (data.length() > 1) {
            char header = data.charAt(0);
            String body = data.substring(1);

            switch (header) {
                case 'h': // Heading adjustment
                    if (isNumeric(body)) {
                        vehicle.setHeadingAdjustment(Float.parseFloat(body));
                        updateHeadingInfo();
                    }
                    break;

                case 'c': // Current heading (starts with 'ch')
                    if (body.startsWith("h") && body.length() > 1) {
                        String value = body.substring(1);
                        if (isNumeric(value)) {
                            vehicle.setCurrentHeading(Float.parseFloat(value));
                            updateHeadingInfo();
                        }
                    }
                    break;

                case 't': // Target heading (starts with 'th')
                    if (body.startsWith("h") && body.length() > 1) {
                        String value = body.substring(1);
                        if (isNumeric(value)) {
                            vehicle.setTargetHeading(Float.parseFloat(value));
                            updateHeadingInfo();
                        }
                    }
                    break;

                case 'n': // Normalized linear velocity
                    if (isNumeric(body)) {
                        normalizedLinearVelocity = Float.parseFloat(body);
                        updateNormalizedLinearVelocityInfo();
                    }
                    break;

                case 'a': // Target angular velocity
                    if (isNumeric(body)) {
                        targetAngularVelocity = Float.parseFloat(body);
                        updateTargetAngularVelocityInfo();
                    }
                    break;

                case 'p': // PWM values no longer displayed
                    break;
            }
        }

        // Update speed info as before
        binding.speedInfo.setText(
            getString(
                R.string.speedInfo,
                String.format(
                    Locale.US, "%3.0f,%3.0f", vehicle.getLeftWheelRpm(), vehicle.getRightWheelRpm())));
    }

    /**
     * Updates the heading information display and chart
     */
    private void updateHeadingInfo() {
        // Check if binding or vehicle is null
        if (binding == null || vehicle == null) {
            return;
        }

        // Update text displays
        float headingAdjustment = vehicle.getHeadingAdjustment();
        float currentHeading = vehicle.getCurrentHeading();
        float targetHeading = vehicle.getTargetHeading();

        binding.headingAdjustmentInfo.setText(
            String.format(Locale.US, "H: %.2f", headingAdjustment));
        binding.currentHeadingInfo.setText(
            String.format(Locale.US, "CH: %.2f", currentHeading));
        binding.targetHeadingInfo.setText(
            String.format(Locale.US, "TH: %.2f", targetHeading));
    }

    /**
     * Updates the normalized linear velocity information display
     */
    private void updateNormalizedLinearVelocityInfo() {
        // Check if binding is null
        if (binding == null) {
            return;
        }

        // Update text display with 2 decimal places for better readability
        binding.normalizedLinearVelocityInfo.setText(
            String.format(Locale.US, "NLV: %.2f", normalizedLinearVelocity));
    }

    /**
     * Updates the target angular velocity information display
     */
    private void updateTargetAngularVelocityInfo() {
        // Check if binding is null
        if (binding == null) {
            return;
        }

        // Update text display with 2 decimal places for better readability
        binding.targetAngularVelocityInfo.setText(
            String.format(Locale.US, "TAV: %.2f", targetAngularVelocity));
    }

    /**
     * Checks if a string is a valid numeric value
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            Float.parseFloat(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    protected void processControllerKeyData(String commandType) {
        if (commandType.equals(Constants.CMD_DRIVE)) {
            handleDriveCommand();
        }
    }

    private void handleDriveCommand() {
        // Check if binding or vehicle is null
        if (binding == null || vehicle == null) {
            return;
        }

        // Get linear and angular velocity
        float linear = vehicle.getLinearVelocity();
        float angular = vehicle.getAngularVelocity();

        binding.controlInfo.setText(
            String.format(Locale.US, "L:%.0f,A:%.0f", linear, angular));

        // Update heading information
        updateHeadingInfo();
    }


    private void setupWheelControls() {
        // Set up acceleration joystick (left side) with touch listener
        binding.leftJoystickContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Record the initial touch position as the reference point
                    initialTouchY = event.getY();
                    isLeftJoystickActive = true;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isLeftJoystickActive) {
                        // Calculate the relative vertical movement from the initial touch point
                        float deltaY = initialTouchY - event.getY();

                        // Get container height for normalization
                        int containerHeight = binding.leftJoystickContainer.getHeight();

                        // Normalize to -1.0 to 1.0 range with a scaling factor for sensitivity
                        // Negative deltaY means moving down (backward), positive means moving up (forward)
                        float scalingFactor = 2.0f; // Adjust this for sensitivity
                        float normalizedDeltaY = (deltaY / containerHeight) * scalingFactor;

                        // Clamp the value between -1.0 and 1.0
                        normalizedDeltaY = Math.max(-1.0f, Math.min(1.0f, normalizedDeltaY));

                        // Update the velocity value
                        currentVelocityValue = normalizedDeltaY;

                        // Convert to progress value (0-100) for the joystick visualization
                        int progress = (int)((normalizedDeltaY * 50) + 50);

                        // Update joystick thumb position
                        updateAccelerationJoystickPosition(progress);

                        // Update vehicle controls
                        updateDirectionalControls();
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Reset to center when touch is released
                    isLeftJoystickActive = false;
                    currentVelocityValue = 0;
                    updateAccelerationJoystickPosition(50);
                    updateDirectionalControls();
                    return true;
            }
            return false;
        });

        // Set up steering joystick (right side) with touch listener
        binding.rightJoystickContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Record the initial touch position as the reference point
                    initialTouchX = event.getX();
                    isRightJoystickActive = true;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isRightJoystickActive) {
                        // Calculate the relative horizontal movement from the initial touch point
                        float deltaX = event.getX() - initialTouchX;

                        // Get container width for normalization
                        int containerWidth = binding.rightJoystickContainer.getWidth();

                        // Normalize to -1.0 to 1.0 range with a scaling factor for sensitivity
                        // Negative deltaX means moving left, positive means moving right
                        float scalingFactor = 2.0f; // Adjust this for sensitivity
                        float normalizedDeltaX = (deltaX / containerWidth) * scalingFactor;

                        // Clamp the value between -1.0 and 1.0
                        normalizedDeltaX = Math.max(-1.0f, Math.min(1.0f, normalizedDeltaX));

                        // Update the steering value
                        currentSteeringValue = normalizedDeltaX;

                        // Convert to progress value (0-100) for the joystick visualization
                        int progress = (int)((normalizedDeltaX * 50) + 50);

                        // Update joystick thumb position
                        updateSteeringJoystickPosition(progress);

                        // Update vehicle controls
                        updateDirectionalControls();
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Reset to center when touch is released
                    isRightJoystickActive = false;
                    currentSteeringValue = 0;
                    updateSteeringJoystickPosition(50);
                    updateDirectionalControls();
                    return true;
            }
            return false;
        });

        // Initialize joystick positions with center values (50)
        binding.forwardSeekBar.setProgress(50);
        binding.rightSeekBar.setProgress(50);
        updateAccelerationJoystickPosition(50);
        updateSteeringJoystickPosition(50);

        // Set up emergency stop button with long/short press logic
        binding.centerStopButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPress = false;
                    // Provide haptic feedback for emergency stop
                    vibrateEmergencyStop();
                    // Use emergencyStop interface
                    if (vehicle != null) vehicle.emergencyStop(true);
                    // Start long press detection
                    stopHandler.postDelayed(() -> {
                        isLongPress = true;
                        binding.unlockButton.setVisibility(View.VISIBLE);
                    }, LONG_PRESS_THRESHOLD);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopHandler.removeCallbacksAndMessages(null);
                    if (!isLongPress) {
                        // Schedule release after 5 seconds
                        if (stopReleaseRunnable != null) stopHandler.removeCallbacks(stopReleaseRunnable);
                        stopReleaseRunnable = () -> { if (vehicle != null) vehicle.emergencyStop(false); };
                        stopHandler.postDelayed(stopReleaseRunnable, STOP_RELEASE_DELAY);
                    }
                    return true;
            }
            return false;
        });

        binding.unlockButton.setOnClickListener(v -> {
            if (vehicle != null) vehicle.emergencyStop(false);
            binding.unlockButton.setVisibility(View.GONE);
        });

        // Update connection status
        updateConnectionStatus();
    }

    /**
     * Updates the connection status (now hidden but kept for callback compatibility)
     */
    private void updateConnectionStatus() {
        // Connection status is now hidden, but we keep the method for compatibility
    }

    /**
     * Updates the position of the acceleration joystick thumb based on seekbar progress
     */
    private void updateAccelerationJoystickPosition(int progress) {
        if (binding == null) return;

        // Calculate vertical position (progress 0 = bottom, 100 = top)
        // Note: Since the SeekBar is rotated 270 degrees, higher progress means moving up
        float verticalOffset = (progress - 50) / 50.0f; // -1.0 to 1.0

        // Get the container dimensions
        int containerHeight = binding.leftJoystickContainer.getHeight();
        int containerWidth = binding.leftJoystickContainer.getWidth();
        int thumbSize = binding.accelerationJoystickThumb.getLayoutParams().height;

        // If container dimensions are 0, view might not be laid out yet
        if (containerHeight == 0) {
            containerHeight = 150; // Default from layout
        }
        if (containerWidth == 0) {
            containerWidth = 150; // Default from layout
        }

        // If thumb size is 0, use the default from layout
        if (thumbSize == 0) {
            thumbSize = 50; // Default from layout
        }

        // Calculate the usable vertical range (accounting for thumb size)
        double usableHeight = containerHeight - thumbSize;

        // For linear vertical movement:
        // - When verticalOffset is -1.0, thumb should be at the bottom
        // - When verticalOffset is 0.0, thumb should be in the middle
        // - When verticalOffset is 1.0, thumb should be at the top

        // Calculate the new position
        // Center horizontally in the container
        int newX = (containerWidth - thumbSize) / 2;

        // Map verticalOffset (-1.0 to 1.0) to vertical position
        // Start from the center and move up or down based on the offset
        int centerY = containerHeight / 2;
        int newY = centerY - (int)((usableHeight / 2) * verticalOffset);

        // Adjust to position the top-left corner of the thumb
        newY -= thumbSize / 2;

        // Apply the new position - strictly vertical movement
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.accelerationJoystickThumb.getLayoutParams();
        params.leftMargin = newX;
        params.topMargin = newY;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        binding.accelerationJoystickThumb.setLayoutParams(params);

        // Update arrow colors based on direction
        if (verticalOffset > 0.1) {
            // Forward
            binding.accelerationArrowUp.setColorFilter(getResources().getColor(android.R.color.white));
            binding.accelerationArrowDown.setColorFilter(getResources().getColor(R.color.satiBotRed));
        } else if (verticalOffset < -0.1) {
            // Backward
            binding.accelerationArrowUp.setColorFilter(getResources().getColor(R.color.satiBotRed));
            binding.accelerationArrowDown.setColorFilter(getResources().getColor(android.R.color.white));
        } else {
            // Neutral
            binding.accelerationArrowUp.setColorFilter(getResources().getColor(R.color.satiBotRed));
            binding.accelerationArrowDown.setColorFilter(getResources().getColor(R.color.satiBotRed));
        }
    }

    /**
     * Updates the position of the steering joystick thumb based on seekbar progress
     */
    private void updateSteeringJoystickPosition(int progress) {
        if (binding == null) return;

        // Calculate horizontal position (progress 0 = left, 100 = right)
        float horizontalOffset = (progress - 50) / 50.0f; // -1.0 to 1.0

        // Get the container dimensions
        int containerSize = binding.rightJoystickContainer.getWidth();
        int thumbSize = binding.steeringJoystickThumb.getLayoutParams().width;

        // If container size is 0, view might not be laid out yet
        if (containerSize == 0) {
            containerSize = 150; // Default from layout
        }

        // If thumb size is 0, use the default from layout
        if (thumbSize == 0) {
            thumbSize = 50; // Default from layout
        }

        // Calculate the center point of the container
        int centerX = containerSize / 2;
        int centerY = containerSize / 2;

        // Calculate the radius of the circle (distance from center to where thumb should be)
        // Subtract half the thumb size to ensure the thumb's center stays on the circle
        double radius = (containerSize / 2) - (thumbSize / 2);

        // For horizontal movement along a circle:
        // We'll use a 180-degree arc (from -90° at left to +90° at right)
        double angleInDegrees = horizontalOffset * 90; // Maps -1.0...1.0 to -90°...90°
        double angleInRadians = Math.toRadians(angleInDegrees);

        // Calculate position using parametric equation of a circle
        // sin(angle) gives us the x-component, cos(angle) gives us the y-component
        // We negate cos because in screen coordinates, y increases downward
        int newX = centerX + (int)(radius * Math.sin(angleInRadians));
        int newY = centerY - (int)(radius * Math.cos(angleInRadians));

        // Adjust for the thumb's size to position its top-left corner
        newX -= thumbSize / 2;
        newY -= thumbSize / 2;

        // Apply the new position
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.steeringJoystickThumb.getLayoutParams();
        params.leftMargin = newX;
        params.topMargin = newY;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        binding.steeringJoystickThumb.setLayoutParams(params);

        // Update arrow colors based on direction
        if (horizontalOffset > 0.1) {
            // Right
            binding.steeringArrowRight.setColorFilter(getResources().getColor(android.R.color.white));
            binding.steeringArrowLeft.setColorFilter(getResources().getColor(R.color.satiBotRed));
        } else if (horizontalOffset < -0.1) {
            // Left
            binding.steeringArrowRight.setColorFilter(getResources().getColor(R.color.satiBotRed));
            binding.steeringArrowLeft.setColorFilter(getResources().getColor(android.R.color.white));
        } else {
            // Neutral
            binding.steeringArrowRight.setColorFilter(getResources().getColor(R.color.satiBotRed));
            binding.steeringArrowLeft.setColorFilter(getResources().getColor(R.color.satiBotRed));
        }
    }

    private void updateDirectionalControls() {
        // Use velocity value for linear velocity (forward/backward)
        float linear = currentVelocityValue;

        // Use steering value for angular velocity (left/right)
        float angular = currentSteeringValue;

        // Update vehicle control with linear and angular velocity
        vehicle.setControlVelocity(linear, angular);
        handleDriveCommand();

        // Provide haptic feedback for high velocity or steering values
        provideHapticFeedback(linear, angular);
    }

    /**
     * Initializes the vibrator service
     */
    private void initializeVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12 and above, use VibratorManager
            VibratorManager vibratorManager = (VibratorManager) requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                vibrator = vibratorManager.getDefaultVibrator();
            }
        } else {
            // For older Android versions
            vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    /**
     * Provides haptic feedback based on linear and angular velocity values
     * @param linear Linear velocity (-1.0 to 1.0)
     * @param angular Angular velocity (-1.0 to 1.0)
     */
    private void provideHapticFeedback(float linear, float angular) {
        if (vibrator == null || !vibrator.hasVibrator() || !hapticFeedbackEnabled) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Check if linear velocity exceeds threshold (left side of phone)
        float absLinear = Math.abs(linear);
        if (absLinear >= HAPTIC_THRESHOLD) {
            // Calculate normalized intensity based on where the value falls between threshold and max
            float normalizedIntensity = calculateNormalizedIntensity(absLinear);

            // If we're already vibrating, only update if cooldown has passed or intensity changed significantly
            if (!isLeftVibrating ||
                (currentTime - lastLeftHapticTime) > HAPTIC_COOLDOWN ||
                Math.abs(normalizedIntensity - lastLeftIntensity) > 0.15f) {

                // Vibrate with intensity proportional to velocity
                vibrateLeft(normalizedIntensity);
                lastLeftHapticTime = currentTime;
                lastLeftIntensity = normalizedIntensity;
                isLeftVibrating = true;
            }
        } else if (isLeftVibrating) {
            // Stop vibration if below threshold
            stopVibration();
            isLeftVibrating = false;
            lastLeftIntensity = 0.0f;
        }

        // Check if angular velocity exceeds threshold (right side of phone)
        float absAngular = Math.abs(angular);
        if (absAngular >= HAPTIC_THRESHOLD) {
            // Calculate normalized intensity based on where the value falls between threshold and max
            float normalizedIntensity = calculateNormalizedIntensity(absAngular);

            // If we're already vibrating, only update if cooldown has passed or intensity changed significantly
            // Use longer cooldown for steering to reduce vibration frequency
            if (!isRightVibrating ||
                (currentTime - lastRightHapticTime) > HAPTIC_COOLDOWN_STEERING ||
                Math.abs(normalizedIntensity - lastRightIntensity) > 0.15f) {

                // Vibrate with intensity proportional to steering
                vibrateRight(normalizedIntensity);
                lastRightHapticTime = currentTime;
                lastRightIntensity = normalizedIntensity;
                isRightVibrating = true;
            }
        } else if (isRightVibrating) {
            // Stop vibration if below threshold
            stopVibration();
            isRightVibrating = false;
            lastRightIntensity = 0.0f;
        }
    }

    /**
     * Calculates a normalized intensity value (0.0 to 1.0) based on where the input value
     * falls between the threshold and max threshold
     * @param value The input value (0.0 to 1.0)
     * @return Normalized intensity (0.0 to 1.0)
     */
    private float calculateNormalizedIntensity(float value) {
        // If below threshold, return 0
        if (value < HAPTIC_THRESHOLD) {
            return 0.0f;
        }

        // If above max threshold, return 1
        if (value >= HAPTIC_MAX_THRESHOLD) {
            return 1.0f;
        }

        // Calculate where the value falls between threshold and max threshold (0.0 to 1.0)
        return (value - HAPTIC_THRESHOLD) / (HAPTIC_MAX_THRESHOLD - HAPTIC_THRESHOLD);
    }

    /**
     * Stops all vibrations
     */
    private void stopVibration() {
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    /**
     * Vibrates the left side of the phone (for linear velocity)
     * @param intensity Intensity of vibration (0.0 to 1.0)
     */
    private void vibrateLeft(float intensity) {
        if (vibrator == null) return;

        // Cancel any ongoing vibrations first
        vibrator.cancel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android 8.0 and above, use VibrationEffect
            // Calculate amplitude based on intensity, scaling between min and max amplitude
            int amplitudeRange = HAPTIC_MAX_AMPLITUDE - HAPTIC_MIN_AMPLITUDE;
            int amplitude = HAPTIC_MIN_AMPLITUDE + (int)(amplitudeRange * intensity);

            // Create a continuous vibration effect
            VibrationEffect effect = VibrationEffect.createOneShot(HAPTIC_DURATION, amplitude);
            vibrator.vibrate(effect);
        } else {
            // For older Android versions
            // Use a fixed duration for continuous feel
            vibrator.vibrate(HAPTIC_DURATION);
        }
    }

    /**
     * Vibrates the right side of the phone (for angular velocity)
     * @param intensity Intensity of vibration (0.0 to 1.0)
     */
    private void vibrateRight(float intensity) {
        if (vibrator == null) return;

        // Cancel any ongoing vibrations first
        vibrator.cancel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android 8.0 and above, use VibrationEffect
            // Calculate amplitude based on intensity, scaling between min and max amplitude
            // Use reduced amplitude range for steering to make vibration less strong
            int amplitudeRange = HAPTIC_MAX_AMPLITUDE_STEERING - HAPTIC_MIN_AMPLITUDE;
            int amplitude = HAPTIC_MIN_AMPLITUDE + (int)(amplitudeRange * intensity);

            // Create a continuous vibration effect with a slightly different feel than left
            // Use a slightly shorter duration to differentiate from left side
            VibrationEffect effect = VibrationEffect.createOneShot(HAPTIC_DURATION - 100, amplitude);
            vibrator.vibrate(effect);
        } else {
            // For older Android versions
            // Use a slightly shorter duration to differentiate from left side
            vibrator.vibrate(HAPTIC_DURATION - 100);
        }
    }

    /**
     * Provides a single strong vibration for emergency stop
     */
    private void vibrateEmergencyStop() {
        if (vibrator == null || !vibrator.hasVibrator() || !hapticFeedbackEnabled) {
            return;
        }

        // Cancel any ongoing vibrations first
        vibrator.cancel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android 8.0 and above, use VibrationEffect
            // Create a single strong vibration
            VibrationEffect effect = VibrationEffect.createOneShot(200, 255);
            vibrator.vibrate(effect);
        } else {
            // For older Android versions
            vibrator.vibrate(200);
        }
    }

    /**
     * Initializes the control update handler and runnable
     */
    private void initControlUpdateHandler() {
        if (controlUpdateHandler == null) {
            controlUpdateHandler = new Handler(Looper.getMainLooper());
        }

        if (controlUpdateRunnable == null) {
            controlUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    updateDirectionalControls();

                    // Schedule the next update if still running
                    if (isControlUpdateRunning && controlUpdateHandler != null) {
                        controlUpdateHandler.postDelayed(this, CONTROL_UPDATE_INTERVAL);
                    }
                }
            };
        }
    }

    /**
     * Starts continuous updates to send control values to the robot
     */
    public void startControlUpdates() {
        // Initialize handler and runnable if needed
        initControlUpdateHandler();

        // Stop any existing updates first
        stopControlUpdates();

        // Start the updates
        isControlUpdateRunning = true;
        controlUpdateHandler.post(controlUpdateRunnable);
    }

    /**
     * Stops the continuous control updates
     */
    public void stopControlUpdates() {
        isControlUpdateRunning = false;

        if (controlUpdateHandler != null && controlUpdateRunnable != null) {
            controlUpdateHandler.removeCallbacks(controlUpdateRunnable);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startControlUpdates();
        updateConnectionStatus();

        // Initialize joystick positions
        if (binding != null) {
            binding.forwardSeekBar.setProgress(50);
            binding.rightSeekBar.setProgress(50);

            // Post to ensure view dimensions are available
            binding.getRoot().post(() -> {
                if (binding != null) {
                    updateAccelerationJoystickPosition(50);
                    updateSteeringJoystickPosition(50);
                }
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopControlUpdates();

        // Stop any ongoing vibrations
        stopVibration();
        isLeftVibrating = false;
        isRightVibrating = false;
    }

    @Override
    public void onDestroyView() {
        // Stop continuous control updates
        stopControlUpdates();
        controlUpdateHandler = null;
        controlUpdateRunnable = null;

        // Reset control values
        currentSteeringValue = 0.0f;
        currentVelocityValue = 0.0f;
        normalizedLinearVelocity = 0.0f;
        targetAngularVelocity = 0.0f;

        // Stop any ongoing vibrations
        stopVibration();
        isLeftVibrating = false;
        isRightVibrating = false;

        // Restore default orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        super.onDestroyView();
        binding = null;
    }
}
