package com.satinavrobotics.satibot.vehicle.pd;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.robot.ControlsFragment;
import com.satinavrobotics.satibot.databinding.FragmentPdTuningBinding;
import com.satinavrobotics.satibot.utils.FormatUtils;
import com.satinavrobotics.satibot.vehicle.Vehicle;

import java.util.Locale;

public class PdTuningFragment extends ControlsFragment {
    private FragmentPdTuningBinding binding;
    private Vehicle vehicle;
    private SharedPreferences sharedPreferences;
    private BottomSheetBehavior<View> bottomSheetBehavior;
    private boolean isPanelVisible = false;
    private static final int CONTROL_UPDATE_INTERVAL = 50;

    private Handler controlUpdateHandler;
    private Runnable controlUpdateRunnable;
    private boolean isControlUpdateRunning = false;

    // Control values
    private float currentSteeringValue = 0.0f;
    private float currentVelocityValue = 0.0f;
    private float normalizedLinearVelocity = 0.0f;
    private float targetAngularVelocity = 0.0f;

    // Track last sent values to reduce message frequency
    private float lastSentLinear = 0.0f;
    private float lastSentAngular = 0.0f;

    // Touch tracking variables
    private float initialTouchX = 0.0f;
    private float initialTouchY = 0.0f;
    private boolean isLeftJoystickActive = false;
    private boolean isRightJoystickActive = false;

    // PD tuning parameters
    private float kp = 1.0f;
    private float kd = 0.5f;
    private float noControlScale = 1.0f;
    private float normalControlScale = 1.0f;
    private float rotationScale = 1.0f;
    private float velocityBias = 0.0f;
    private float rotationBias = 0.0f;

    // SharedPreferences keys
    private static final String PREF_KP = "pd_tuning_kp";
    private static final String PREF_KD = "pd_tuning_kd";
    private static final String PREF_NO_CONTROL_SCALE = "pd_tuning_no_control_scale";
    private static final String PREF_NORMAL_CONTROL_SCALE = "pd_tuning_normal_control_scale";
    private static final String PREF_ROTATION_SCALE = "pd_tuning_rotation_scale";
    private static final String PREF_VELOCITY_BIAS = "pd_tuning_velocity_bias";
    private static final String PREF_ROTATION_BIAS = "pd_tuning_rotation_bias";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentPdTuningBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Force landscape orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Initialize preferences (vehicle is initialized by ControlsFragment)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Load saved parameters
        loadParametersFromPreferences();

        // Setup UI components that don't require vehicle
        setupBottomSheet();
        setupSliders();
        setupSetButton();
        setupJoystickControls();

        // Observe vehicle from ViewModel to setup vehicle-dependent components
        if (mViewModel != null) {
            mViewModel.getVehicle().observe(getViewLifecycleOwner(), vehicleFromViewModel -> {
                if (vehicleFromViewModel != null) {
                    vehicle = vehicleFromViewModel;
                    setupVehicleDependentComponents();
                }
            });
        }

