package com.satinavrobotics.satibot.robot;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;

import org.openbot.R;
import org.openbot.databinding.FragmentBluetoothControlBinding;

import com.satinavrobotics.satibot.common.ControlsFragment;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.SensorReader;
import com.satinavrobotics.satibot.utils.SteeringWheelView;
import com.satinavrobotics.satibot.utils.TiltSteeringHandler;
import com.satinavrobotics.satibot.utils.TouchPedalView;

import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import java.util.Locale;

public class BluetoothControlFragment extends ControlsFragment {
    private FragmentBluetoothControlBinding binding;

    // Drive mode constants
    private static final int DRIVE_MODE_DUAL_PEDALS = 0;
    private static final int DRIVE_MODE_TILT = 1;
    private static final int DRIVE_MODE_WHEEL = 2;

    // Control update interval in milliseconds
    private static final int CONTROL_UPDATE_INTERVAL = 50;

    private int currentDriveMode = DRIVE_MODE_DUAL_PEDALS;
    private Handler controlUpdateHandler;
    private Runnable controlUpdateRunnable;
    private boolean isControlUpdateRunning = false;
    private TiltSteeringHandler tiltSteeringHandler;

    // Control values
    private float currentSteeringValue = 0.0f;
    private float currentForwardValue = 0.0f;
    private float currentBackwardValue = 0.0f;
    private float currentVelocityValue = 0.0f;
    private float normalizedLinearVelocity = 0.0f;
    private float targetAngularVelocity = 0.0f;
    private float leftPwm = 0.0f;
    private float rightPwm = 0.0f;


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

        // Initialize tilt steering handler
        tiltSteeringHandler = new TiltSteeringHandler(requireContext());
        tiltSteeringHandler.setSteeringListener(steeringValue -> {
            currentSteeringValue = steeringValue;
            updateTiltControls();
        });

        initializeControls();
        setupConnectionToggles();
        setupDriveModeSpinner();
        setupPedalControls();
        setupTiltAccelerationPedals();
        setupWheelControls();
        setupStopButton();

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

