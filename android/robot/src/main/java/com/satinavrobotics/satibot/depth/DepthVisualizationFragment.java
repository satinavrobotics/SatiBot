package com.satinavrobotics.satibot.depth;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.depth.depth_sources.ARCoreDepthImageGenerator;
import com.satinavrobotics.satibot.depth.depth_sources.DepthImageGenerator;
import com.satinavrobotics.satibot.depth.depth_sources.ONNXDepthImageGenerator;
import com.satinavrobotics.satibot.depth.depth_sources.TFLiteDepthImageGenerator;
import com.satinavrobotics.satibot.arcore.rendering.DisplayRotationHelper;
import com.satinavrobotics.satibot.arcore.rendering.DepthMapRenderer;

// Play Services TFLite imports

import java.io.IOException;

import timber.log.Timber;

public class DepthVisualizationFragment extends BaseDepthFragment {

    private static final String TAG = DepthVisualizationFragment.class.getSimpleName();

    // Additional components specific to visualization
    private DisplayRotationHelper displayRotationHelper;
    private boolean installRequested;

    // Handler for checking initialization status
    private Handler initCheckHandler;
    private static final int INIT_CHECK_DELAY_MS = 500; // Check every 500ms

    // Bottom sheet behavior for the control panel
    private BottomSheetBehavior<View> controlPanelBehavior;
    private boolean isPanelVisible = false;

    // UI elements specific to visualization (common ones are in base class)
    private SeekBar confidenceThresholdSeekBar;
    private TextView confidenceThresholdValueTextView;
    private RadioGroup visualizationModeRadioGroup;
    private Spinner depthSourceSpinner;
    private ImageButton toggleControlPanelButton;
    private View nnapiContainer;
    private TextView deviceInfoText;

    // ONNX-specific UI elements
    private View onnxSettingsContainer;
    private SeekBar closerNextThresholdSeekBar;
    private TextView closerNextThresholdValueTextView;
    private SeekBar maxSafeDistanceSeekBar;
    private TextView maxSafeDistanceValueTextView;
    private SeekBar consecutiveThresholdSeekBar;
    private TextView consecutiveThresholdValueTextView;
    private SeekBar downsampleFactorSeekBar;
    private TextView downsampleFactorValueTextView;
    private SeekBar horizontalGradientThresholdSeekBar;
    private TextView horizontalGradientThresholdValueTextView;
    private SeekBar robotWidthSeekBar;
    private TextView robotWidthValueTextView;
    private SeekBar navigabilityThresholdSeekBar;
    private TextView navigabilityThresholdValueTextView;

    // FPS update handler
    private Handler fpsUpdateHandler;
    private static final int FPS_UPDATE_INTERVAL_MS = 500; // Update FPS display every 500ms