        // Setup vehicle-dependent components if vehicle is already available
        if (vehicle != null) {
            setupVehicleDependentComponents();
        }
    }

    private void setupVehicleDependentComponents() {
        if (vehicle == null) return;

        setupConnectionToggles();
        updateConnectionStatus();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (vehicle != null && (vehicle.isUsbConnected() || vehicle.bleConnected())) {
                requestCurrentParameters();
            }
        }, 500);
    }

    private void setupConnectionToggles() {
        binding.usbToggle.setChecked(vehicle.isUsbConnected());
        binding.bleToggle.setChecked(vehicle.bleConnected());

        binding.usbToggle.setOnClickListener(v -> {
            binding.usbToggle.setChecked(vehicle.isUsbConnected());
            Navigation.findNavController(requireView()).navigate(R.id.open_usb_fragment);
        });

        binding.bleToggle.setOnClickListener(v -> {
            binding.bleToggle.setChecked(vehicle.bleConnected());
            Navigation.findNavController(requireView()).navigate(R.id.open_bluetooth_fragment);
        });
    }

    private void setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.controlPanel);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        // Set the bottom sheet to be draggable by touch
        bottomSheetBehavior.setDraggable(true);

        // Set up the drag handle for the control panel
        binding.dragHandle.setOnClickListener(v -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                isPanelVisible = false;
                binding.toggleControlPanelButton.setImageResource(android.R.drawable.arrow_up_float);
            }
        });

        // Set up the toggle button for the control panel
        binding.toggleControlPanelButton.setOnClickListener(v -> {
            if (isPanelVisible) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                isPanelVisible = false;
                binding.toggleControlPanelButton.setImageResource(android.R.drawable.arrow_up_float);
            } else {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                isPanelVisible = true;
                binding.toggleControlPanelButton.setImageResource(android.R.drawable.arrow_down_float);
            }
        });

        // Add callback for the bottom sheet behavior
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    isPanelVisible = false;
                    binding.toggleControlPanelButton.setImageResource(android.R.drawable.arrow_up_float);
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    isPanelVisible = true;
                    binding.toggleControlPanelButton.setImageResource(android.R.drawable.arrow_down_float);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // Not needed
            }
        });
    }

    private void setupSliders() {
        // KP slider
        binding.kpSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                kp = progress / 100.0f; // 0.0 to 10.0
                binding.kpValue.setText(String.format(Locale.US, "%.3f", kp));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // KD slider
        binding.kdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                kd = progress / 100.0f; // 0.0 to 10.0
                binding.kdValue.setText(String.format(Locale.US, "%.3f", kd));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // No Control Scale slider
        binding.noControlScaleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                noControlScale = progress / 100.0f; // 0.0 to 10.0
                binding.noControlScaleValue.setText(String.format(Locale.US, "%.3f", noControlScale));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Normal Control Scale slider
        binding.normalControlScaleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                normalControlScale = progress / 100.0f; // 0.0 to 10.0
                binding.normalControlScaleValue.setText(String.format(Locale.US, "%.3f", normalControlScale));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Rotation Scale slider
        binding.rotationScaleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rotationScale = progress / 100.0f; // 0.0 to 10.0
                binding.rotationScaleValue.setText(String.format(Locale.US, "%.3f", rotationScale));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Velocity Bias slider
        binding.velocityBiasSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                velocityBias = (progress - 1000) / 1000.0f; // -1.0 to 1.0
                binding.velocityBiasValue.setText(String.format(Locale.US, "%.3f", velocityBias));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Rotation Bias slider
        binding.rotationBiasSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rotationBias = (progress - 1000) / 1000.0f; // -1.0 to 1.0
                binding.rotationBiasValue.setText(String.format(Locale.US, "%.3f", rotationBias));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Initialize slider positions and values
        updateSlidersFromParameters();
    }

    private void setupSetButton() {
        binding.setParametersButton.setOnClickListener(v -> {
            if (vehicle == null || (!vehicle.isUsbConnected() && !vehicle.bleConnected())) {
                Toast.makeText(requireContext(), "No connection to vehicle", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean wasRunning = isControlUpdateRunning;
            if (wasRunning) stopControlUpdates();

            vehicle.sendTuningParameters(kp, kd, noControlScale, normalControlScale,
                                       rotationScale, velocityBias, rotationBias);
            saveParametersToPreferences();
            Toast.makeText(requireContext(), "Parameters sent to vehicle", Toast.LENGTH_SHORT).show();

            if (wasRunning) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isAdded() && vehicle != null) startControlUpdates();
                }, 100);
            }
        });
    }

    private void setupJoystickControls() {
        // Initialize control update handler
        initControlUpdateHandler();

        // Setup emergency stop button
        binding.centerStopButton.setOnClickListener(v -> {
            currentSteeringValue = 0.0f;
            currentVelocityValue = 0.0f;
            normalizedLinearVelocity = 0.0f;
            targetAngularVelocity = 0.0f;
            vehicle.setControlVelocity(0.0f, 0.0f);
            updateControlDisplay();
        });

        // Setup left joystick (acceleration) - right side
        binding.leftJoystickContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
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
                    }
                    return true;

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

        // Setup right joystick (steering) - left side
        binding.rightJoystickContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
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
                    }
                    return true;

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

        // Start control updates
        startControlUpdates();
    }

    private void requestCurrentParameters() {
        if (vehicle == null || (!vehicle.isUsbConnected() && !vehicle.bleConnected())) return;

        boolean wasRunning = isControlUpdateRunning;
        if (wasRunning) stopControlUpdates();

        vehicle.requestTuningParameters();

        if (wasRunning) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && vehicle != null) startControlUpdates();
            }, 100);
        }
    }

    private void parsePdParametersResponse(String body) {
        try {
            String[] values = body.split(",");
            if (values.length >= 7) {
                float receivedKp = Float.parseFloat(values[0]);
                float receivedKd = Float.parseFloat(values[1]);
                float receivedNoControlScale = Float.parseFloat(values[2]);
                float receivedNormalControlScale = Float.parseFloat(values[3]);
                float receivedRotationScale = Float.parseFloat(values[4]);
                float receivedVelocityBias = Float.parseFloat(values[5]);
                float receivedRotationBias = Float.parseFloat(values[6]);

                requireActivity().runOnUiThread(() -> {
                    updateParametersFromVehicle(receivedKp, receivedKd, receivedNoControlScale,
                                               receivedNormalControlScale, receivedRotationScale,
                                               receivedVelocityBias, receivedRotationBias);
                });
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            Toast.makeText(requireContext(), "Invalid PD parameters format received", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateParametersFromVehicle(float receivedKp, float receivedKd, float receivedNoControlScale,
                                            float receivedNormalControlScale, float receivedRotationScale,
                                            float receivedVelocityBias, float receivedRotationBias) {
        kp = receivedKp;
        kd = receivedKd;
        noControlScale = receivedNoControlScale;
        normalControlScale = receivedNormalControlScale;
        rotationScale = receivedRotationScale;
        velocityBias = receivedVelocityBias;
        rotationBias = receivedRotationBias;

        updateSlidersFromParameters();
        saveParametersToPreferences();
        Toast.makeText(requireContext(), "PD parameters loaded from vehicle", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void processControllerKeyData(String commandType) {
        switch (commandType) {
            case Constants.CMD_DRIVE:
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        updateControlDisplay();
                        resetControlTimer();
                    });
                }
                break;
            case Constants.CMD_INDICATOR_LEFT:
            case Constants.CMD_INDICATOR_RIGHT:
            case Constants.CMD_INDICATOR_STOP:
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(this::updateConnectionStatus);
                }
                break;
        }
    }

    @Override
    protected void processUSBData(String data) {
        if (binding == null || vehicle == null || data == null || data.isEmpty()) return;

        char header = data.charAt(0);
        String body = data.substring(1);

        switch (header) {
            case 'm':
                parsePdParametersResponse(body);
                break;
            case 'r':
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        updateConnectionStatus();
                        requestCurrentParameters();
                    });
                }
                break;
            case 'f':
                vehicle.processVehicleConfig(body);
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(this::updateConnectionStatus);
                }
                break;
            case 'v':
                if (FormatUtils.isNumeric(body)) {
                    vehicle.setBatteryPercentage(Float.parseFloat(body));
                }
                break;
            case 'w':
                String[] rpmValues = body.split(",");
                if (rpmValues.length == 2 && FormatUtils.isNumeric(rpmValues[0]) && FormatUtils.isNumeric(rpmValues[1])) {
                    vehicle.setLeftWheelRpm(Float.parseFloat(rpmValues[0]));
                    vehicle.setRightWheelRpm(Float.parseFloat(rpmValues[1]));
                }
                break;
            case 'n':
                if (FormatUtils.isNumeric(body)) {
                    normalizedLinearVelocity = Float.parseFloat(body);
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(this::updateControlDisplay);
                    }
                }
                break;
            case 'a':
                if (FormatUtils.isNumeric(body)) {
                    targetAngularVelocity = Float.parseFloat(body);
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(this::updateControlDisplay);
                    }
                }
                break;
        }
    }

    private void loadParametersFromPreferences() {
        kp = sharedPreferences.getFloat(PREF_KP, 1.0f);
        kd = sharedPreferences.getFloat(PREF_KD, 0.5f);
        noControlScale = sharedPreferences.getFloat(PREF_NO_CONTROL_SCALE, 1.0f);
        normalControlScale = sharedPreferences.getFloat(PREF_NORMAL_CONTROL_SCALE, 1.0f);
        rotationScale = sharedPreferences.getFloat(PREF_ROTATION_SCALE, 1.0f);
        velocityBias = sharedPreferences.getFloat(PREF_VELOCITY_BIAS, 0.0f);
        rotationBias = sharedPreferences.getFloat(PREF_ROTATION_BIAS, 0.0f);
    }

    private void saveParametersToPreferences() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putFloat(PREF_KP, kp);
        editor.putFloat(PREF_KD, kd);
        editor.putFloat(PREF_NO_CONTROL_SCALE, noControlScale);
        editor.putFloat(PREF_NORMAL_CONTROL_SCALE, normalControlScale);
        editor.putFloat(PREF_ROTATION_SCALE, rotationScale);
        editor.putFloat(PREF_VELOCITY_BIAS, velocityBias);
        editor.putFloat(PREF_ROTATION_BIAS, rotationBias);
        editor.apply();
    }

    private void updateSlidersFromParameters() {
        binding.kpSeekBar.setProgress((int) (kp * 100));
        binding.kpValue.setText(String.format(Locale.US, "%.3f", kp));

        binding.kdSeekBar.setProgress((int) (kd * 100));
        binding.kdValue.setText(String.format(Locale.US, "%.3f", kd));

        binding.noControlScaleSeekBar.setProgress((int) (noControlScale * 100));
        binding.noControlScaleValue.setText(String.format(Locale.US, "%.3f", noControlScale));

        binding.normalControlScaleSeekBar.setProgress((int) (normalControlScale * 100));
        binding.normalControlScaleValue.setText(String.format(Locale.US, "%.3f", normalControlScale));

        binding.rotationScaleSeekBar.setProgress((int) (rotationScale * 100));
        binding.rotationScaleValue.setText(String.format(Locale.US, "%.3f", rotationScale));

        binding.velocityBiasSeekBar.setProgress((int) ((velocityBias + 1.0f) * 1000));
        binding.velocityBiasValue.setText(String.format(Locale.US, "%.3f", velocityBias));

        binding.rotationBiasSeekBar.setProgress((int) ((rotationBias + 1.0f) * 1000));
        binding.rotationBiasValue.setText(String.format(Locale.US, "%.3f", rotationBias));
    }

    private void updateConnectionStatus() {
        if (vehicle.isUsbConnected()) {
            binding.connectionStatusText.setText("USB Connected");
            binding.connectionStatusText.setTextColor(0xFF00FF00); // Green
        } else if (vehicle.bleConnected()) {
            binding.connectionStatusText.setText("Bluetooth Connected");
            binding.connectionStatusText.setTextColor(0xFF00FF00); // Green
        } else {
            binding.connectionStatusText.setText("Not Connected");
            binding.connectionStatusText.setTextColor(0xFFFFFF00); // Yellow
        }
    }

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

    private void updateDirectionalControls() {
        if (vehicle == null || (!vehicle.isUsbConnected() && !vehicle.bleConnected())) return;

        float linear = currentVelocityValue;
        float angular = currentSteeringValue;

        if (Math.abs(linear - lastSentLinear) > 0.01f || Math.abs(angular - lastSentAngular) > 0.01f) {
            vehicle.setControlVelocity(linear, angular);
            handleDriveCommand();
            lastSentLinear = linear;
            lastSentAngular = angular;
        }
    }

    private void handleDriveCommand() {
        if (binding == null || vehicle == null) return;

        float linear = vehicle.getLinearVelocity();
        float angular = vehicle.getAngularVelocity();
        binding.controlInfo.setText(String.format(Locale.US, "L:%.0f,A:%.0f", linear, angular));
    }

    private void updateControlDisplay() {
        if (binding != null && vehicle != null) {
            float linear = vehicle.getLinearVelocity();
            float angular = vehicle.getAngularVelocity();
            binding.controlInfo.setText(String.format(Locale.US, "L:%.0f,A:%.0f", linear, angular));
        }
    }

    private void updateAccelerationJoystickPosition(int progress) {
        if (binding == null) return;

        binding.forwardSeekBar.setProgress(progress);
        float normalizedProgress = (progress - 50) / 50.0f;
        int containerHeight = binding.leftJoystickContainer.getHeight();
        if (containerHeight == 0) return;

        float thumbY = -normalizedProgress * (containerHeight / 4.0f);
        binding.accelerationJoystickThumb.setTranslationY(thumbY);
        updateArrowHighlights();
    }

    private void updateSteeringJoystickPosition(int progress) {
        if (binding == null) return;

        binding.rightSeekBar.setProgress(progress);
        float normalizedProgress = (progress - 50) / 50.0f;
        int containerWidth = binding.rightJoystickContainer.getWidth();
        if (containerWidth == 0) return;

        float thumbX = normalizedProgress * (containerWidth / 4.0f);
        binding.steeringJoystickThumb.setTranslationX(thumbX);
        updateArrowHighlights();
    }

    private void updateArrowHighlights() {
        if (currentVelocityValue > 0.1f) {
            binding.accelerationArrowUp.setAlpha(1.0f);
            binding.accelerationArrowDown.setAlpha(0.3f);
        } else if (currentVelocityValue < -0.1f) {
            binding.accelerationArrowUp.setAlpha(0.3f);
            binding.accelerationArrowDown.setAlpha(1.0f);
        } else {
            binding.accelerationArrowUp.setAlpha(0.3f);
            binding.accelerationArrowDown.setAlpha(0.3f);
        }

        if (currentSteeringValue > 0.1f) {
            binding.steeringArrowLeft.setAlpha(0.3f);
            binding.steeringArrowRight.setAlpha(1.0f);
        } else if (currentSteeringValue < -0.1f) {
            binding.steeringArrowLeft.setAlpha(1.0f);
            binding.steeringArrowRight.setAlpha(0.3f);
        } else {
            binding.steeringArrowLeft.setAlpha(0.3f);
            binding.steeringArrowRight.setAlpha(0.3f);
        }
    }

    public void startControlUpdates() {
        initControlUpdateHandler();
        stopControlUpdates();
        isControlUpdateRunning = true;
        controlUpdateHandler.post(controlUpdateRunnable);
    }

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

        if (binding != null) {
            binding.forwardSeekBar.setProgress(50);
            binding.rightSeekBar.setProgress(50);
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
    }

    @Override
    public void onDestroyView() {
        stopControlUpdates();
        controlUpdateHandler = null;
        controlUpdateRunnable = null;

        currentSteeringValue = 0.0f;
        currentVelocityValue = 0.0f;
        normalizedLinearVelocity = 0.0f;
        targetAngularVelocity = 0.0f;
        lastSentLinear = 0.0f;
        lastSentAngular = 0.0f;

        if (getActivity() != null) {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }

        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (getActivity() != null) {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }
}