    private void setupDriveModeSpinner() {
        // Set up the spinner with drive mode options
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.bluetooth_drive_modes,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.driveModeSpinner.setAdapter(adapter);

        // Set the selection listener
        binding.driveModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switchDriveMode(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
    }

    private void switchDriveMode(int mode) {
        // Stop any active control modes
        if (currentDriveMode == DRIVE_MODE_TILT) {
            stopTiltControl();
        }

        // Stop continuous control updates if running
        stopControlUpdates();

        // Update the current drive mode
        currentDriveMode = mode;

        // Update UI based on the selected mode
        switch (mode) {
            case DRIVE_MODE_DUAL_PEDALS:
                binding.sliderControlsLayout.setVisibility(View.VISIBLE);
                binding.tiltControlsLayout.setVisibility(View.GONE);
                binding.wheelControlsLayout.setVisibility(View.GONE);
                break;

            case DRIVE_MODE_TILT:
                binding.sliderControlsLayout.setVisibility(View.GONE);
                binding.tiltControlsLayout.setVisibility(View.VISIBLE);
                binding.wheelControlsLayout.setVisibility(View.GONE);
                startTiltControl();
                break;

            case DRIVE_MODE_WHEEL:
                binding.sliderControlsLayout.setVisibility(View.GONE);
                binding.tiltControlsLayout.setVisibility(View.GONE);
                binding.wheelControlsLayout.setVisibility(View.VISIBLE);
                startControlUpdates();
                break;
        }

        // Reset control values
        vehicle.setControlVelocity(0, 0);
        handleDriveCommand();
    }


    private void setupPedalControls() {
        // Set up left pedal
        binding.leftPedal.setOnValueChangeListener(new TouchPedalView.OnValueChangeListener() {
            @Override
            public void onValueChanged(float value) {
                updateControlFromPedals();
            }

            @Override
            public void onTouchEnd() {
                // Auto-center when touch is released
                updateControlFromPedals();
            }
        });

        // Set up right pedal
        binding.rightPedal.setOnValueChangeListener(new TouchPedalView.OnValueChangeListener() {
            @Override
            public void onValueChanged(float value) {
                updateControlFromPedals();
            }

            @Override
            public void onTouchEnd() {
                // Auto-center when touch is released
                updateControlFromPedals();
            }
        });
    }

    private void setupStopButton() {
        binding.stopButton.setOnClickListener(v -> {
            // Stop the vehicle using linear and angular velocity
            vehicle.setControlVelocity(0, 0);
            handleDriveCommand();

        });
    }

    private void updateControlFromPedals() {
        // Get values from pedals (-1 to 1)
        float leftValue = binding.leftPedal.getCurrentValue();
        float rightValue = binding.rightPedal.getCurrentValue();

        // Convert from left/right wheel speeds to linear/angular velocity
        float linear = (leftValue + rightValue) / 2;
        float angular = (rightValue - leftValue) / 0.15f; // Using same wheelbase as in VelocityConverter

        // Set the control values using linear and angular velocity
        vehicle.setControlVelocity(linear, angular);
        handleDriveCommand();
    }

    private void setupTiltAccelerationPedals() {
        // Set up forward pedal (right side)
        binding.forwardPedal.setOnValueChangeListener(new TouchPedalView.OnValueChangeListener() {
            @Override
            public void onValueChanged(float value) {
                // Only use positive values for forward pedal
                currentForwardValue = Math.max(0, value);
                updateTiltControls();
            }

            @Override
            public void onTouchEnd() {
                currentForwardValue = 0;
                updateTiltControls();
            }
        });

        // Set up backward pedal (left side)
        binding.backwardPedal.setOnValueChangeListener(new TouchPedalView.OnValueChangeListener() {
            @Override
            public void onValueChanged(float value) {
                // Only use positive values for backward pedal, but will be applied as negative
                currentBackwardValue = Math.max(0, value);
                updateTiltControls();
            }

            @Override
            public void onTouchEnd() {
                currentBackwardValue = 0;
                updateTiltControls();
            }
        });
    }

    private void updateTiltControls() {
        if (currentDriveMode != DRIVE_MODE_TILT) return;

        // Calculate speed based on pedal inputs
        // Forward has priority over backward if both are pressed
        float speed;
        if (currentForwardValue > 0) {
            speed = currentForwardValue;
        } else if (currentBackwardValue > 0) {
            speed = -currentBackwardValue; // Negative for reverse
        } else {
            speed = 0;
        }

        // Convert steering and speed directly to linear and angular velocity
        float linear = speed;
        float angular = currentSteeringValue * 2.0f; // Scale steering to get reasonable angular velocity

        // Update vehicle control with linear and angular velocity
        vehicle.setControlVelocity(linear, angular);
        handleDriveCommand();

    }

    private void startTiltControl() {
        // Start the tilt steering handler
        tiltSteeringHandler.start();
    }

    private void stopTiltControl() {
        // Stop the tilt steering handler
        tiltSteeringHandler.stop();

        // Reset control values
        currentSteeringValue = 0;
        currentForwardValue = 0;
        currentBackwardValue = 0;

        // Stop the vehicle using linear and angular velocity
        vehicle.setControlVelocity(0, 0);
        handleDriveCommand();
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

                case 'v': // Normalized linear velocity
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

                case 'p': // PWM values
                    String[] pwmValues = body.split(",");
                    if (pwmValues.length == 2 && isNumeric(pwmValues[0]) && isNumeric(pwmValues[1])) {
                        leftPwm = Float.parseFloat(pwmValues[0]);
                        rightPwm = Float.parseFloat(pwmValues[1]);
                        updatePwmValues();
                    }
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
     * Updates the PWM values display
     */
    private void updatePwmValues() {
        // Check if binding is null
        if (binding == null) {
            return;
        }

        // Update PWM values in all control layouts
        binding.pwmValueSlider.setText(
            String.format(Locale.US, "PWM: %.0f,%.0f", leftPwm, rightPwm));

        binding.pwmValueTilt.setText(
            String.format(Locale.US, "PWM: %.0f,%.0f", leftPwm, rightPwm));

        binding.pwmValueWheel.setText(
            String.format(Locale.US, "PWM: %.0f,%.0f", leftPwm, rightPwm));
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
        // Set up steering wheel
        binding.steeringWheel.setOnSteeringValueChangeListener(new SteeringWheelView.OnSteeringValueChangeListener() {
            @Override
            public void onSteeringValueChanged(float value) {
                currentSteeringValue = value;
                // Note: We don't call updateWheelControls() here as it will be called by the timer
            }

            @Override
            public void onTouchEnd() {
                // Auto-center when touch is released
                currentSteeringValue = 0;
                // Immediately update controls when touch ends for responsive feel
                updateWheelControls();
            }
        });

        // Set up velocity pedal
        binding.velocityPedal.setOnValueChangeListener(new TouchPedalView.OnValueChangeListener() {
            @Override
            public void onValueChanged(float value) {
                currentVelocityValue = value;
                // Note: We don't call updateWheelControls() here as it will be called by the timer
            }

            @Override
            public void onTouchEnd() {
                // Auto-center when touch is released
                currentVelocityValue = 0;
                // Immediately update controls when touch ends for responsive feel
                updateWheelControls();
            }
        });
    }

    private void updateWheelControls() {
        if (currentDriveMode != DRIVE_MODE_WHEEL) return;

        // Use velocity pedal value for linear velocity
        float linear = currentVelocityValue;

        // Use steering wheel value for angular velocity
        float angular = currentSteeringValue; // Scale steering to get reasonable angular velocity

        // Update vehicle control with linear and angular velocity
        vehicle.setControlVelocity(linear, angular);
        handleDriveCommand();
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
                    if (currentDriveMode == DRIVE_MODE_WHEEL) {
                        updateWheelControls();
                    }

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
    private void startControlUpdates() {
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
    private void stopControlUpdates() {
        isControlUpdateRunning = false;

        if (controlUpdateHandler != null && controlUpdateRunnable != null) {
            controlUpdateHandler.removeCallbacks(controlUpdateRunnable);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (currentDriveMode == DRIVE_MODE_TILT) {
            startTiltControl();
        } else if (currentDriveMode == DRIVE_MODE_WHEEL) {
            startControlUpdates();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (currentDriveMode == DRIVE_MODE_TILT) {
            stopTiltControl();
        }
        stopControlUpdates();
    }

    @Override
    public void onDestroyView() {
        // Stop any active control modes
        if (currentDriveMode == DRIVE_MODE_TILT) {
            stopTiltControl();
        }

        // Stop continuous control updates
        stopControlUpdates();
        controlUpdateHandler = null;
        controlUpdateRunnable = null;

        // Reset control values
        currentSteeringValue = 0.0f;
        currentForwardValue = 0.0f;
        currentBackwardValue = 0.0f;
        currentVelocityValue = 0.0f;
        normalizedLinearVelocity = 0.0f;
        targetAngularVelocity = 0.0f;
        leftPwm = 0.0f;
        rightPwm = 0.0f;

        // Restore default orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        super.onDestroyView();
        binding = null;
    }
}