    // FPS calculation specific to visualization fragment
    private final long[] frameTimes = new long[50]; // Store last 50 frame times (5 seconds at 10 FPS)
    private int frameTimeIndex = 0;
    private TextView mainFpsText;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_depth_visualization_with_sliding_panel, container, false);
        // Force landscape orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        return view;
    }

    @Override
    protected void findUIElements(View view) {
        // Find common UI elements
        surfaceView = view.findViewById(R.id.surfaceView);
        statusText = view.findViewById(R.id.statusText);
        fpsText = view.findViewById(R.id.fpsText);
        displayModeButton = view.findViewById(R.id.displayModeButton);

        // Find visualization-specific UI elements
        confidenceThresholdSeekBar = view.findViewById(R.id.confidenceThresholdSeekBar);
        confidenceThresholdValueTextView = view.findViewById(R.id.confidenceThresholdValue);
        visualizationModeRadioGroup = view.findViewById(R.id.visualizationModeRadioGroup);
        depthSourceSpinner = view.findViewById(R.id.depthSourceSpinner);
        toggleControlPanelButton = view.findViewById(R.id.toggleControlPanelButton);
        nnapiContainer = view.findViewById(R.id.nnapiContainer);
        deviceInfoText = view.findViewById(R.id.deviceInfoText);
        mainFpsText = view.findViewById(R.id.mainFpsText);

        // Find ONNX-specific UI elements
        onnxSettingsContainer = view.findViewById(R.id.onnxSettingsContainer);
        closerNextThresholdSeekBar = view.findViewById(R.id.closerNextThresholdSeekBar);
        closerNextThresholdValueTextView = view.findViewById(R.id.closerNextThresholdValue);
        maxSafeDistanceSeekBar = view.findViewById(R.id.maxSafeDistanceSeekBar);
        maxSafeDistanceValueTextView = view.findViewById(R.id.maxSafeDistanceValue);
        consecutiveThresholdSeekBar = view.findViewById(R.id.consecutiveThresholdSeekBar);
        consecutiveThresholdValueTextView = view.findViewById(R.id.consecutiveThresholdValue);
        downsampleFactorSeekBar = view.findViewById(R.id.downsampleFactorSeekBar);
        downsampleFactorValueTextView = view.findViewById(R.id.downsampleFactorValue);
        horizontalGradientThresholdSeekBar = view.findViewById(R.id.horizontalGradientThresholdSeekBar);
        horizontalGradientThresholdValueTextView = view.findViewById(R.id.horizontalGradientThresholdValue);
        robotWidthSeekBar = view.findViewById(R.id.robotWidthSeekBar);
        robotWidthValueTextView = view.findViewById(R.id.robotWidthValue);
        navigabilityThresholdSeekBar = view.findViewById(R.id.navigabilityThresholdSeekBar);
        navigabilityThresholdValueTextView = view.findViewById(R.id.navigabilityThresholdValue);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize DisplayRotationHelper
        displayRotationHelper = new DisplayRotationHelper(requireContext());

        // Initialize FPS update handler
        fpsUpdateHandler = new Handler();

        // Initialize switches
        SwitchCompat nnapiSwitch = view.findViewById(R.id.nnapiSwitch);
        SwitchCompat enableHorizontalGradientsSwitch = view.findViewById(R.id.enableHorizontalGradientsSwitch);

        // Set up the control panel behavior
        View controlPanel = view.findViewById(R.id.controlPanel);
        controlPanelBehavior = BottomSheetBehavior.from(controlPanel);
        controlPanelBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        // Set the bottom sheet to be draggable by touch
        // This allows the panel to be hidden by dragging it down
        controlPanelBehavior.setDraggable(true);

        // Set up the drag handle for the control panel
        View dragHandle = view.findViewById(R.id.dragHandle);
        dragHandle.setOnClickListener(v -> {
            if (controlPanelBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                controlPanelBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                isPanelVisible = false;
                toggleControlPanelButton.setImageResource(android.R.drawable.arrow_up_float);
            }
        });

        // Set up the toggle button for the control panel
        toggleControlPanelButton.setOnClickListener(v -> {
            if (isPanelVisible) {
                controlPanelBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                isPanelVisible = false;
                toggleControlPanelButton.setImageResource(android.R.drawable.arrow_up_float);
            } else {
                controlPanelBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                isPanelVisible = true;
                toggleControlPanelButton.setImageResource(android.R.drawable.arrow_down_float);
            }
        });

        // Add callback for the bottom sheet behavior
        controlPanelBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    isPanelVisible = false;
                    toggleControlPanelButton.setImageResource(android.R.drawable.arrow_up_float);
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    isPanelVisible = true;
                    toggleControlPanelButton.setImageResource(android.R.drawable.arrow_down_float);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // Not needed
            }
        });

        // Set the FPS update listener for visualization-specific FPS tracking
        if (depthMapRenderer != null) {
            depthMapRenderer.setFpsUpdateListener(() -> {
                // Update FPS statistics
                updateFrameStats();
            });
        }

        // Set up the confidence threshold seek bar
        confidenceThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float threshold = progress / 255.0f;
                confidenceThresholdValueTextView.setText(String.format("%.0f%%", threshold * 100));
                if (depthMapRenderer != null) {
                    depthMapRenderer.setConfidenceThreshold(threshold);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Set up the visualization mode radio group
        visualizationModeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (depthMapRenderer != null) {
                if (checkedId == R.id.radioRainbow) {
                    depthMapRenderer.setDepthColorMode(0);
                } else if (checkedId == R.id.radioGrayscale) {
                    depthMapRenderer.setDepthColorMode(1);
                }
            }
        });

        // Set up the depth source spinner
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.depth_sources,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        depthSourceSpinner.setAdapter(adapter);
        depthSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != currentDepthSource) {
                    currentDepthSource = position;

                    // Save the depth source preference immediately
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.setDepthSource(currentDepthSource);
                        Timber.d("Saved depth source preference: %d", currentDepthSource);
                    }

                    // Show/hide NNAPI switch based on depth source
                    if (position == DEPTH_SOURCE_ONNX) {
                        nnapiContainer.setVisibility(View.VISIBLE);
                        // Show ONNX sliders
                        if (onnxSettingsContainer != null) onnxSettingsContainer.setVisibility(View.VISIBLE);
                    } else {
                        nnapiContainer.setVisibility(View.GONE);
                        // Hide ONNX sliders
                        if (onnxSettingsContainer != null) onnxSettingsContainer.setVisibility(View.GONE);
                    }

                    // Initialize TFLite if needed before updating depth source
                    if (position == DEPTH_SOURCE_TFLITE && !TFLiteDepthImageGenerator.isTFLiteInitialized()) {
                        updateStatusText("Initializing TensorFlow Lite...");
                        // Use the static method in TFLiteDepthImageGenerator to initialize TFLite
                        TFLiteDepthImageGenerator.initializeTFLite(requireContext(), () -> {
                            // Update the local initialization flag based on the static flag
                            tfLiteInitialized = TFLiteDepthImageGenerator.isTFLiteInitialized();

                            // After initialization completes (success or failure), update the depth source
                            updateDepthSource();
                        });
                    } else {
                        // For other sources or if TFLite is already initialized, update immediately
                        // Make sure our local flag matches the static flag
                        if (position == DEPTH_SOURCE_TFLITE) {
                            tfLiteInitialized = TFLiteDepthImageGenerator.isTFLiteInitialized();
                        }
                        updateDepthSource();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        // Set up the NNAPI switch
        nnapiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (depthMapRenderer != null && depthProcessor.getDepthImageGenerator() instanceof ONNXDepthImageGenerator onnxGenerator) {
                boolean success = onnxGenerator.setUseNNAPI(isChecked);
                if (!success) {
                    // If switching failed, revert the switch state
                    buttonView.setChecked(!isChecked);
                    Toast.makeText(requireContext(),
                            "Failed to switch execution provider",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Set up the horizontal gradients switch
        enableHorizontalGradientsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (depthProcessor != null) {
                depthProcessor.setHorizontalGradientsEnabled(isChecked);
                Timber.d("Horizontal gradients %s", isChecked ? "enabled" : "disabled");
            }
        });



        // Set up ONNX-specific settings
        closerNextThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Convert progress (0-1000) to millimeters (0-100mm)
                float thresholdMm = progress / 10.0f;
                closerNextThresholdValueTextView.setText(String.format("%.1f mm", thresholdMm));

                // Store the value in RobotParametersManager
                RobotParametersManager.getInstance().setCloserNextThreshold(thresholdMm);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        maxSafeDistanceSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Convert progress (0-1000) to meters (0-10m)
                float distanceM = progress / 100.0f;
                maxSafeDistanceValueTextView.setText(String.format("%.1f m", distanceM));

                // Convert to mm for storage and processing
                float distanceMm = distanceM * 1000.0f;

                // Store the value in RobotParametersManager
                RobotParametersManager.getInstance().setMaxSafeDistance(distanceMm);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        consecutiveThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Ensure minimum value of 1
                int threshold = Math.max(1, progress);
                consecutiveThresholdValueTextView.setText(String.format("%d pixels", threshold));

                // Store the value in RobotParametersManager
                RobotParametersManager.getInstance().setConsecutiveThreshold(threshold);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        downsampleFactorSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Ensure minimum value of 1 (no downsampling)
                int factor = Math.max(1, progress + 1); // +1 because we want range 1-10, not 0-9
                downsampleFactorValueTextView.setText(String.format("%dx", factor));

                // Store the value in RobotParametersManager
                RobotParametersManager.getInstance().setDownsampleFactor(factor);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        horizontalGradientThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Convert progress (0-1000) to millimeters (0-1000mm)
                horizontalGradientThresholdValueTextView.setText(String.format("%.1f mm", (float) progress));

                // Store the value in RobotParametersManager
                RobotParametersManager.getInstance().setDepthGradientThreshold((float) progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Set up the robot width seek bar
        robotWidthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Convert progress (0-100) to meters (0.1-1.0m)
                // Use a float division to ensure we get a float result
                float widthMeters = Math.max(0.1f, (float)progress / 100.0f);
                robotWidthValueTextView.setText(String.format("%.1f m", widthMeters));

                // Log the progress and calculated width for debugging
                Timber.d("Robot width seekbar progress: %d, calculated width: %.2f meters", progress, widthMeters);

                // Update the central robot parameters manager
                RobotParametersManager.getInstance().setRobotWidthMeters(widthMeters);

                // Check if we're in navigation mode
                boolean isNavMode = currentDisplayMode == DepthMapRenderer.DISPLAY_MODE_NAV;

                // Update robot bounds and NavMapOverlay in a coordinated way
                if (navMapOverlay != null) {
                    // First update the NavMapOverlay with the new width
                    navMapOverlay.setRobotWidthMeters(widthMeters);

                    // Then update both the robot bounds overlay and navigation map
                    navMapOverlay.updateRobotBounds(null, isNavMode);

                    // If in nav mode, update the navigation map data
                    if (isNavMode) {
                        navMapOverlay.update();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Log when user starts interacting with the seekbar
                Timber.d("Robot width seekbar: started tracking touch");
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Log when user stops interacting with the seekbar
                int progress = seekBar.getProgress();
                float widthMeters = Math.max(0.1f, (float)progress / 100.0f);
                Timber.d("Robot width seekbar: stopped tracking touch, final width: %.2f meters", widthMeters);
            }
        });

        // Set up the navigability threshold seek bar
        navigabilityThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Ensure minimum value of 1%
                int thresholdPercent = Math.max(1, progress);
                navigabilityThresholdValueTextView.setText(String.format("%d%% obstacles", thresholdPercent));

                // Store the value in RobotParametersManager
                RobotParametersManager.getInstance().setNavigabilityThreshold(thresholdPercent);

                if (navMapOverlay != null) {
                    navMapOverlay.setNavigabilityThreshold(thresholdPercent);
                }

                Timber.d("Set navigability threshold to %d%% obstacles", thresholdPercent);

                // Update the navigation map if it's visible
                if (navMapOverlay != null && depthMapRenderer != null &&
                    currentDisplayMode == DepthMapRenderer.DISPLAY_MODE_NAV) {
                    navMapOverlay.update();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        installRequested = false;
        displayRotationHelper = new DisplayRotationHelper(requireContext());
    }

    @Override
    protected void onDisplayModeChanged(int newDisplayMode) {
        // Show or hide the navigation map overlay based on the display mode
        View overlayView = requireView().findViewById(R.id.navMapOverlay);
        if (overlayView != null) {
            boolean isNavMode = newDisplayMode == DepthMapRenderer.DISPLAY_MODE_NAV;

            // Set visibility first
            overlayView.setVisibility(isNavMode ? View.VISIBLE : View.GONE);

            if (isNavMode) {
                // Also update the robot bounds to ensure the navigation map is properly positioned
                if (navMapOverlay != null) {
                    // Update the NavMapOverlay with visibility set to true
                    // This will also update the robot bounds overlay
                    navMapOverlay.updateRobotBounds(null, true);

                    // Update the navigation map data
                    navMapOverlay.update();
                }

                Timber.d("Navigation map overlay shown and positioned, visibility: %s",
                        overlayView.getVisibility() == View.VISIBLE ? "VISIBLE" : "NOT VISIBLE");
            } else {
                Timber.d("Navigation map overlay hidden");
            }
        } else {
            Timber.e("Navigation map overlay view not found!");
        }

        // Log the mode change
        Timber.d("Display mode changed to: %s", DISPLAY_MODE_NAMES[newDisplayMode]);
    }


    /**
     * Updates the depth source based on the current selection.
     */
    protected void updateDepthSource() {
        if (depthMapRenderer == null || depthProcessor == null || arCoreHandler == null) {
            return;
        }

        try {
            DepthImageGenerator newGenerator;

            // ONNX doesn't need special initialization
            boolean onnxInitialized = true;
            switch (currentDepthSource) {
                case DEPTH_SOURCE_TFLITE:
                    // Check the static initialization flag
                    if (!TFLiteDepthImageGenerator.isTFLiteInitialized()) {
                        // This should not happen as we now initialize TFLite before calling updateDepthSource
                        updateStatusText("TensorFlow Lite not initialized, initializing now...");
                        TFLiteDepthImageGenerator.initializeTFLite(requireContext(), () -> {
                            // Update our local flag
                            tfLiteInitialized = TFLiteDepthImageGenerator.isTFLiteInitialized();

                            if (tfLiteInitialized) {
                                // Try again after initialization
                                updateDepthSource();
                            } else {
                                // If initialization failed, fall back to ARCore
                                updateStatusText("TensorFlow Lite initialization failed, using ARCore instead");
                                depthSourceSpinner.setSelection(DEPTH_SOURCE_ARCORE);
                            }
                        });
                        return;
                    }
                    updateStatusText("Switching to TensorFlow Lite depth source");
                    newGenerator = new TFLiteDepthImageGenerator();
                    break;

                case DEPTH_SOURCE_ONNX:
                    if (!onnxInitialized) {
                        updateStatusText("ONNX Runtime not initialized, using ARCore instead");
                        depthSourceSpinner.setSelection(DEPTH_SOURCE_ARCORE);
                        return;
                    }
                    updateStatusText("Switching to ONNX Runtime depth source");
                    newGenerator = new ONNXDepthImageGenerator();
                    break;

                case DEPTH_SOURCE_ARCORE:
                default:
                    updateStatusText("Switching to ARCore depth source");
                    newGenerator = new ARCoreDepthImageGenerator();
                    break;
            }

            try {
                // Set the new depth image generator in the depth processor
                depthProcessor.setDepthImageGenerator(newGenerator, requireContext());

                // Start checking for initialization
                startInitializationCheck();

                // Initialize the navigation map overlay with the new depth image generator
                initializeNavMapOverlay();

                // Update status based on current initialization state
                if (depthProcessor.getDepthImageGenerator().isInitialized()) {
                    if (currentDepthSource == DEPTH_SOURCE_TFLITE) {
                        updateStatusText("TensorFlow Lite depth source initialized");
                    } else if (currentDepthSource == DEPTH_SOURCE_ONNX) {
                        updateStatusText("ONNX Runtime depth source initialized");
                    } else {
                        updateStatusText("ARCore depth source initialized");
                    }
                } else {
                    if (currentDepthSource == DEPTH_SOURCE_TFLITE) {
                        updateStatusText("Waiting for TensorFlow Lite initialization...");
                    } else if (currentDepthSource == DEPTH_SOURCE_ONNX) {
                        updateStatusText("Waiting for ONNX Runtime initialization...");
                    } else {
                        updateStatusText("Waiting for ARCore depth source initialization...");
                    }
                }
            } catch (IOException e) {
                Timber.e(e, "Error setting depth image generator: %s", e.getMessage());
                updateStatusText("Error setting depth image generator: " + e.getMessage());
            }

            // Set the confidence threshold
            float threshold = confidenceThresholdSeekBar.getProgress() / 255.0f;
            depthMapRenderer.setConfidenceThreshold(threshold);

            // Set the color mode
            int colorMode = visualizationModeRadioGroup.getCheckedRadioButtonId() == R.id.radioRainbow ? 0 : 1;
            depthMapRenderer.setDepthColorMode(colorMode);

        } catch (Exception e) {
            updateStatusText("Error switching depth source: " + e.getMessage());
        }
    }

    /**
     * Starts a periodic check for depth image generator initialization.
     * This is needed because TFLite initialization is asynchronous.
     */
    private void startInitializationCheck() {
        // Create a handler on the main thread
        if (initCheckHandler == null) {
            initCheckHandler = new Handler();
        }

        // Define the runnable that checks initialization status
        Runnable initCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (depthProcessor != null && depthProcessor.getDepthImageGenerator() != null) {
                    boolean isInitialized = depthProcessor.getDepthImageGenerator().isInitialized();

                    if (isInitialized) {
                        // The generator is now initialized
                        if (currentDepthSource == DEPTH_SOURCE_TFLITE) {
                            updateStatusText("TensorFlow Lite depth source initialized");
                        } else if (currentDepthSource == DEPTH_SOURCE_ONNX) {
                            updateStatusText("ONNX Runtime depth source initialized");
                        } else {
                            updateStatusText("ARCore depth source initialized");
                        }
                    } else {
                        // Still waiting for initialization
                        if (currentDepthSource == DEPTH_SOURCE_TFLITE) {
                            updateStatusText("Waiting for TensorFlow Lite initialization...");
                        } else if (currentDepthSource == DEPTH_SOURCE_ONNX) {
                            updateStatusText("Waiting for ONNX Runtime initialization...");
                        }

                        // Schedule another check
                        initCheckHandler.postDelayed(this, INIT_CHECK_DELAY_MS);
                    }
                } else {
                    // DepthProcessor not created yet or no depth image generator set, check again later
                    initCheckHandler.postDelayed(this, INIT_CHECK_DELAY_MS);
                }
            }
        };

        // Start the periodic check
        initCheckHandler.post(initCheckRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Load saved depth source preference
        int savedDepthSource = sharedPreferencesManager.getDepthSource();
        if (savedDepthSource != currentDepthSource) {
            Timber.d("Loading saved depth source: %d", savedDepthSource);
            currentDepthSource = savedDepthSource;

            // Update the spinner selection on the UI thread
            requireActivity().runOnUiThread(() -> {
                depthSourceSpinner.setSelection(currentDepthSource);
            });

            // Show/hide NNAPI switch based on depth source
            if (currentDepthSource == DEPTH_SOURCE_ONNX) {
                nnapiContainer.setVisibility(View.VISIBLE);
                // Show ONNX sliders
                if (onnxSettingsContainer != null) onnxSettingsContainer.setVisibility(View.VISIBLE);
            } else {
                nnapiContainer.setVisibility(View.GONE);
                // Hide ONNX sliders
                if (onnxSettingsContainer != null) onnxSettingsContainer.setVisibility(View.GONE);
            }
        }

        // Start checking for initialization
        startInitializationCheck();

        // Start updating device info and FPS
        startDeviceInfoAndFPSUpdates();

        try {
            // Check if ARCore is installed
            switch (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
                case INSTALL_REQUESTED:
                    installRequested = true;
                    return;
                case INSTALLED:
                    break;
            }

            // Check if depth is supported
            Session session = arCoreHandler.getSession();
            if (session != null) {
                boolean isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
                if (!isDepthSupported) {
                    String message = "Depth is not supported on this device";
                    updateStatusText(message);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    // We'll continue but warn the user
                }
            }

            // Initialize the depth image generator based on the current selection
            updateDepthSource();

        } catch (Exception e) {
            String message = "Failed to initialize depth visualization: " + e.getMessage();
            Timber.e(e, "Exception during initialization: %s", message);
            updateStatusText("Error: " + message);
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
        }

        displayRotationHelper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();

        // Stop the initialization check
        if (initCheckHandler != null) {
            initCheckHandler.removeCallbacksAndMessages(null);
        }

        // Stop updating device info and FPS
        stopDeviceInfoAndFPSUpdates();

        // Save the current depth source preference
        sharedPreferencesManager.setDepthSource(currentDepthSource);
        Timber.d("Saved depth source preference: %d", currentDepthSource);

        // Save all robot parameters to preferences
        RobotParametersManager.getInstance().saveParametersToPreferences();

        displayRotationHelper.onPause();
    }

    @Override
    protected void processControllerKeyData(String command) {

    }

    @Override
    protected void processUSBData(String data) {

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Additional cleanup specific to visualization fragment
        // Base class handles the common cleanup
    }

    /**
     * Updates the FPS display based on the current frame time.
     * This method is called from the ArCoreHandler's GL thread.
     */
    private void updateFrameStats() {
        // Calculate FPS
        long currentTime = System.currentTimeMillis();
        if (lastFrameTime > 0) {
            long frameTime = currentTime - lastFrameTime;
            frameTimes[frameTimeIndex] = frameTime;
            frameTimeIndex = (frameTimeIndex + 1) % frameTimes.length;

            // Calculate average FPS from the stored frame times
            if (frameTimeIndex % 5 == 0) { // Update every 5 frames to avoid too frequent UI updates
                float avgFps = calculateAverageFPS();
                updateFPSDisplay(avgFps);
            }
        }
        lastFrameTime = currentTime;
    }

    /**
     * Initialize the navigation map overlay with the current settings.
     */
    protected void initializeNavMapOverlay() {
        try {
            // Get the depth image generator from the depth processor
            DepthImageGenerator depthImageGenerator = depthProcessor.getDepthImageGenerator();

            // Initialize ONNX-specific settings from RobotParametersManager
            RobotParametersManager robotParams = RobotParametersManager.getInstance();

            // Get and set closerNextThreshold from RobotParametersManager
            float closerNextThreshold = robotParams.getCloserNextThreshold();

            // Update UI elements on the main thread
            requireActivity().runOnUiThread(() -> {
                int progress = (int)(closerNextThreshold * 10.0f); // Convert mm to progress value
                closerNextThresholdSeekBar.setProgress(progress);
                closerNextThresholdValueTextView.setText(String.format("%.1f mm", closerNextThreshold));
            });

            // Get and set maxSafeDistance from RobotParametersManager
            float maxSafeDistance = robotParams.getMaxSafeDistance();

            // Update UI elements on the main thread
            requireActivity().runOnUiThread(() -> {
                int progress = (int)(maxSafeDistance / 10.0f); // Convert mm to progress value
                maxSafeDistanceSeekBar.setProgress(progress);
                maxSafeDistanceValueTextView.setText(String.format("%.1f m", maxSafeDistance / 1000.0f));
            });

            // Get and set consecutiveThreshold from RobotParametersManager
            int consecutiveThreshold = robotParams.getConsecutiveThreshold();

            // Update UI elements on the main thread
            requireActivity().runOnUiThread(() -> {
                consecutiveThresholdSeekBar.setProgress(consecutiveThreshold);
                consecutiveThresholdValueTextView.setText(String.format("%d pixels", consecutiveThreshold));
            });

            // Get and set downsampleFactor from RobotParametersManager
            int downsampleFactor = robotParams.getDownsampleFactor();

            // Update UI elements on the main thread
            requireActivity().runOnUiThread(() -> {
                downsampleFactorSeekBar.setProgress(downsampleFactor - 1); // UI is 0-based, factor is 1-based
                downsampleFactorValueTextView.setText(String.format("%dx", downsampleFactor));
            });

            // Get and set depthGradientThreshold from RobotParametersManager
            float depthGradientThreshold = robotParams.getDepthGradientThreshold();

            // Update UI elements on the main thread
            requireActivity().runOnUiThread(() -> {
                horizontalGradientThresholdSeekBar.setProgress((int)depthGradientThreshold);
                horizontalGradientThresholdValueTextView.setText(String.format("%.1f mm", depthGradientThreshold));
            });

            // Get and set robotWidthMeters from RobotParametersManager
            float robotWidthMeters = robotParams.getRobotWidthMeters();
            Timber.d("Initializing robot width seekbar with value: %.2f meters", robotWidthMeters);
            requireActivity().runOnUiThread(() -> {
                // Calculate the progress value correctly (multiply by 100 and cast to int)
                int progress = (int)(robotWidthMeters * 100.0f);
                Timber.d("Setting robot width seekbar progress to: %d", progress);
                robotWidthSeekBar.setProgress(progress); // Convert to progress (0-100)
                robotWidthValueTextView.setText(String.format("%.1f m", robotWidthMeters));
            });

            // Initialize the navigation map overlay
            navMapOverlay = new NavMapOverlay(depthImageGenerator, depthProcessor);
            requireActivity().runOnUiThread(() -> {
                View navMapOverlayView = requireView().findViewById(R.id.navMapOverlay);
                if (navMapOverlayView != null) {
                    // Make sure the overlay view is visible
                    navMapOverlayView.setVisibility(View.VISIBLE);

                    navMapOverlay.initialize(navMapOverlayView);
                    navMapOverlay.setRobotWidthMeters(robotWidthMeters);

                    // Set initial visibility based on display mode
                    boolean isNavMode = currentDisplayMode == DepthMapRenderer.DISPLAY_MODE_NAV;

                    // Update the robot bounds and position the navigation map
                    // This will also update the robot bounds overlay
                    navMapOverlay.updateRobotBounds(null, isNavMode);

                    // Update the navigation map data if in nav mode
                    if (isNavMode) {
                        navMapOverlay.update();
                    }

                    // Set a simplified NavMapUpdateListener on the DepthMapRenderer
                    // This listener only updates the robot bounds and navigation map when needed
                    if (depthMapRenderer != null) {
                        depthMapRenderer.setNavMapUpdateListener(cameraIntrinsics -> {
                            if (navMapOverlay != null) {
                                try {
                                    // Check if we're in navigation mode
                                    boolean navMode = currentDisplayMode == DepthMapRenderer.DISPLAY_MODE_NAV;

                                    // Only update if in navigation mode or if this is the first update
                                    if (navMode || !navMapOverlay.isInitialized()) {
                                        // Update the NavMapOverlay with camera intrinsics
                                        navMapOverlay.updateRobotBounds(cameraIntrinsics, navMode);

                                        // If in nav mode, update the navigation map data
                                        if (navMode) {
                                            navMapOverlay.update();
                                        }
                                    }
                                } catch (Exception e) {
                                    Timber.w("Failed to update NavMapOverlay: %s", e.getMessage());
                                }
                            }
                        });
                    }

                    Timber.d("Initialized navigation map overlay, visibility: %s",
                            isNavMode ? "VISIBLE" : "GONE");
                } else {
                    Timber.e("Navigation map overlay view not found!");
                }
            });

            // Get and set navigabilityThreshold from RobotParametersManager
            int navigabilityThreshold = robotParams.getNavigabilityThreshold();
            if (navMapOverlay != null) {
                navMapOverlay.setNavigabilityThreshold(navigabilityThreshold);
            }

            // Update UI elements on the main thread
            requireActivity().runOnUiThread(() -> {
                navigabilityThresholdSeekBar.setProgress(navigabilityThreshold);
                navigabilityThresholdValueTextView.setText(String.format("%d%% obstacles", navigabilityThreshold));
            });

            // Set initial values for confidence threshold
            requireActivity().runOnUiThread(() -> {
                float threshold = confidenceThresholdSeekBar.getProgress() / 255.0f;
                depthMapRenderer.setConfidenceThreshold(threshold);
            });

            // Get the color mode from the UI thread and set it
            requireActivity().runOnUiThread(() -> {
                int colorMode = visualizationModeRadioGroup.getCheckedRadioButtonId() == R.id.radioRainbow ? 0 : 1;
                depthMapRenderer.setDepthColorMode(colorMode);
            });

            // Set initial display mode (depth map)
            currentDisplayMode = DepthMapRenderer.DISPLAY_MODE_DEPTH;
            updateDisplayModeButtonText(currentDisplayMode);

            // We'll set sessionInitialized to true only when we actually get depth data
            updateStatusText("Initializing depth visualization...");
        } catch (Exception e) {
            Timber.e(e, "Failed to initialize navigation map overlay: %s", e.getMessage());
            updateStatusText("Error: " + e.getMessage());
        }
    }

    /**
     * Calculates the average FPS from the stored frame times.
     * @return Average FPS over the last several frames
     */
    private float calculateAverageFPS() {
        long totalTime = 0;
        int validFrames = 0;

        for (long frameTime : frameTimes) {
            if (frameTime > 0) {
                totalTime += frameTime;
                validFrames++;
            }
        }

        if (validFrames > 0 && totalTime > 0) {
            return 1000.0f * validFrames / totalTime;
        } else {
            return 0;
        }
    }

    /**
     * Updates the FPS display in the UI.
     * @param fps Current FPS value
     */
    @SuppressLint("DefaultLocale")
    private void updateFPSDisplay(final float fps) {
        requireActivity().runOnUiThread(() -> {
            if (mainFpsText != null) {
                mainFpsText.setText(String.format("%.1f FPS", fps));
            }
        });
    }

    /**
     * Start updating the device info and FPS display.
     * This should be called when the depth image generator is initialized.
     */
    private void startDeviceInfoAndFPSUpdates() {
        if (fpsUpdateHandler == null) {
            fpsUpdateHandler = new Handler();
        }

        // Stop any existing updates
        fpsUpdateHandler.removeCallbacksAndMessages(null);

        // Create a runnable to update the device info and FPS display
        Runnable updateRunnable = new Runnable() {
            @SuppressLint("DefaultLocale")
            @Override
            public void run() {
                if (depthMapRenderer != null && depthProcessor != null) {
                    DepthImageGenerator generator = depthProcessor.getDepthImageGenerator();

                    // Update device info
                    if (generator instanceof ONNXDepthImageGenerator onnxGenerator) {
                        String deviceInfo = onnxGenerator.getCurrentDevice();
                        float fps = onnxGenerator.getCurrentFPS();

                        // Update UI
                        requireActivity().runOnUiThread(() -> {
                            if (deviceInfoText != null) {
                                deviceInfoText.setText(deviceInfo);
                            }

                            if (fpsText != null) {
                                fpsText.setText(String.format("%.1f FPS", fps));
                            }
                        });
                    } else {
                        // For non-ONNX generators, just show the type
                        String deviceInfo = generator.getClass().getSimpleName();

                        // Update UI
                        requireActivity().runOnUiThread(() -> {
                            if (deviceInfoText != null) {
                                deviceInfoText.setText(deviceInfo);
                            }

                            if (fpsText != null) {
                                fpsText.setText("N/A");
                            }
                        });
                    }
                }

                // Schedule the next update
                fpsUpdateHandler.postDelayed(this, FPS_UPDATE_INTERVAL_MS);
            }
        };

        // Start the updates
        fpsUpdateHandler.post(updateRunnable);
    }

    /**
     * Stop updating the device info and FPS display.
     * This should be called when the fragment is paused or destroyed.
     */
    private void stopDeviceInfoAndFPSUpdates() {
        if (fpsUpdateHandler != null) {
            fpsUpdateHandler.removeCallbacksAndMessages(null);
        }
    }
}
