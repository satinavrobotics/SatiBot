package com.satinavrobotics.satibot.robot;

import static com.satinavrobotics.satibot.navigation.NavigationUtils.getYawFromQuaternion;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.ar.core.Pose;
import com.google.ar.core.TrackingFailureReason;
import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.depth.BaseDepthFragment;
import com.satinavrobotics.satibot.arcore.rendering.DepthMapRenderer;
import com.satinavrobotics.satibot.depth.NavMapOverlay;
import com.satinavrobotics.satibot.depth.RobotParametersManager;
import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.env.StatusManager;
import com.satinavrobotics.satibot.livekit.LiveKitServer;
import com.satinavrobotics.satibot.navigation.WaypointsManager;
import com.satinavrobotics.satibot.navigation.UnifiedNavigationController;
import com.satinavrobotics.satibot.navigation.strategy.CombinedNavigationStrategy;
import com.satinavrobotics.satibot.main.MainViewModel;
import com.satinavrobotics.satibot.navigation.NavigationUtils;
import com.satinavrobotics.satibot.arcore.ArCoreListener;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;
import com.satinavrobotics.satibot.arcore.ImageFrame;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.vehicle.Vehicle;
import com.satinavrobotics.satibot.utils.Enums;

import livekit.org.webrtc.VideoFrame;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

import timber.log.Timber;

/**
 * Fragment for autonomous navigation between waypoints using depth images.
 * This fragment extends BaseDepthFragment to share common depth functionality.
 * Uses ARCoreHandler for AR session management and map loading.
 * Implements ArCoreListener to receive ARCore updates directly.
 * Registers only autonomous RPC methods (waypoint-cmd, status) for remote control.
 */
public class DepthNavigationFragment extends BaseDepthFragment implements ArCoreListener {
    private static final String TAG = DepthNavigationFragment.class.getSimpleName();

    // Navigation-specific UI elements
    private TextView costValuesText;
    private TextView navigationErrorText;
    private TextView navigationStatusText;
    private TextView waypointsCountText;
    private TextView velocityText;
    private TextView anchorCountText;
    private Button startStopButton;

    // Navigation state
    private boolean isNavigating = false;
    private WaypointsManager waypointsManager;

    // Unified navigation controller
    private UnifiedNavigationController unifiedNavigationController;
    private CombinedNavigationStrategy combinedNavigationStrategy;

    // LiveKit functionality
    private LiveKitServer liveKitServer;

    // Vehicle control (from ControlsFragment)
    protected Vehicle vehicle;
    protected MainViewModel mViewModel;
    protected SharedPreferencesManager preferencesManager;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_autonomous_navigation, container, false);

        // Set up navigation-specific button listeners
        setupNavigationButtons();

        return view;
    }

    /**
     * Find UI elements in the view. Implementation of abstract method from BaseDepthFragment.
     */
    @Override
    protected void findUIElements(View view) {
        // Find common UI elements (handled by BaseDepthFragment)
        surfaceView = view.findViewById(R.id.surfaceView);
        statusText = view.findViewById(R.id.statusText);
        fpsText = view.findViewById(R.id.fpsText);
        displayModeButton = view.findViewById(R.id.displayModeButton);

        // Find navigation-specific UI elements
        costValuesText = view.findViewById(R.id.costValuesText);
        navigationErrorText = view.findViewById(R.id.navigationErrorText);
        navigationStatusText = view.findViewById(R.id.navigationStatusText);
        waypointsCountText = view.findViewById(R.id.waypointsCountText);
        velocityText = view.findViewById(R.id.velocityText);
        anchorCountText = view.findViewById(R.id.anchorCountText);
        startStopButton = view.findViewById(R.id.startStopButton);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize vehicle control functionality (from ControlsFragment)
        initializeVehicleControl();

        // Get the waypoints manager
        waypointsManager = WaypointsManager.getInstance();

        // Initialize LiveKit functionality
        liveKitServer = LiveKitServer.getInstance(requireContext());
        ActivityResultLauncher<String[]> requestPermissionLauncher = liveKitServer.createPermissionLauncher(this);

        // Initialize navigation-specific UI
        initializeNavigationUI();

        // Update waypoints count (will show 0 initially until waypoints are received from LiveKit)
        updateWaypointsCount();

        // Register autonomous RPC methods for remote control
        registerAutonomousRpcMethods();

        // Set initial display mode to navigation
        currentDisplayMode = DepthMapRenderer.DISPLAY_MODE_NAV;
        updateDisplayModeButtonText(currentDisplayMode);
    }





    /**
     * Initialize vehicle control functionality (from ControlsFragment)
     */
    private void initializeVehicleControl() {
        // Get the ViewModel
        mViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        // Get the vehicle from the ViewModel
        vehicle = mViewModel.getVehicle().getValue();

        // Initialize preferences manager
        preferencesManager = new SharedPreferencesManager(requireContext());

        // Set up fragment result listeners for gamepad input (from ControlsFragment)
        setupGamepadListeners();
    }

    /**
     * Set up gamepad input listeners (from ControlsFragment)
     */
    private void setupGamepadListeners() {
        requireActivity()
            .getSupportFragmentManager()
            .setFragmentResultListener(
                Constants.GENERIC_MOTION_EVENT,
                this,
                (requestKey, result) -> {
                    MotionEvent motionEvent = result.getParcelable(Constants.DATA);
                    // Process joystick input to update controller state
                    if (vehicle != null && vehicle.getGameController() != null) {
                        vehicle.getGameController().processJoystickInput(motionEvent, -1);
                    }
                });
        requireActivity()
            .getSupportFragmentManager()
            .setFragmentResultListener(
                Constants.KEY_EVENT,
                this,
                (requestKey, result) -> {
                    KeyEvent event = result.getParcelable(Constants.DATA);
                    if (KeyEvent.ACTION_UP == event.getAction()) {
                        processKeyEvent(result.getParcelable(Constants.DATA));
                    }
                    // Process the button input to update controller state
                    if (vehicle != null && vehicle.getGameController() != null) {
                        vehicle.getGameController().processButtonInput(result.getParcelable(Constants.DATA));
                    }
                });
    }

    /**
     * Initialize navigation-specific UI elements
     */
    private void initializeNavigationUI() {
        // Initialize velocity display with zero values
        updateVelocityText(0.0f, 0.0f);

        // Initialize cost values display
        if (costValuesText != null) {
            costValuesText.setText("Costs: L:-.-- C:-.-- R:-.-");
        }

        // Initialize navigation error display
        if (navigationErrorText != null) {
            navigationErrorText.setText("Target: No waypoint");
        }

        // Initialize navigation status display
        if (navigationStatusText != null) {
            navigationStatusText.setText("Waiting for waypoints from LiveKit server (will auto-start)...");
        }
    }
    /**
     * Register only autonomous control RPC methods for this fragment.
     * This allows remote control of waypoint navigation without manual driving controls.
     */
    private void registerAutonomousRpcMethods() {
        if (liveKitServer != null) {
            try {
                // Try to register autonomous RPC methods
                boolean success = liveKitServer.registerAutonomousControlRpcMethods();
                if (success) {
                    Timber.d("Successfully registered autonomous RPC methods for depth navigation");
                } else {
                    Timber.d("Could not register autonomous RPC methods yet, will retry when connected");
                }
            } catch (Exception e) {
                Timber.e(e, "Failed to register autonomous RPC methods: %s", e.getMessage());
            }
        } else {
            Timber.w("LiveKit server is null, cannot register autonomous RPC methods");
        }
    }

    private void setupNavigationButtons() {
        // Set up start/stop button (primarily for manual stop, since navigation auto-starts)
        if (startStopButton != null) {
            startStopButton.setOnClickListener(v -> {
                if (isNavigating) {
                    stopNavigation();
                } else {
                    // Manual start - only if waypoints are available
                    if (waypointsManager != null && waypointsManager.hasNextWaypoint()) {
                        startNavigation();
                    } else {
                        // Debug: Add a test waypoint for debugging
                        Timber.d("No waypoints available, adding test waypoint for debugging");
                        addTestWaypoint();
                        navigationStatusText.setText("Added test waypoint - starting navigation...");
                        startNavigation();
                    }
                }
            });
        }
    }

    /**
     * Override from BaseDepthFragment to handle navigation-specific display mode changes
     */
    @Override
    protected void onDisplayModeChanged(int newDisplayMode) {
        // Always update the navigation map overlay regardless of display mode
        if (navMapOverlay != null) {
            // Make sure the overlays stay visible - check if view is available
            if (isAdded() && getView() != null) {
                View navMapOverlayView = getView().findViewById(R.id.navMapOverlay);
                View robotBoundsOverlayView = getView().findViewById(R.id.robotBoundsOverlay);

                if (navMapOverlayView != null) {
                    navMapOverlayView.setVisibility(View.VISIBLE);
                }

                if (robotBoundsOverlayView != null) {
                    robotBoundsOverlayView.setVisibility(View.VISIBLE);
                }
            }

            // Update the robot bounds and navigation map
            navMapOverlay.updateRobotBounds(null, true);
            navMapOverlay.update();

            Timber.d("Display mode changed to %d, overlays kept visible", newDisplayMode);
        }
    }

    /**
     * Process key events from gamepad (from ControlsFragment)
     */
    protected void processKeyEvent(KeyEvent keyCode) {
        if (Enums.ControlMode.getByID(preferencesManager.getControlMode()) == Enums.ControlMode.GAMEPAD) {
            switch (keyCode.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_X: // square
                    processControllerKeyData(Constants.CMD_INDICATOR_LEFT);
                    break;
                case KeyEvent.KEYCODE_BUTTON_Y: // triangle
                    processControllerKeyData(Constants.CMD_INDICATOR_STOP);
                    break;
                case KeyEvent.KEYCODE_BUTTON_B: // circle
                    processControllerKeyData(Constants.CMD_INDICATOR_RIGHT);
                    break;
                case KeyEvent.KEYCODE_BUTTON_A: // x
                    //processControllerKeyData(Constants.CMD_INDICATOR_HAZARD);
                    break;
                default:
                    break;
            }
        }
    }

    private void startNavigation() {
        if (!isNavigating) {
            isNavigating = true;
            startStopButton.setText("Stop");
            startStopButton.setBackgroundTintList(requireContext().getColorStateList(android.R.color.holo_red_light));
            navigationStatusText.setText("Starting waypoint navigation...");

            // Debug: Check waypoints availability
            int waypointCount = waypointsManager != null ? waypointsManager.getWaypointCount() : 0;
            Timber.d("Starting navigation with %d waypoints available", waypointCount);

            // Start waypoint navigation using the UnifiedNavigationController
            if (unifiedNavigationController != null) {
                unifiedNavigationController.startNavigation();
                Timber.d("UnifiedNavigationController.startNavigation() called");
            } else {
                Timber.e("UnifiedNavigationController is null, cannot start navigation");
                navigationStatusText.setText("Error: Navigation controller not initialized");
            }

            // Update velocity display immediately when navigation starts
            updateVelocityDisplayFromVehicle();
        }
    }

    private void stopNavigation() {
        if (isNavigating) {
            isNavigating = false;
            startStopButton.setText("Start");
            startStopButton.setBackgroundTintList(requireContext().getColorStateList(android.R.color.holo_green_light));
            navigationStatusText.setText("Navigation stopped");

            // Stop waypoint navigation using the UnifiedNavigationController
            if (unifiedNavigationController != null) {
                unifiedNavigationController.stopNavigation();
            }

            // Clear next goal info from StatusManager when navigation stops
            StatusManager.getInstance().updateNextGoalInfo(null);

            // Update velocity display to show zero
            updateVelocityText(0, 0);
        }
    }

    // Control update loop and processNextWaypoint methods removed
    // Navigation is now handled by UnifiedNavigationController

    private void updateWaypointsCount() {
        // Get the count of remaining waypoints from the WaypointsManager
        int remainingWaypoints = 0;
        if (waypointsManager != null) {
            remainingWaypoints = waypointsManager.getWaypointCount();
        }

        if (waypointsCountText != null) {
            waypointsCountText.setText(String.format(Locale.US, "%d remaining", remainingWaypoints));
        }
    }

    /**
     * Update the cost values display with current navigation costs
     */
    private void updateCostValuesDisplay() {
        if (costValuesText == null || combinedNavigationStrategy == null) {
            return;
        }

        // Get current cost values from the combined strategy's obstacle avoidance component
        float[] costValues = combinedNavigationStrategy.getObstacleStrategy().getCurrentCostValues(
            unifiedNavigationController != null ? unifiedNavigationController.getContext() : null);

        // Update the UI on the main thread
        requireActivity().runOnUiThread(() -> {
            if (costValuesText != null) {
                if (costValues != null && costValues.length == 3) {
                    // Display cost values with 2 decimal places
                    costValuesText.setText(String.format(Locale.US,
                        "Costs: L:%.2f C:%.2f R:%.2f",
                        costValues[0], costValues[1], costValues[2]));
                } else {
                    // Show placeholder when cost values are not available
                    costValuesText.setText("Costs: L:-.-- C:-.-- R:-.-");
                }
            }
        });
    }



    /**
     * Update the navigation error display with current position and heading errors
     *
     * @param currentPose The current robot pose
     */
    private void updateNavigationErrorDisplay(Pose currentPose) {
        if (navigationErrorText == null || currentPose == null) {
            return;
        }

        // Get the current waypoint from the UnifiedNavigationController
        if (unifiedNavigationController != null && unifiedNavigationController.getContext().getTargetWaypoint() != null) {
            try {
                JSONObject currentWaypoint = unifiedNavigationController.getContext().getTargetWaypoint();
                double waypointX = currentWaypoint.getDouble("x");
                double waypointZ = currentWaypoint.getDouble("z");

                // Get current position
                float[] currentTranslation = new float[3];
                currentPose.getTranslation(currentTranslation, 0);

                // Calculate position error
                float deltaX = (float) waypointX - currentTranslation[0];
                float deltaZ = (float) waypointZ - currentTranslation[2];
                float distanceError = (float) Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

                // Calculate heading error using unified NavigationUtils
                float angleToWaypoint = NavigationUtils.calculateAngleToWaypoint(deltaX, deltaZ);

                float[] currentRotation = new float[4];
                currentPose.getRotationQuaternion(currentRotation, 0);
                float currentYaw = getYawFromQuaternion(currentRotation);
                float headingError = NavigationUtils.calculateHeadingError(currentYaw, angleToWaypoint);
                float headingErrorDegrees = (float) Math.toDegrees(Math.abs(headingError));

                // Debug logging to understand the heading calculation (reduced frequency)
                if (System.currentTimeMillis() % 1000 < 50) { // Log every ~1 second
                    Timber.d("Heading Debug - Target: (%.2f, %.2f), Current: (%.2f, %.2f), " +
                            "DeltaX: %.2f, DeltaZ: %.2f, AngleToWaypoint: %.1f°, CurrentYaw: %.1f°, HeadingError: %.1f°",
                            waypointX, waypointZ, currentTranslation[0], currentTranslation[2],
                            deltaX, deltaZ, Math.toDegrees(angleToWaypoint), Math.toDegrees(currentYaw),
                            Math.toDegrees(headingError));
                }

                // Get turn direction indicator using NavigationUtils
                String turnArrow = NavigationUtils.getTurnDirectionIndicator(headingError, 5.0f, 1.0f);

                // Get current navigation state for display
                String navState = "";
                if (unifiedNavigationController != null) {
                    com.satinavrobotics.satibot.navigation.NavigationContext.NavigationState state =
                        unifiedNavigationController.getContext().getCurrentState();
                    switch (state) {
                        case TURNING:
                            navState = " [TURN]";
                            break;
                        case MOVING:
                            navState = " [MOVE]";
                            break;
                        case AVOIDING:
                            navState = " [AVOID]";
                            break;
                        case IDLE:
                            navState = " [IDLE]";
                            break;
                        case COMPLETED:
                            navState = " [DONE]";
                            break;
                    }
                }
                // Update StatusManager with next goal information
                try {
                    JSONObject nextGoalInfo = new JSONObject();
                    nextGoalInfo.put("relativeX", deltaX);
                    nextGoalInfo.put("relativeZ", deltaZ);
                    nextGoalInfo.put("distance", distanceError);
                    nextGoalInfo.put("headingError", headingError);
                    nextGoalInfo.put("headingErrorDegrees", headingErrorDegrees);
                    nextGoalInfo.put("targetX", waypointX);
                    nextGoalInfo.put("targetZ", waypointZ);

                    StatusManager.getInstance().updateNextGoalInfo(nextGoalInfo);
                } catch (JSONException e) {
                    Timber.e(e, "Error creating next goal info for status");
                }

                // Update the UI on the main thread
                String finalTurnArrow = turnArrow;
                String finalNavState = navState;
                requireActivity().runOnUiThread(() -> {
                    if (navigationErrorText != null) {
                        navigationErrorText.setText(String.format(Locale.US,
                            "Target: (%.2f, %.2f) Dist: %.2fm Heading: %.1f°%s%s",
                            waypointX, waypointZ, distanceError, headingErrorDegrees, finalTurnArrow, finalNavState));
                    }
                });

            } catch (JSONException e) {
                Timber.e(e, "Error calculating navigation errors");
                requireActivity().runOnUiThread(() -> {
                    if (navigationErrorText != null) {
                        navigationErrorText.setText("Target: Error reading waypoint");
                    }
                });
            }
        } else {
            // No current waypoint - clear next goal info from StatusManager
            //StatusManager.getInstance().updateNextGoalInfo(null);

            requireActivity().runOnUiThread(() -> {
                if (navigationErrorText != null) {
                    navigationErrorText.setText("Target: No waypoint");
                }
            });
        }
    }

    @Override
    protected void processControllerKeyData(String commandType) {
        if (commandType.equals(Constants.CMD_DRIVE)) {
            // Handle drive command
            // Check if fragment is still attached to an activity before using requireActivity()
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Update UI with current control values
                    float linear = vehicle.getLinearVelocity();
                    float angular = vehicle.getAngularVelocity();

                    // Always update the velocity display with actual vehicle values
                    updateVelocityText(linear, angular);
                });
            }
        } else if (commandType.equals(Constants.CMD_WAYPOINTS)) {
            // Waypoints received from LiveKit server - update UI and start navigation automatically
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Update waypoints count display
                    updateWaypointsCount();

                    // Update navigation status
                    int waypointCount = waypointsManager != null ? waypointsManager.getWaypointCount() : 0;
                    if (waypointCount > 0) {
                        if (isNavigating) {
                            // If already navigating, restart with new waypoints
                            navigationStatusText.setText(String.format(Locale.US,
                                "Received %d new waypoints - restarting navigation...", waypointCount));
                            Timber.i("Received %d new waypoints from LiveKit server, restarting navigation", waypointCount);

                            // Stop current navigation and restart with new waypoints
                            stopNavigation();
                            startNavigation();
                        } else {
                            // Start navigation with received waypoints
                            navigationStatusText.setText(String.format(Locale.US,
                                "Received %d waypoints from LiveKit - starting navigation...", waypointCount));
                            Timber.i("Received %d waypoints from LiveKit server, starting navigation automatically", waypointCount);

                            // Automatically start navigation when waypoints are received
                            startNavigation();
                        }
                    } else {
                        navigationStatusText.setText("No waypoints received from LiveKit server");
                        Timber.w("Received empty waypoint list from LiveKit server");

                        // Stop navigation if currently running and no waypoints received
                        if (isNavigating) {
                            stopNavigation();
                        }
                    }
                });
            }
        }
    }

    @Override
    protected void processUSBData(String data) {

    }

    /**
     * Override from BaseDepthFragment to initialize navigation-specific NavMapOverlay
     */
    @Override
    protected void initializeNavMapOverlay() {
        if (depthMapRenderer == null || depthProcessor == null) {
            Timber.e("Cannot initialize NavMapOverlay: depthMapRenderer or depthProcessor is null");
            return;
        }

        try {
            // Create NavMapOverlay with the depth processor
            // The depth processor already has a reference to the depth image generator
            navMapOverlay = new NavMapOverlay(depthProcessor.getDepthImageGenerator(), depthProcessor);

            // Initialize NavMapOverlay with the root view
            requireActivity().runOnUiThread(() -> {
                // Check if fragment is still attached and view is available
                if (!isAdded() || getView() == null) {
                    Timber.w("Fragment not attached or view is null, cannot initialize NavMapOverlay");
                    return;
                }

                View rootView = getView();
                navMapOverlay.initialize(rootView);

                // Get robot parameters from RobotParametersManager
                RobotParametersManager robotParams = RobotParametersManager.getInstance();

                // Set navigability threshold from RobotParametersManager
                int navigabilityThreshold = robotParams.getNavigabilityThreshold();
                navMapOverlay.setNavigabilityThreshold(navigabilityThreshold);

                // Get robot width from RobotParametersManager
                float robotWidth = robotParams.getRobotWidthMeters();

                // Log the robot width being used
                Timber.d("Setting robot width in NavMapOverlay: %.2f meters", robotWidth);

                // Set the robot width in NavMapOverlay
                navMapOverlay.setRobotWidthMeters(robotWidth);

                // Get frame dimensions if available from the depth processor
                if (depthProcessor != null && depthProcessor.getDepthImageGenerator() != null &&
                    depthProcessor.getDepthImageGenerator().isInitialized()) {
                    int width = depthProcessor.getWidth();
                    int height = depthProcessor.getHeight();
                    if (width > 0 && height > 0) {
                        // Update frame dimensions in RobotParametersManager
                        robotParams.updateFrameDimensions(width, height);
                        Timber.d("Updated frame dimensions in RobotParametersManager: %d x %d", width, height);
                    }
                }

                // Always make the overlays visible regardless of display mode
                // Check again to make sure we're still attached
                if (isAdded() && getView() != null) {
                    View navMapOverlayView = rootView.findViewById(R.id.navMapOverlay);
                    if (navMapOverlayView != null) {
                        navMapOverlayView.setVisibility(View.VISIBLE);
                    }

                    View robotBoundsOverlayView = rootView.findViewById(R.id.robotBoundsOverlay);
                    if (robotBoundsOverlayView != null) {
                        robotBoundsOverlayView.setVisibility(View.VISIBLE);
                    }
                }

                // Update robot bounds and always make visible (true parameter)
                navMapOverlay.updateRobotBounds(null, true);

                Timber.d("NavMapOverlay initialized and set to always visible");

                // Create UnifiedNavigationController
                unifiedNavigationController = new UnifiedNavigationController(vehicle, waypointsManager);

                // Create combined navigation strategy (handles both waypoint following and obstacle avoidance)
                combinedNavigationStrategy = new CombinedNavigationStrategy();

                // Add the combined strategy to the unified controller
                unifiedNavigationController.addStrategy(combinedNavigationStrategy);

                // Set up navigation listener for waypoint navigation events
                unifiedNavigationController.setNavigationListener(new UnifiedNavigationController.NavigationListener() {
                    @Override
                    public void onNavigationStateChanged(com.satinavrobotics.satibot.navigation.NavigationContext.NavigationState state, JSONObject waypoint) {
                        requireActivity().runOnUiThread(() -> {
                            switch (state) {
                                case TURNING:
                                    if (waypoint != null) {
                                        try {
                                            double x = waypoint.getDouble("x");
                                            double z = waypoint.getDouble("z");
                                            navigationStatusText.setText(String.format(Locale.US, "Turning to waypoint: %.2f, %.2f", x, z));
                                        } catch (JSONException e) {
                                            navigationStatusText.setText("Turning to waypoint");
                                        }
                                    }
                                    break;
                                case MOVING:
                                    if (waypoint != null) {
                                        try {
                                            double x = waypoint.getDouble("x");
                                            double z = waypoint.getDouble("z");
                                            navigationStatusText.setText(String.format(Locale.US, "Moving to waypoint: %.2f, %.2f", x, z));
                                        } catch (JSONException e) {
                                            navigationStatusText.setText("Moving to waypoint");
                                        }
                                    }
                                    break;
                                case AVOIDING:
                                    navigationStatusText.setText("Avoiding obstacles");
                                    break;
                                case IDLE:
                                    navigationStatusText.setText("Navigation idle");
                                    break;
                                case COMPLETED:
                                    navigationStatusText.setText("All waypoints completed");
                                    break;
                            }
                            updateWaypointsCount();

                            // Update velocity display when navigation state changes
                            if (isNavigating) {
                                updateVelocityDisplayFromVehicle();
                            }
                        });
                    }

                    @Override
                    public void onNavigationCompleted() {
                        requireActivity().runOnUiThread(() -> {
                            navigationStatusText.setText("All waypoints completed!");
                            stopNavigation(); // Stop the navigation UI state
                        });
                    }

                    @Override
                    public void onNavigationError(String error) {
                        requireActivity().runOnUiThread(() -> {
                            navigationStatusText.setText("Navigation error: " + error);
                            Timber.e("Waypoint navigation error: %s", error);
                        });
                    }
                });

                // Set up the navigability listener
                navMapOverlay.setNavigabilityListener(navigabilityData -> {
                    if (unifiedNavigationController != null && depthProcessor != null) {
                        // Get left and right navigability maps from depth processor
                        boolean[] leftNavigabilityMap = depthProcessor.getLeftNavigabilityMap();
                        boolean[] rightNavigabilityMap = depthProcessor.getRightNavigabilityMap();

                        // Update the unified navigation controller with all navigability data
                        unifiedNavigationController.updateNavigabilityData(
                            navigabilityData, leftNavigabilityMap, rightNavigabilityMap);

                        // Update cost values display after navigability data is updated
                        updateCostValuesDisplay();

                        // Log the navigability data occasionally
                        if (System.currentTimeMillis() % 2000 < 50) { // Log every ~2 seconds
                            StringBuilder sb = new StringBuilder("Navigability: ");
                            for (boolean isNavigable : navigabilityData) {
                                sb.append(isNavigable ? "1" : "0");
                            }
                            Timber.d(sb.toString());
                        }
                    }
                });

                Timber.d("UnifiedNavigationController initialized successfully");
            });
        } catch (Exception e) {
            Timber.e(e, "Error initializing NavMapOverlay: %s", e.getMessage());
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // Set this fragment as the ArCoreListener
        if (arCoreHandler != null) {
            arCoreHandler.setArCoreListener(this);
            arCoreHandler.setAnchorResolutionListener(this::updateAnchorCountText);
            Timber.d("Set this fragment as ArCoreListener in onStart");
        }

        // Try to register autonomous RPC methods again in case LiveKit connected after onViewCreated
        registerAutonomousRpcMethods();
    }

    @Override
    public void onPause() {
        super.onPause();

        try {
            // Stop navigation if it's running when paused
            if (isNavigating) {
                stopNavigation();
            }
        } catch (Exception e) {
            Timber.e(e, "Error during navigation pause: %s", e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Stop navigation if it's running
        if (isNavigating) {
            stopNavigation();
        }

        // Unregister autonomous RPC methods when exiting the fragment
        unregisterAutonomousRpcMethods();

        // Clear next goal info from StatusManager when fragment is destroyed
        StatusManager.getInstance().updateNextGoalInfo(null);

        // Clean up navigation-specific references
        navMapOverlay = null;
        unifiedNavigationController = null;
        combinedNavigationStrategy = null;
    }

    /**
     * Unregister autonomous control RPC methods when exiting this fragment.
     * This ensures clean separation between manual and autonomous control modes.
     */
    private void unregisterAutonomousRpcMethods() {
        if (liveKitServer != null) {
            try {
                boolean success = liveKitServer.unregisterAutonomousControlRpcMethods();
                if (success) {
                    Timber.d("Successfully unregistered autonomous RPC methods when exiting depth navigation");
                } else {
                    Timber.d("Could not unregister autonomous RPC methods (room may not be connected)");
                }
            } catch (Exception e) {
                Timber.e(e, "Failed to unregister autonomous RPC methods: %s", e.getMessage());
            }
        } else {
            Timber.w("LiveKit server is null, cannot unregister autonomous RPC methods");
        }
    }

    /**
     * Update the velocity display in the top right corner
     *
     * @param linear Linear velocity (forward/backward)
     * @param angular Angular velocity (rotation)
     */
    @SuppressLint("DefaultLocale")
    private void updateVelocityText(final float linear, final float angular) {
        if (velocityText == null) return;

        // Check if fragment is still attached to an activity
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // Check again in case fragment was detached while waiting for UI thread
                if (velocityText != null) {
                    // Make sure the velocity text is visible
                    velocityText.setVisibility(View.VISIBLE);

                    // Format with more precision and include both linear and angular values
                    velocityText.setText(String.format("Velocity: %.2f, Turn: %.2f", linear, angular));

                    // Log the velocity update for debugging
                    Timber.d("Updated velocity display: linear=%.2f, angular=%.2f", linear, angular);
                }
            });
        }
    }

    /**
     * Update the velocity display with current values from the vehicle
     * This method reads the current velocity from the vehicle and updates the display
     */
    private void updateVelocityDisplayFromVehicle() {
        if (vehicle != null) {
            float linear = vehicle.getLinearVelocity();
            float angular = vehicle.getAngularVelocity();

            // Debug: Log velocity values occasionally to avoid spam
            if (System.currentTimeMillis() % 2000 < 50) { // Log every ~2 seconds
                Timber.d("Vehicle velocity: linear=%.2f, angular=%.2f", linear, angular);
            }

            updateVelocityText(linear, angular);
        } else {
            Timber.w("Vehicle is null, cannot update velocity display");
        }
    }

    /**
     * Add a test waypoint for debugging purposes
     * This creates a simple waypoint 2 meters forward from the current position
     */
    private void addTestWaypoint() {
        try {
            JSONArray testWaypoints = new JSONArray();
            JSONObject testWaypoint = new JSONObject();

            // Create a simple test waypoint 2 meters forward (positive Z direction)
            testWaypoint.put("x", 0.0);
            testWaypoint.put("z", 2.0);
            testWaypoint.put("y", 0.0);

            testWaypoints.put(testWaypoint);

            if (waypointsManager != null) {
                waypointsManager.setWaypoints(testWaypoints);
                Timber.d("Added test waypoint: (0.0, 2.0, 0.0)");
                updateWaypointsCount();
            }
        } catch (JSONException e) {
            Timber.e(e, "Error creating test waypoint");
        }
    }

    /**
     * Update the anchor count display
     *
     * @param resolvedCount Number of anchors resolved so far
     * @param totalCount Total number of anchors to resolve
     */
    @SuppressLint("DefaultLocale")
    private void updateAnchorCountText(final int resolvedCount, final int totalCount) {
        if (anchorCountText == null) return;

        // Check if fragment is still attached to an activity
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // Check again in case fragment was detached while waiting for UI thread
                if (anchorCountText != null) {
                    // Make sure the anchor count text is visible
                    anchorCountText.setVisibility(View.VISIBLE);

                    // Format the anchor count display
                    anchorCountText.setText(String.format("Anchors: %d/%d", resolvedCount, totalCount));

                    // Log the anchor count update for debugging
                    Timber.d("Updated anchor count display: %d/%d", resolvedCount, totalCount);
                }
            });
        }
    }

    // ArCoreListener interface implementation

    @Override
    public void onArCoreUpdate(Pose currentPose, ImageFrame frame, CameraIntrinsics cameraIntrinsics, long timestamp) {
        try {

            // Process current pose in local coordinates if at least one anchor has been resolved
            if (currentPose != null && arCoreHandler.getResolvedAnchorsCount() > 0) {
                // Compute the origin pose from the closest resolved anchor
                Pose originPose = arCoreHandler.computeOriginPose();

                if (originPose != null) {
                    // Compute local coordinates based on the origin pose
                    Pose localPose = arCoreHandler.computeLocalPose(currentPose, originPose);

                    if (localPose != null) {
                        // Create JSON with local pose data
                        JSONObject localPoseJson = arCoreHandler.createLocalPoseJson(localPose);

                        // Update status manager with local pose
                        StatusManager.getInstance().updateARCorePose(localPoseJson);

                        // Update the UnifiedNavigationController with the current pose
                        if (unifiedNavigationController != null) {
                            unifiedNavigationController.updateCurrentPose(localPose);
                        }

                        // Update the cost values display
                        updateCostValuesDisplay();

                        // Update navigation error display if we have a current waypoint
                        updateNavigationErrorDisplay(localPose);

                        // Update velocity display with current vehicle values when navigation is active
                        if (isNavigating) {
                            updateVelocityDisplayFromVehicle();
                        }
                    }
                }
            } else if (currentPose != null) {
                // Update cost values display even when no anchors are resolved
                updateCostValuesDisplay();
            }

            // Get frame dimensions from the depth processor and update RobotParametersManager
            if (depthProcessor != null) {
                int width = depthProcessor.getWidth();
                int height = depthProcessor.getHeight();
                if (width > 0 && height > 0) {
                    RobotParametersManager.getInstance().updateFrameDimensions(width, height);
                }
            }

            // Use the current display mode
            boolean isNavMode = currentDisplayMode == DepthMapRenderer.DISPLAY_MODE_NAV;

            // Update robot bounds and NavMapOverlay in a coordinated way
            if (navMapOverlay != null) {
                try {
                    // Update the NavMapOverlay with camera intrinsics
                    // This will also update the robot bounds overlay
                    navMapOverlay.updateRobotBounds(cameraIntrinsics, isNavMode);

                    // Always update the navigation map data in AutonomousNavigationFragment
                    navMapOverlay.update();
                } catch (Exception ex) {
                    Timber.w("Failed to update NavMapOverlay with camera intrinsics: %s", ex.getMessage());
                }
            }

            // Ensure the overlays remain visible - but check if fragment is still attached
            if (isAdded() && getView() != null) {
                requireActivity().runOnUiThread(() -> {
                    // Check again on UI thread as fragment state might have changed
                    if (isAdded() && getView() != null) {
                        View navMapOverlayView = getView().findViewById(R.id.navMapOverlay);
                        View robotBoundsOverlayView = getView().findViewById(R.id.robotBoundsOverlay);

                        if (navMapOverlayView != null && navMapOverlayView.getVisibility() != View.VISIBLE) {
                            navMapOverlayView.setVisibility(View.VISIBLE);
                        }

                        if (robotBoundsOverlayView != null && robotBoundsOverlayView.getVisibility() != View.VISIBLE) {
                            robotBoundsOverlayView.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }

            // FPS is now calculated in the FPS update listener

            // Show depth source in status text occasionally
            if (System.currentTimeMillis() % 5000 < 50) { // Update roughly every 5 seconds
                String depthSourceName = getDepthSourceName(currentDepthSource);
                updateStatusText("Using " + depthSourceName + " depth source");
            }
        } catch (Exception e) {
            Timber.e(e, "Exception during ARCore update: %s", e.getMessage());
        }
    }

    @Override
    public void onRenderedFrame(VideoFrame.I420Buffer frame, long timestamp) {
        // Not used in this fragment
    }

    @Override
    public void onArCoreTrackingFailure(long timestamp, TrackingFailureReason trackingFailureReason) {
        // Do not display tracking issues in autonomous navigation fragment
        // Just log them for debugging purposes
        Timber.d("ARCore tracking failure: %s", trackingFailureReason.toString());
    }

    @Override
    public void onArCoreSessionPaused(long timestamp) {
        // Do not display session paused messages in autonomous navigation fragment
        // Just log them for debugging purposes
        Timber.d("ARCore session paused");
    }
}
