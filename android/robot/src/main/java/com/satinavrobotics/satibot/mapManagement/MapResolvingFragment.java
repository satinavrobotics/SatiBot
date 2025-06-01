package com.satinavrobotics.satibot.mapManagement;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Earth;
import com.google.ar.core.Frame;
import com.google.ar.core.GeospatialPose;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.VpsAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.databinding.FragmentMapResolvingBinding;

import com.satinavrobotics.satibot.env.CameraPermissionHelper;
import com.satinavrobotics.satibot.env.DisplayRotationHelper;
import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.googleServices.GoogleServices;
import com.satinavrobotics.satibot.mapManagement.rendering.BackgroundRenderer;
import com.satinavrobotics.satibot.mapManagement.rendering.ObjectRenderer;
import com.satinavrobotics.satibot.mapManagement.rendering.PlaneRenderer;
import com.satinavrobotics.satibot.mapManagement.rendering.PointCloudRenderer;
import com.satinavrobotics.satibot.mapManagement.rendering.WaypointRenderer;
import com.satinavrobotics.satibot.googleServices.GoogleSignInCallback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

public class MapResolvingFragment extends Fragment implements GLSurfaceView.Renderer {
    private static final String TAG = "MapResolvingFragment";
    private static final float[] OBJECT_COLOR = new float[] {0.0f, 1.0f, 0.0f, 1.0f}; // Green color for resolved anchors
    private static final float[] EARTH_OBJECT_COLOR = new float[] {0.0f, 0.0f, 1.0f, 1.0f}; // Blue color for Earth mode
    private static final float WAYPOINT_SELECTION_DISTANCE = 1.0f; // Maximum distance in meters to select a waypoint - increased for easier selection

    // UI elements
    private FragmentMapResolvingBinding binding;
    private TextView statusText;
    private TextView resolvedCountText;
    private TextView waypointModeText;
    private TextView waypointCountText;
    private Button doneButton;
    private Button clearWaypointsButton;
    private ImageView crosshair;
    private LinearLayout waypointControls;
    private LinearLayout waypointButtons;
    private RadioGroup waypointModeGroup;
    private RadioButton placeWaypointMode;
    private RadioButton connectWaypointMode;
    private final SnackbarHelper snackbarHelper = new SnackbarHelper();

    // ARCore components
    private boolean installRequested;
    private Session session;
    private DisplayRotationHelper displayRotationHelper;
    private SharedPreferences sharedPreferences;
    private TrackingStateHelper trackingStateHelper;
    private GestureDetector gestureDetector;
    private final AtomicBoolean queuedTap = new AtomicBoolean(false);

    // Rendering components
    private GLSurfaceView surfaceView;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();
    private final ObjectRenderer anchorRenderer = new ObjectRenderer();
    private final WaypointRenderer waypointRenderer = new WaypointRenderer();

    // Temporary matrices for rendering
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] anchorMatrix = new float[16];

    // Cloud anchor components
    private final MapResolvingManager mapResolvingManager = new MapResolvingManager();
    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private OnBackPressedCallback onBackPressedCallback;
    private SharedPreferencesManager preferencesManager;

    // Map data
    private Map currentMap;
    private boolean isResolvingAnchors = false;
    private int totalAnchorsToResolve = 0;
    private int resolvedAnchorsCount = 0;

    // Waypoint data
    private WaypointGraph waypointGraph;
    private boolean waypointModeEnabled = false;
    private boolean isPlacementMode = true; // true = place waypoints, false = connect waypoints
    private Pose lastCameraPose;

    // Geospatial API components
    private Earth earth;
    private boolean isEarthMode = false;
    private boolean isNoMapMode = false;
    private TextView geospatialInfoText;
    private TextView vpsStatusText;
    private FusedLocationProviderClient fusedLocationClient;
    private GeospatialPose lastGeospatialPose;
    private boolean isVpsAvailable = false;
    private boolean isCheckingVps = false;

    // Dense mapping components
    private boolean isDenseMappingMode = false;
    private DenseMappingManager denseMappingManager;
    private LinearLayout denseMappingControls;
    private TextView denseMappingStatus;
    private TextView denseMappingInfo;
    private Button recordButton;
    private Button pauseButton;
    private Button finishButton;
    private GoogleServices googleServices;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize Firebase components
        db = FirebaseFirestore.getInstance();
        currentUser = FirebaseAuth.getInstance().getCurrentUser();

        // Initialize DisplayRotationHelper
        displayRotationHelper = new DisplayRotationHelper(requireActivity());

        // Initialize SharedPreferences
        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);
        preferencesManager = new SharedPreferencesManager(requireContext());

        // Initialize FusedLocationProviderClient for Geospatial API
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        // Initialize GoogleServices for Drive upload
        googleServices = new GoogleServices(requireActivity(), requireContext(), new GoogleSignInCallback() {
            @Override
            public void onSignInSuccess(FirebaseUser account) {}
            @Override
            public void onSignInFailed(Exception exception) {}
            @Override
            public void onSignOutSuccess() {}
            @Override
            public void onSignOutFailed(Exception exception) {}
        });

        // Check if we're in dense mapping mode
        Bundle args = getArguments();
        if (args != null) {
            isDenseMappingMode = args.getBoolean("dense_mapping_mode", false);
        }

        // Handle back button press
        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Clean up resources before navigating back
                cleanupResources();

                // Navigate directly to the map management fragment instead of using popBackStack
                // This prevents navigation issues when returning to this fragment
                if (isAdded() && getView() != null) {
                    Navigation.findNavController(getView()).navigate(R.id.mapManagementFragment);
                }
            }
        };

        requireActivity().getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMapResolvingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Force portrait orientation for this fragment
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Initialize UI components
        surfaceView = binding.surfaceview;
        statusText = binding.statusText;
        resolvedCountText = binding.resolvedCountText;
        waypointModeText = binding.waypointModeText;
        waypointCountText = binding.waypointCountText;
        geospatialInfoText = binding.geospatialInfoText;
        vpsStatusText = binding.vpsStatusText;
        doneButton = binding.doneButton;
        crosshair = binding.crosshair;
        waypointControls = binding.waypointControls;
        waypointButtons = binding.waypointButtons;
        waypointModeGroup = binding.waypointModeGroup;
        placeWaypointMode = binding.placeWaypointMode;
        connectWaypointMode = binding.connectWaypointMode;
        clearWaypointsButton = binding.clearWaypointsButton;

        // Initially hide the waypoint-related UI elements
        crosshair.setVisibility(View.GONE);
        waypointControls.setVisibility(View.GONE);
        waypointButtons.setVisibility(View.GONE);
        waypointCountText.setVisibility(View.GONE);
        waypointModeText.setVisibility(View.GONE);

        // Initially hide the geospatial info and VPS status texts
        geospatialInfoText.setVisibility(View.GONE);
        vpsStatusText.setVisibility(View.GONE);

        // Initialize dense mapping UI elements
        denseMappingControls = binding.denseMappingControls;
        denseMappingStatus = binding.denseMappingStatus;
        denseMappingInfo = binding.denseMappingInfo;
        recordButton = binding.recordButton;
        pauseButton = binding.pauseButton;
        finishButton = binding.finishButton;

        // Initially hide dense mapping controls
        denseMappingControls.setVisibility(isDenseMappingMode ? View.VISIBLE : View.GONE);

        // Initialize TrackingStateHelper
        trackingStateHelper = new TrackingStateHelper(requireActivity());

        // Initialize waypoint graph
        waypointGraph = new WaypointGraph();

        // Set up renderer
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending

        // Set up render mode
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);
        installRequested = false;

        // Set up gesture detector for tap events
        gestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // Queue tap for processing in the render thread
                queuedTap.set(true);
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        // Set up touch listener for the surface view
        surfaceView.setOnTouchListener((v, event) -> {
            return gestureDetector.onTouchEvent(event);
        });

        // Set up button listeners
        doneButton.setOnClickListener(v -> {
            // Save waypoints before cleaning up
            if (waypointModeEnabled && waypointGraph.getWaypointCount() > 0) {
                saveWaypointsToFirebase();
            } else {
                // Clean up resources before navigating back
                cleanupResources();

                // Navigate directly to the map management fragment instead of using popBackStack
                if (isAdded() && getView() != null) {
                    Navigation.findNavController(getView()).navigate(R.id.mapManagementFragment);
                }
            }
        });

        // Set up waypoint mode radio group listener
        waypointModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.place_waypoint_mode) {
                isPlacementMode = true;
                waypointModeText.setText("Point the center dot at a horizontal surface to place waypoints");
                waypointGraph.setSelectedWaypoint(null);
            } else if (checkedId == R.id.connect_waypoint_mode) {
                isPlacementMode = false;
                waypointModeText.setText("Tap waypoints to connect them");
                waypointGraph.setSelectedWaypoint(null);
            }
        });

        // Set up clear waypoints button
        clearWaypointsButton.setOnClickListener(v -> {
            waypointGraph.clear();
            updateWaypointCountText();
            snackbarHelper.showMessage(requireActivity(), "Waypoints cleared");
        });

        // Set up dense mapping buttons
        recordButton.setOnClickListener(v -> {
            if (denseMappingManager != null) {
                if (!denseMappingManager.isRecording()) {
                    // Start recording
                    denseMappingManager.startRecording();
                    recordButton.setText("Stop Recording");
                    pauseButton.setEnabled(true);
                    finishButton.setEnabled(false);
                    denseMappingStatus.setText("Recording...");
                } else if (denseMappingManager.isPaused()) {
                    // Resume recording
                    denseMappingManager.resumeRecording();
                    recordButton.setText("Stop Recording");
                    pauseButton.setText("Pause");
                    denseMappingStatus.setText("Recording...");
                } else {
                    // Stop recording
                    denseMappingManager.pauseRecording();
                    recordButton.setText("Resume Recording");
                    pauseButton.setText("Pause");
                    finishButton.setEnabled(true);
                    denseMappingStatus.setText("Paused");
                }
            }
        });

        pauseButton.setOnClickListener(v -> {
            if (denseMappingManager != null && denseMappingManager.isRecording()) {
                if (!denseMappingManager.isPaused()) {
                    // Pause recording
                    denseMappingManager.pauseRecording();
                    pauseButton.setText("Resume");
                    recordButton.setText("Stop Recording");
                    finishButton.setEnabled(true);
                    denseMappingStatus.setText("Paused");
                } else {
                    // Resume recording
                    denseMappingManager.resumeRecording();
                    pauseButton.setText("Pause");
                    finishButton.setEnabled(false);
                    denseMappingStatus.setText("Recording...");
                }
            }
        });

        finishButton.setOnClickListener(v -> {
            if (denseMappingManager != null) {
                // Finish recording and upload
                denseMappingManager.stopRecording();
                recordButton.setEnabled(false);
                pauseButton.setEnabled(false);
                finishButton.setEnabled(false);
                denseMappingStatus.setText("Finalizing and uploading...");
            }
        });

        // Load the selected map
        loadSelectedMap();
    }

    private void loadSelectedMap() {
        String mapId = preferencesManager.getCurrentMapId();
        if (mapId == null) {
            showError("No map selected. Please select a map first.");
            // Clean up and navigate back
            cleanupResources();
            if (isAdded() && getView() != null) {
                Navigation.findNavController(getView()).navigate(R.id.mapManagementFragment);
            }
            return;
        }

        // Check if this is the special Earth map
        if ("earth".equals(mapId)) {
            isEarthMode = true;
            statusText.setText("Earth Mode - Using Geospatial API");

            // Create a special Earth map
            currentMap = new Map();
            currentMap.setId("earth");
            currentMap.setName("Earth");

            // Show the geospatial info and VPS status texts
            geospatialInfoText.setVisibility(View.VISIBLE);
            geospatialInfoText.setText("Initializing Geospatial API...");

            vpsStatusText.setVisibility(View.VISIBLE);
            vpsStatusText.setText("Checking VPS availability...");

            // No anchors to resolve in Earth mode
            resolvedCountText.setVisibility(View.GONE);
        }
        // Check if this is the No Map option
        else if ("no_map".equals(mapId)) {
            isNoMapMode = true;
            statusText.setText("No Map Mode - Using Direct ARCore Pose");

            // Create a special No Map object
            currentMap = new Map();
            currentMap.setId("no_map");
            currentMap.setName("No Map");

            // Hide the geospatial info and VPS status texts
            geospatialInfoText.setVisibility(View.GONE);
            vpsStatusText.setVisibility(View.GONE);

            // No anchors to resolve in No Map mode
            resolvedCountText.setVisibility(View.GONE);

            // Check if Geospatial API is supported on this device
            if (session != null) {
                if (session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                    updateVpsStatusText("Geospatial API: Available", false);
                    // Start checking VPS availability at current location
                    checkVpsAvailability();
                } else {
                    updateVpsStatusText("Geospatial API: Not supported on this device", true);
                }
            }

            return;
        }

        // Not Earth mode, proceed with normal map loading
        isEarthMode = false;
        geospatialInfoText.setVisibility(View.GONE);
        statusText.setText("Loading map...");

        db.collection("maps").document(mapId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        currentMap = documentSnapshot.toObject(Map.class);
                        if (currentMap != null) {
                            // Set the map ID (it's not automatically set by Firestore)
                            currentMap.setId(documentSnapshot.getId());

                            // Update UI with map info
                            updateMapInfo();

                            // Start resolving anchors when session is ready
                            if (session != null) {
                                resolveAnchors();
                            }
                        } else {
                            showError("Failed to load map data.");
                            // Clean up and navigate back
                            cleanupResources();
                            if (isAdded() && getView() != null) {
                                Navigation.findNavController(getView()).navigate(R.id.mapManagementFragment);
                            }
                        }
                    } else {
                        showError("Map not found.");
                        // Clean up and navigate back
                        cleanupResources();
                        if (isAdded() && getView() != null) {
                                Navigation.findNavController(getView()).navigate(R.id.mapManagementFragment);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    showError("Error loading map: " + e.getMessage());
                    // Clean up and navigate back
                    cleanupResources();
                    if (isAdded() && getView() != null) {
                                Navigation.findNavController(getView()).navigate(R.id.mapManagementFragment);
                    }
                });
    }

    private void updateMapInfo() {
        if (currentMap == null) return;

        // Handle Earth mode and No Map mode differently
        if (isEarthMode || isNoMapMode) {
            requireActivity().runOnUiThread(() -> {
                statusText.setText("Map: " + currentMap.getName());
                // Hide the resolved count text in special modes
                resolvedCountText.setVisibility(View.GONE);
            });
            return;
        }

        // Normal map mode
        totalAnchorsToResolve = currentMap.getAnchors().size();
        resolvedAnchorsCount = 0;

        requireActivity().runOnUiThread(() -> {
            statusText.setText("Map: " + currentMap.getName());
            resolvedCountText.setVisibility(View.VISIBLE);
            updateResolvedCountText();
        });
    }

    private void updateResolvedCountText() {
        resolvedCountText.setText("Resolved: " + resolvedAnchorsCount + "/" + totalAnchorsToResolve);
    }

    private void resolveAnchors() {
        // Skip anchor resolution for Earth mode or No Map mode
        if (isEarthMode || isNoMapMode) {
            return;
        }

        if (currentMap == null || currentMap.getAnchors() == null || currentMap.getAnchors().isEmpty()) {
            showError("No anchors to resolve in this map.");
            return;
        }

        if (isResolvingAnchors) {
            return; // Already resolving
        }

        isResolvingAnchors = true;
        resolvedAnchorsCount = 0;
        updateResolvedCountText();

        // Show resolving message
        try {
            if (isAdded() && getActivity() != null) {
                snackbarHelper.showMessage(getActivity(), "Resolving anchors...");
            }
        } catch (Exception e) {
            Timber.e(e, "Error showing resolving message");
        }

        // Create a list of cloud anchor IDs to resolve
        List<String> cloudAnchorIds = new ArrayList<>();
        for (Map.Anchor anchor : currentMap.getAnchors()) {
            cloudAnchorIds.add(anchor.getCloudAnchorId());
        }

        // Create a listener for the resolving operations
        MapResolvingManager.CloudAnchorResolveListener listener =
                (cloudAnchorId, anchor, cloudAnchorState) -> {
            try {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            if (cloudAnchorState == CloudAnchorState.SUCCESS && anchor != null) {
                                resolvedAnchorsCount++;
                                updateResolvedCountText();

                                // Show success message for each resolved anchor for a short duration
                                if (isAdded() && getActivity() != null) {
                                    snackbarHelper.showMessageForShortDuration(getActivity(),
                                            "Resolved anchor " + resolvedAnchorsCount + "/" + totalAnchorsToResolve);

                                    // If all anchors are resolved, show completion message for a few seconds
                                    if (resolvedAnchorsCount == totalAnchorsToResolve) {
                                        snackbarHelper.showMessageForShortDuration(getActivity(),
                                                "All anchors resolved successfully!");

                                        // Hide the message after 3 seconds
                                        new android.os.Handler().postDelayed(() -> {
                                            try {
                                                if (isAdded() && getActivity() != null) {
                                                    snackbarHelper.hide(getActivity());
                                                }
                                            } catch (Exception e) {
                                                Timber.e(e, "Error hiding snackbar");
                                            }
                                        }, 3000); // 3 seconds
                                    }
                                }
                            } else if (cloudAnchorState.isError()) {
                                // Show error message for failed anchors
                                Timber.e("Error resolving anchor %s: %s", cloudAnchorId, cloudAnchorState);
                                if (isAdded() && getActivity() != null) {
                                    snackbarHelper.showError(getActivity(),
                                            "Error resolving anchor: " + cloudAnchorState);
                                }
                            }
                        } catch (Exception e) {
                            Timber.e(e, "Error handling anchor resolution result");
                        }
                    });
                }
            } catch (Exception e) {
                Timber.e(e, "Error in cloud anchor resolve listener");
            }
        };

        // Resolve all anchors
        mapResolvingManager.resolveCloudAnchors(cloudAnchorIds, listener);
    }

    @Override
    public void onResume() {
        super.onResume();
        createSession();
        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onDestroyView() {
        // Clean up resources before the view is destroyed
        cleanupResources();

        // Call super after cleanup to ensure proper fragment lifecycle
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        try {
            // Clean up resources
            cleanupResources();

            // Additional cleanup specific to onDestroy
            if (session != null) {
                session.close();
                session = null;
            }

            // Reset orientation when leaving this fragment
            if (isAdded() && getActivity() != null) {
                getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }

            // Remove callback
            if (onBackPressedCallback != null) {
                onBackPressedCallback.remove();
            }
        } catch (Exception e) {
            Timber.e(e, "Error during fragment destruction");
        } finally {
            super.onDestroy();
        }
    }

    private void createSession() {
        if (session == null) {
            Exception exception = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions
                if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
                    CameraPermissionHelper.requestCameraPermission(requireActivity());
                    return;
                }

                // Create the session
                session = new Session(requireContext());
                Config config = new Config(session);

                // Enable cloud anchors - this is critical for resolving cloud anchors
                config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);

                // Enable only horizontal plane detection
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);

                // Enable Geospatial API if in Earth mode
                if (isEarthMode) {
                    // Check if Geospatial mode is supported on this device
                    if (session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                        config.setGeospatialMode(Config.GeospatialMode.ENABLED);
                        Timber.d("Geospatial API enabled");
                    } else {
                        showError("Geospatial API is not supported on this device");
                        isEarthMode = false;
                    }
                }

                // Apply the configuration
                session.configure(config);

                // Set the session in the manager
                mapResolvingManager.setSession(session);

                // If we already have a map loaded, start resolving anchors
                if (currentMap != null) {
                    resolveAnchors();
                }

            } catch (UnavailableArcoreNotInstalledException |
                     UnavailableApkTooOldException |
                     UnavailableSdkTooOldException e) {
                exception = e;
            } catch (UnavailableUserDeclinedInstallationException |
                     UnavailableDeviceNotCompatibleException e) {
                throw new RuntimeException(e);
            }

            if (exception != null) {
                statusText.setText("Error creating AR session: " + exception.getMessage());
                Timber.e(exception, "Exception creating session");
                return;
            }
        }

        // Resume the ARCore session
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            statusText.setText("Camera not available. Try restarting the app.");
            session = null;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Enable depth testing for proper 3D rendering
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        // Enable blending for transparent objects
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        try {
            // Initialize the background renderer
            backgroundRenderer.createOnGlThread(requireContext());

            // Load the plane renderer with the texture
            try {
                planeRenderer.createOnGlThread(requireContext(), "models/trigrid.png");
            } catch (IOException e) {
                Timber.e(e, "Failed to load plane texture");
            }

            pointCloudRenderer.createOnGlThread(requireContext());

            // Load the anchor renderer with the 3D model
            try {
                anchorRenderer.createOnGlThread(requireContext(), "models/anchor.obj", "models/anchor.png");
                anchorRenderer.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
                anchorRenderer.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
            } catch (IOException e) {
                Timber.e(e, "Failed to load 3D models, using fallback");
                try {
                    anchorRenderer.createOnGlThread(requireContext(), "models/andy.obj", "models/andy.png");
                    anchorRenderer.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
                    anchorRenderer.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
                } catch (IOException ex) {
                    Timber.e(ex, "Failed to load fallback 3D model");
                }
            }

            // Initialize the waypoint renderer
            try {
                waypointRenderer.createOnGlThread(requireContext());
            } catch (IOException e) {
                Timber.e(e, "Failed to initialize waypoint renderer");
            }
        } catch (IOException e) {
            Timber.e(e, "Failed to read an asset file");
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }

        // Notify ARCore session that the view size changed
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            // Set the camera texture name in the session
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // Obtain the current frame from ARSession
            Frame frame = session.update();
            Camera camera = frame.getCamera();
            TrackingState cameraTrackingState = camera.getTrackingState();

            // Store the current camera pose for waypoint placement
            lastCameraPose = camera.getPose();

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops
            trackingStateHelper.updateKeepScreenOnFlag(cameraTrackingState);

            // Draw camera preview
            backgroundRenderer.draw(frame);

            // If not tracking, don't draw 3D objects
            if (cameraTrackingState == TrackingState.PAUSED) {
                return;
            }

            // Get camera and projection matrices
            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

            // Visualize tracked points
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                pointCloudRenderer.update(pointCloud);
                pointCloudRenderer.draw(viewMatrix, projectionMatrix);
            }

            // Visualize planes
            Collection<Plane> allPlanes = session.getAllTrackables(Plane.class);
            planeRenderer.drawPlanes(allPlanes, camera.getDisplayOrientedPose(), projectionMatrix);

            // Visualize resolved anchors
            float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            // Handle Earth mode, No Map mode, or normal cloud anchor mode
            if (isEarthMode) {
                // In Earth mode, get the Earth object and check if it's tracking
                earth = session.getEarth();
                if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
                    // Get the camera's geospatial pose
                    GeospatialPose cameraGeospatialPose = earth.getCameraGeospatialPose();
                    lastGeospatialPose = cameraGeospatialPose;

                    // Update the geospatial info text with the current pose
                    updateGeospatialInfoText(cameraGeospatialPose);

                    // Create a visual indicator at the current location
                    Pose cameraPose = camera.getPose();
                    cameraPose.toMatrix(anchorMatrix, 0);

                    // Draw a 3D object at the camera position
                    float scaleFactor = 0.5f; // Smaller scale for Earth mode
                    anchorRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
                    anchorRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, EARTH_OBJECT_COLOR);
                } else if (earth != null) {
                    // Earth is not tracking yet
                    Earth.EarthState earthState = earth.getEarthState();
                    requireActivity().runOnUiThread(() -> {
                        geospatialInfoText.setText("Waiting for Earth tracking: " + earthState);

                        // Update VPS status based on Earth state
                        if (earthState == Earth.EarthState.ENABLED) {
                            updateVpsStatusText("Geospatial API: Enabled, waiting for tracking", false);
                        } else if (earthState == Earth.EarthState.ERROR_INTERNAL) {
                            updateVpsStatusText("Geospatial API: Internal error", true);
                        } else if (earthState == Earth.EarthState.ERROR_NOT_AUTHORIZED) {
                            updateVpsStatusText("Geospatial API: Not authorized", true);
                        } else if (earthState == Earth.EarthState.ERROR_RESOURCE_EXHAUSTED) {
                            updateVpsStatusText("Geospatial API: Resource exhausted", true);
                        } else {
                            updateVpsStatusText("Geospatial API: " + earthState, true);
                        }

                        // Try to check VPS availability if we haven't already
                        if (!isCheckingVps && !isVpsAvailable) {
                            checkVpsAvailability();
                        }
                    });
                }
            } else if (isNoMapMode) {
                // In No Map mode, just use the direct ARCore pose
                // Update status text with current tracking state
                requireActivity().runOnUiThread(() -> {
                    statusText.setText("No Map Mode - Using Direct ARCore Pose");
                });

                // Optionally, visualize the camera position with a marker
                Pose cameraPose = camera.getPose();
                cameraPose.toMatrix(anchorMatrix, 0);

                // Draw a 3D object at the camera position with a different color
                float scaleFactor = 0.5f;
                anchorRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
                anchorRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, new float[]{1.0f, 0.0f, 1.0f, 1.0f}); // Purple color
            } else {
                // Normal cloud anchor mode - draw all resolved anchors
                for (MapResolvingManager.ResolvedAnchor resolvedAnchor : mapResolvingManager.getResolvedAnchors()) {
                    Anchor anchor = resolvedAnchor.getAnchor();
                    if (anchor.getTrackingState() == TrackingState.TRACKING) {
                        // Get the current pose of the Anchor in world space
                        anchor.getPose().toMatrix(anchorMatrix, 0);

                        // Update the model matrix and draw the 3D object
                        float scaleFactor = 1.0f;
                        anchorRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
                        anchorRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR);
                    }
                }

                // Check if all anchors are resolved and enable waypoint mode if needed
                if (!waypointModeEnabled && !isEarthMode && !isDenseMappingMode &&
                    resolvedAnchorsCount > 0 && resolvedAnchorsCount == totalAnchorsToResolve) {
                    enableWaypointMode();
                }

                // Check if enough anchors are resolved for dense mapping
                if (isDenseMappingMode  && resolvedAnchorsCount >= 2 && !recordButton.isEnabled()) {
                    enableDenseMappingMode();
                }

                // Handle waypoint mode if enabled
                if (waypointModeEnabled) {
                    // Draw waypoints and connections
                    waypointRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, waypointGraph);

                    // Handle tap events for waypoint placement or connection
                    handleTapEvent(frame, camera);
                }

                // Process frame for dense mapping if recording
                if (isDenseMappingMode && denseMappingManager != null && denseMappingManager.isRecording()) {
                    denseMappingManager.processFrame(frame);
                }
            }

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions
            Timber.e(t, "Exception on the OpenGL thread");
        }
    }

    /**
     * Handles tap events for waypoint placement or connection.
     *
     * @param frame The current AR frame
     * @param camera The AR camera
     */
    private void handleTapEvent(Frame frame, Camera camera) {
        if (!queuedTap.get() || !waypointModeEnabled) {
            return;
        }

        // Reset the tap flag
        queuedTap.set(false);

        if (isPlacementMode) {
            // Place a waypoint on a horizontal surface
            placeWaypoint(camera.getPose(), frame);
        } else {
            // Try to select a waypoint for connection
            selectWaypointForConnection(camera.getPose());
        }
    }

    /**
     * Places a waypoint on a horizontal surface where the user tapped.
     *
     * @param cameraPose The current camera pose
     * @param frame The current AR frame for hit testing
     */
    private void placeWaypoint(Pose cameraPose, Frame frame) {
        if (mapResolvingManager.getResolvedAnchors().isEmpty()) {
            snackbarHelper.showMessage(requireActivity(), "No resolved anchors available for reference");
            return;
        }

        try {
            // Get the tap location (center of the screen when the user tapped)
            // ARCore hit test expects normalized screen coordinates (0.0-1.0)
            // where (0,0) is top-left and (1,1) is bottom-right
            float x = binding.crosshair.getX() + binding.crosshair.getWidth() / 2.0f; // Center X of the screen (50%)
            float y = binding.crosshair.getY() + binding.crosshair.getHeight() / 2.0f; // Center Y of the screen (50%)

            // Perform hit test at the center of the screen
            List<HitResult> hitResults = frame.hitTest(x, y);

            // Log the number of hit results for debugging
            Timber.d("Hit test at center (%f, %f) returned %d results", x, y, hitResults.size());

            // Filter for hits on horizontal planes
            HitResult validHitResult = null;
            for (HitResult hit : hitResults) {
                Trackable trackable = hit.getTrackable();
                if (trackable instanceof Plane) {
                    Plane plane = (Plane) trackable;
                    // Log the plane type for debugging
                    Timber.d("Hit on plane of type: %s", plane.getType());

                    // Only accept hits on horizontal planes
                    if (plane.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                            plane.isPoseInPolygon(hit.getHitPose())) {
                        validHitResult = hit;
                        Timber.d("Found valid hit on horizontal upward-facing plane");
                        break;
                    }
                } else {
                    Timber.d("Hit on non-plane trackable: %s", trackable.getClass().getSimpleName());
                }
            }

            // If no valid hit on a horizontal surface, show a message and return
            if (validHitResult == null) {
                Timber.d("No valid hit on horizontal surface found");
                snackbarHelper.showMessage(requireActivity(), "Point at a horizontal surface to place a waypoint");
                return;
            }

            // Get the hit pose on the horizontal surface
            Pose waypointPose = validHitResult.getHitPose();

            // Log the waypoint pose for debugging
            float[] translation = new float[3];
            waypointPose.getTranslation(translation, 0);
            Timber.d("Placing waypoint at position: [%f, %f, %f]", translation[0], translation[1], translation[2]);

            // Get the first resolved anchor as reference
            MapResolvingManager.ResolvedAnchor referenceAnchor = mapResolvingManager.getResolvedAnchors().get(0);

            // Create the waypoint
            Waypoint waypoint = waypointGraph.createWaypoint(
                    waypointPose,
                    referenceAnchor.getAnchor(),
                    referenceAnchor.getCloudAnchorId());

            // Create an AR anchor for the waypoint at the hit location
            Anchor waypointAnchor = validHitResult.createAnchor();
            waypoint.setAnchor(waypointAnchor);

            // Update the UI
            updateWaypointCountText();

            // Show a message
            snackbarHelper.showMessage(requireActivity(), "Waypoint placed on horizontal surface");
            Timber.d("Successfully placed waypoint #%d", waypointGraph.getWaypointCount());
        } catch (Exception e) {
            Timber.e(e, "Error placing waypoint");
            snackbarHelper.showError(requireActivity(), "Error placing waypoint: " + e.getMessage());
        }
    }

    /**
     * Selects a waypoint for connection.
     *
     * @param cameraPose The current camera pose
     */
    private void selectWaypointForConnection(Pose cameraPose) {
        // Find the closest waypoint to the camera's forward ray
        Waypoint closestWaypoint = waypointGraph.findClosestWaypoint(cameraPose, WAYPOINT_SELECTION_DISTANCE);

        if (closestWaypoint == null) {
            // No waypoint found within selection distance
            return;
        }

        String selectedWaypointId = waypointGraph.getSelectedWaypointId();

        if (selectedWaypointId == null) {
            // No waypoint currently selected, select this one
            waypointGraph.setSelectedWaypoint(closestWaypoint.getId());
            snackbarHelper.showMessage(requireActivity(), "Waypoint selected. Tap another to connect.");
        } else if (selectedWaypointId.equals(closestWaypoint.getId())) {
            // Same waypoint tapped again, deselect it
            waypointGraph.setSelectedWaypoint(null);
            snackbarHelper.showMessage(requireActivity(), "Waypoint deselected");
        } else {
            // Different waypoint tapped, create connection
            waypointGraph.connectWaypoints(selectedWaypointId, closestWaypoint.getId());
            waypointGraph.setSelectedWaypoint(null);
            snackbarHelper.showMessage(requireActivity(), "Waypoints connected");
        }
    }

    /**
     * Enables waypoint mode after all anchors have been resolved.
     */
    private void enableWaypointMode() {
        if (waypointModeEnabled) {
            return;
        }

        waypointModeEnabled = true;

        // Load any existing waypoints from the map
        loadWaypoints();

        // Update UI on the main thread
        requireActivity().runOnUiThread(() -> {
            // Show waypoint-related UI elements
            crosshair.setVisibility(View.VISIBLE);
            waypointControls.setVisibility(View.VISIBLE);
            waypointButtons.setVisibility(View.VISIBLE);
            waypointCountText.setVisibility(View.VISIBLE);
            waypointModeText.setVisibility(View.VISIBLE);

            // Set initial mode text
            waypointModeText.setText("Point the center dot at a horizontal surface to place waypoints");

            // Update waypoint count
            updateWaypointCountText();

            // Show a message
            if (waypointGraph.getWaypointCount() > 0) {
                snackbarHelper.showMessage(requireActivity(),
                        String.format("All anchors resolved. Loaded %d waypoints with %d connections.",
                                waypointGraph.getWaypointCount(), countTotalConnections()));
            } else {
                snackbarHelper.showMessage(requireActivity(), "All anchors resolved. Waypoint mode enabled.");
            }
        });
    }

    /**
     * Enables dense mapping mode after at least two anchors have been resolved.
     */
    private void enableDenseMappingMode() {
        if (denseMappingManager != null) {
            return; // Already enabled
        }

        // Find the origin anchor (the one with local coordinates 0,0,0)
        Pose originPose = null;
        String originAnchorId = null;

        // First, find the anchor with local coordinates (0,0,0) in the Map object
        if (currentMap != null && currentMap.getAnchors() != null) {
            for (Map.Anchor mapAnchor : currentMap.getAnchors()) {
                if (mapAnchor.getLocalX() == 0 && mapAnchor.getLocalY() == 0 && mapAnchor.getLocalZ() == 0) {
                    originAnchorId = mapAnchor.getCloudAnchorId();
                    Timber.d("Found origin anchor with ID: %s", originAnchorId);
                    break;
                }
            }
        }

        // If we found the origin anchor ID, get its resolved anchor and pose
        if (originAnchorId != null) {
            MapResolvingManager.ResolvedAnchor resolvedOriginAnchor =
                    mapResolvingManager.getResolvedAnchor(originAnchorId);

            if (resolvedOriginAnchor != null) {
                originPose = resolvedOriginAnchor.getAnchor().getPose();
                Timber.d("Using origin anchor (ID: %s) for local coordinate system with pose: %s",
                        originAnchorId, originPose);
            } else {
                Timber.w("Origin anchor (ID: %s) was not successfully resolved", originAnchorId);
            }
        }

        // If we couldn't find the origin anchor or it wasn't resolved, compute it from the closest anchor
        if (originPose == null) {
            originPose = computeOriginPoseFromClosestAnchor("dense mapping");
            if (originPose == null) {
                Timber.e("No resolved anchors available for dense mapping");
            }
        }

        // Create the dense mapping manager with the origin pose
        denseMappingManager = new DenseMappingManager(
                requireContext(),
                currentMap.getId(),
                currentMap.getName(),
                googleServices,
                new DenseMappingManager.DenseMappingCallback() {
                    @Override
                    public void onFrameProcessed(int frameCount) {
                        // Update the UI with the frame count
                        requireActivity().runOnUiThread(() -> {
                            denseMappingInfo.setText("Frames processed: " + frameCount);
                        });
                    }

                    @Override
                    public void onRecordingFinished(String zipFilePath) {
                        // Show completion message
                        requireActivity().runOnUiThread(() -> {
                            denseMappingStatus.setText("Recording Complete");
                            denseMappingInfo.setText("Data saved and uploaded to Google Drive");
                            snackbarHelper.showMessage(requireActivity(),
                                    "Dense mapping data uploaded to Google Drive");
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        // Show error message
                        requireActivity().runOnUiThread(() -> {
                            snackbarHelper.showError(requireActivity(), errorMessage);
                        });
                    }
                },
                originPose);

        // Update UI on the main thread
        requireActivity().runOnUiThread(() -> {
            // Enable the record button
            recordButton.setEnabled(true);

            // Update status text
            denseMappingStatus.setText("Ready to Record");
            denseMappingInfo.setText("Press 'Start Recording' to begin dense mapping");

            // Show a message
            snackbarHelper.showMessage(requireActivity(),
                    String.format("Resolved %d anchors. Dense mapping ready.", resolvedAnchorsCount));
        });
    }

    /**
     * Updates the waypoint count text.
     */
    private void updateWaypointCountText() {
        requireActivity().runOnUiThread(() -> {
            waypointCountText.setText("Waypoints: " + waypointGraph.getWaypointCount());
        });
    }

    private void showError(String message) {
        Timber.e(message);
        try {
            // Check if the fragment is attached to a context
            if (isAdded() && getActivity() != null) {
                snackbarHelper.showError(getActivity(), message);
            }
        } catch (Exception e) {
            Timber.e(e, "Error showing error message: %s", message);
        }
    }

    /**
     * Saves the current waypoints to Firebase.
     */
    private void saveWaypointsToFirebase() {
        if (currentMap == null || currentMap.getId() == null) {
            snackbarHelper.showError(requireActivity(), "No map loaded, cannot save waypoints");
            return;
        }

        if (waypointGraph.getWaypointCount() == 0) {
            snackbarHelper.showMessage(requireActivity(), "No waypoints to save");
            return;
        }

        // Show saving message
        snackbarHelper.showMessage(requireActivity(), "Saving waypoints...");

        // Find the origin anchor (the one with local coordinates 0,0,0)
        Pose originPose = null;
        String originAnchorId = null;

        // First, find the anchor with local coordinates (0,0,0) in the Map object
        if (currentMap != null && currentMap.getAnchors() != null) {
            for (Map.Anchor mapAnchor : currentMap.getAnchors()) {
                if (mapAnchor.getLocalX() == 0 && mapAnchor.getLocalY() == 0 && mapAnchor.getLocalZ() == 0) {
                    originAnchorId = mapAnchor.getCloudAnchorId();
                    Timber.d("Found origin anchor with ID: %s for waypoint saving", originAnchorId);
                    break;
                }
            }
        }

        // If we found the origin anchor ID, get its resolved anchor and pose
        if (originAnchorId != null) {
            MapResolvingManager.ResolvedAnchor resolvedOriginAnchor =
                    mapResolvingManager.getResolvedAnchor(originAnchorId);

            if (resolvedOriginAnchor != null) {
                originPose = resolvedOriginAnchor.getAnchor().getPose();
                Timber.d("Using origin anchor (ID: %s) for waypoint saving with pose: %s",
                        originAnchorId, originPose);
            } else {
                Timber.w("Origin anchor (ID: %s) was not successfully resolved for waypoint saving", originAnchorId);
            }
        }

        // If we couldn't find the origin anchor or it wasn't resolved, compute it from the closest anchor
        if (originPose == null) {
            originPose = computeOriginPoseFromClosestAnchor("waypoint saving");
        }

        // Convert waypoints to WaypointData objects with local coordinates
        List<WaypointData> waypointDataList = new ArrayList<>();
        for (Waypoint waypoint : waypointGraph.getAllWaypoints()) {
            // Create WaypointData with local coordinates relative to the origin
            WaypointData waypointData = new WaypointData(waypoint, originPose);

            // Log the local coordinates for debugging
            if (originPose != null) {
                float[] localCoords = waypoint.calculateLocalCoordinates(originPose);
                Timber.d("Waypoint %s local coordinates: [%f, %f, %f]",
                        waypoint.getId(), localCoords[0], localCoords[1], localCoords[2]);
            }

            waypointDataList.add(waypointData);
        }

        // Update the map with the new waypoints
        currentMap.setWaypoints(waypointDataList);
        currentMap.setUpdatedAt(System.currentTimeMillis());

        // Save to Firebase
        db.collection("maps").document(currentMap.getId())
                .update("waypoints", waypointDataList, "updatedAt", currentMap.getUpdatedAt())
                .addOnSuccessListener(aVoid -> {
                    Timber.d("Waypoints saved successfully");
                    snackbarHelper.showMessage(requireActivity(),
                            String.format("Saved %d waypoints with %d connections",
                                    waypointGraph.getWaypointCount(), countTotalConnections()));

                    // Clean up resources and navigate back after successful save
                    cleanupResources();
                    if (isAdded() && getView() != null) {
                        Navigation.findNavController(getView()).navigate(R.id.mapManagementFragment);
                    }
                })
                .addOnFailureListener(e -> {
                    Timber.e(e, "Error saving waypoints");
                    snackbarHelper.showError(requireActivity(), "Error saving waypoints: " + e.getMessage());
                });
    }

    /**
     * Counts the total number of connections between waypoints.
     * Each connection is counted only once.
     *
     * @return The total number of unique connections
     */
    private int countTotalConnections() {
        Set<String> connections = new HashSet<>();
        for (Waypoint waypoint : waypointGraph.getAllWaypoints()) {
            String waypointId = waypoint.getId();
            for (String connectedId : waypoint.getConnectedWaypointIds()) {
                // Create a unique key for each connection (using alphabetical order to avoid duplicates)
                String connectionKey = waypointId.compareTo(connectedId) < 0
                        ? waypointId + "_" + connectedId
                        : connectedId + "_" + waypointId;
                connections.add(connectionKey);
            }
        }
        return connections.size();
    }

    /**
     * Computes the origin pose from the closest anchor to the origin when the true origin anchor
     * has not been resolved yet.
     *
     * @param purpose A string describing the purpose (for logging)
     * @return The computed origin pose, or null if it couldn't be computed
     */
    private Pose computeOriginPoseFromClosestAnchor(String purpose) {
        if (currentMap == null || currentMap.getAnchors() == null) {
            return null;
        }

        List<MapResolvingManager.ResolvedAnchor> resolvedAnchors = mapResolvingManager.getResolvedAnchors();
        if (resolvedAnchors.isEmpty()) {
            return null;
        }

        // Find the anchor closest to the origin based on local coordinates
        Map.Anchor closestAnchor = null;
        double minDistance = Double.MAX_VALUE;

        for (Map.Anchor mapAnchor : currentMap.getAnchors()) {
            // Skip anchors that are at the origin (they should have been found earlier)
            if (mapAnchor.getLocalX() == 0 && mapAnchor.getLocalY() == 0 && mapAnchor.getLocalZ() == 0) {
                continue;
            }

            // Calculate distance to origin
            double distance = mapAnchor.distanceToOrigin();
            if (distance < minDistance) {
                // Find the corresponding resolved anchor
                MapResolvingManager.ResolvedAnchor resolvedAnchor =
                        mapResolvingManager.getResolvedAnchor(mapAnchor.getCloudAnchorId());

                if (resolvedAnchor != null) {
                    minDistance = distance;
                    closestAnchor = mapAnchor;
                }
            }
        }

        if (closestAnchor != null) {
            // Get the resolved anchor for this map anchor
            MapResolvingManager.ResolvedAnchor resolvedClosestAnchor =
                    mapResolvingManager.getResolvedAnchor(closestAnchor.getCloudAnchorId());

            if (resolvedClosestAnchor != null) {
                // Get the world pose of the closest anchor
                Pose closestAnchorPose = resolvedClosestAnchor.getAnchor().getPose();

                // Create a pose with the negative local coordinates of the closest anchor
                float[] translation = new float[] {
                    (float)-closestAnchor.getLocalX(),
                    (float)-closestAnchor.getLocalY(),
                    (float)-closestAnchor.getLocalZ()
                };

                // Create a pose with the inverse of the local orientation
                float[] rotation = new float[] {
                    (float)-closestAnchor.getLocalQx(),
                    (float)-closestAnchor.getLocalQy(),
                    (float)-closestAnchor.getLocalQz(),
                    (float)closestAnchor.getLocalQw()  // w component is positive for inverse
                };

                // Normalize the quaternion
                float magnitude = (float)Math.sqrt(
                    rotation[0] * rotation[0] +
                    rotation[1] * rotation[1] +
                    rotation[2] * rotation[2] +
                    rotation[3] * rotation[3]
                );

                if (magnitude > 0) {
                    rotation[0] /= magnitude;
                    rotation[1] /= magnitude;
                    rotation[2] /= magnitude;
                    rotation[3] /= magnitude;
                } else {
                    // Default to identity quaternion if normalization fails
                    rotation[0] = 0;
                    rotation[1] = 0;
                    rotation[2] = 0;
                    rotation[3] = 1;
                }

                // Create a local-to-world transform
                Pose localToWorld = new Pose(translation, rotation);

                // Compute the origin pose by composing the closest anchor's pose with the local-to-world transform
                Pose originPose = closestAnchorPose.compose(localToWorld);

                Timber.d("Computed origin pose from closest anchor (ID: %s) for %s with local coordinates (%f, %f, %f) and orientation (%f, %f, %f, %f)",
                        closestAnchor.getCloudAnchorId(),
                        purpose,
                        closestAnchor.getLocalX(), closestAnchor.getLocalY(), closestAnchor.getLocalZ(),
                        closestAnchor.getLocalQx(), closestAnchor.getLocalQy(), closestAnchor.getLocalQz(), closestAnchor.getLocalQw());

                return originPose;
            }
        }

        // If we couldn't compute the origin pose, fall back to the first resolved anchor
        Pose fallbackPose = resolvedAnchors.get(0).getAnchor().getPose();
        Timber.w("Falling back to first resolved anchor as origin for %s with pose: %s", purpose, fallbackPose);
        return fallbackPose;
    }

    /**
     * Loads waypoints from the current map after anchors are resolved.
     */
    private void loadWaypoints() {
        if (currentMap == null || currentMap.getWaypoints() == null || currentMap.getWaypoints().isEmpty()) {
            Timber.d("No waypoints to load");
            return;
        }

        // Clear any existing waypoints
        waypointGraph.clear();

        // Create a map of anchor IDs to resolved anchors for quick lookup
        HashMap<String, Anchor> resolvedAnchorsMap = new HashMap<>();
        for (MapResolvingManager.ResolvedAnchor resolvedAnchor : mapResolvingManager.getResolvedAnchors()) {
            resolvedAnchorsMap.put(resolvedAnchor.getCloudAnchorId(), resolvedAnchor.getAnchor());
        }

        // Find the origin anchor (the one with local coordinates 0,0,0)
        Pose originPose = null;
        String originAnchorId = null;

        // First, find the anchor with local coordinates (0,0,0) in the Map object
        if (currentMap != null && currentMap.getAnchors() != null) {
            for (Map.Anchor mapAnchor : currentMap.getAnchors()) {
                if (mapAnchor.getLocalX() == 0 && mapAnchor.getLocalY() == 0 && mapAnchor.getLocalZ() == 0) {
                    originAnchorId = mapAnchor.getCloudAnchorId();
                    Timber.d("Found origin anchor with ID: %s for waypoint loading", originAnchorId);
                    break;
                }
            }
        }

        // If we found the origin anchor ID, get its resolved anchor and pose
        if (originAnchorId != null) {
            MapResolvingManager.ResolvedAnchor resolvedOriginAnchor =
                    mapResolvingManager.getResolvedAnchor(originAnchorId);

            if (resolvedOriginAnchor != null) {
                originPose = resolvedOriginAnchor.getAnchor().getPose();
                Timber.d("Using origin anchor (ID: %s) for waypoint local coordinate system with pose: %s",
                        originAnchorId, originPose);
            } else {
                Timber.w("Origin anchor (ID: %s) was not successfully resolved for waypoint loading", originAnchorId);
            }
        }

        // If we couldn't find the origin anchor or it wasn't resolved, compute it from the closest anchor
        if (originPose == null) {
            originPose = computeOriginPoseFromClosestAnchor("waypoint loading");
        }

        // First pass: Create all waypoints
        HashMap<String, Waypoint> createdWaypoints = new HashMap<>();
        for (WaypointData waypointData : currentMap.getWaypoints()) {
            String referenceAnchorId = waypointData.getReferenceAnchorId();
            Anchor referenceAnchor = resolvedAnchorsMap.get(referenceAnchorId);

            // Determine which method to use for positioning the waypoint
            boolean useLocalCoordinates = false;
            Pose worldPose = null;

            // Check if we have local coordinates and an origin pose
            if (waypointData.getLocalTranslation() != null &&
                waypointData.getLocalTranslation().size() == 3 &&
                originPose != null) {

                try {
                    // Convert local coordinates to a pose
                    float[] localTranslation = new float[3];
                    for (int i = 0; i < 3; i++) {
                        localTranslation[i] = waypointData.getLocalTranslation().get(i);
                    }

                    // Create a pose with the local translation and identity rotation
                    Pose localPose = new Pose(localTranslation, new float[]{0, 0, 0, 1});

                    // Transform from local to world coordinates
                    worldPose = originPose.compose(localPose);
                    useLocalCoordinates = true;

                    Timber.d("Using local coordinates for waypoint %s: [%f, %f, %f]",
                            waypointData.getId(),
                            localTranslation[0], localTranslation[1], localTranslation[2]);
                } catch (Exception e) {
                    Timber.e(e, "Error using local coordinates for waypoint %s", waypointData.getId());
                    useLocalCoordinates = false;
                }
            }

            // Fall back to reference anchor method if local coordinates aren't available or failed
            if (!useLocalCoordinates && referenceAnchor != null) {
                // Create the relative pose
                Pose relativePose = waypointData.createRelativePose();

                // Calculate the world pose by composing the anchor pose with the relative pose
                Pose anchorPose = referenceAnchor.getPose();
                worldPose = anchorPose.compose(relativePose);

                Timber.d("Using reference anchor for waypoint %s", waypointData.getId());
            }

            // Create the waypoint if we have a valid world pose
            if (worldPose != null) {
                // Create the waypoint with the original ID
                Waypoint waypoint = new Waypoint(waypointData.getId(), worldPose, referenceAnchor, referenceAnchorId);

                // Create an AR anchor for the waypoint
                Anchor waypointAnchor = session.createAnchor(worldPose);
                waypoint.setAnchor(waypointAnchor);

                // Store the waypoint in our map for the second pass
                createdWaypoints.put(waypointData.getId(), waypoint);

                // Add the waypoint to the graph
                waypointGraph.addWaypoint(waypoint);

                Timber.d("Loaded waypoint %s at pose %s", waypointData.getId(), worldPose.toString());
            } else {
                Timber.w("Could not create waypoint %s - no valid pose available", waypointData.getId());
            }
        }

        // Second pass: Create connections between waypoints
        for (WaypointData waypointData : currentMap.getWaypoints()) {
            Waypoint waypoint = createdWaypoints.get(waypointData.getId());
            if (waypoint != null) {
                for (String connectedId : waypointData.getConnectedWaypointIds()) {
                    Waypoint connectedWaypoint = createdWaypoints.get(connectedId);
                    if (connectedWaypoint != null) {
                        waypoint.addConnection(connectedId);
                        Timber.d("Created connection between waypoints %s and %s",
                                waypointData.getId(), connectedId);
                    }
                }
            }
        }

        // Update the UI
        updateWaypointCountText();
        Timber.d("Loaded %d waypoints with %d connections",
                waypointGraph.getWaypointCount(), countTotalConnections());
    }

    /**
     * Cleans up resources used by this fragment.
     * This method should be called before navigating away from the fragment
     * to ensure that all resources are properly released.
     */
    private void cleanupResources() {
        // Cancel any pending operations
        isResolvingAnchors = false;
        isCheckingVps = false;

        // Clean up dense mapping resources
        if (denseMappingManager != null) {
            if (denseMappingManager.isRecording()) {
                denseMappingManager.stopRecording();
            }
            denseMappingManager.cleanup();
            denseMappingManager = null;
        }
        waypointModeEnabled = false;

        // Clean up ARCore session
        if (session != null) {
            try {
                session.pause();
            } catch (Exception e) {
                Timber.e(e, "Error pausing session");
            }
        }

        // Clean up map resolving manager
        if (mapResolvingManager != null) {
            mapResolvingManager.clear();
        }

        // Clean up waypoint graph
        if (waypointGraph != null) {
            waypointGraph.clear();
        }

        // Remove any pending handlers
        if (getView() != null) {
            getView().removeCallbacks(null);
        }

        // Hide any snackbars
        if (isAdded() && getActivity() != null) {
            snackbarHelper.hide(getActivity());
        }

        // Hide waypoint-related UI elements
        if (isAdded() && getActivity() != null) {
            requireActivity().runOnUiThread(() -> {
                if (crosshair != null) crosshair.setVisibility(View.GONE);
                if (waypointControls != null) waypointControls.setVisibility(View.GONE);
                if (waypointButtons != null) waypointButtons.setVisibility(View.GONE);
                if (waypointCountText != null) waypointCountText.setVisibility(View.GONE);
                if (waypointModeText != null) waypointModeText.setVisibility(View.GONE);
            });
        }
    }

    /**
     * Updates the geospatial info text with the current pose information.
     * This method formats the latitude, longitude, altitude, and orientation information
     * and displays it on the screen.
     *
     * @param pose The current geospatial pose of the device
     */
    private void updateGeospatialInfoText(GeospatialPose pose) {
        if (pose == null) return;

        // Determine if VPS is being used based on accuracy values
        // VPS typically provides much better accuracy than GPS alone
        boolean isUsingVps = pose.getHorizontalAccuracy() < 3.0 && pose.getOrientationYawAccuracy() < 15.0;

        // Format the geospatial information with proper precision
        String info = String.format(
                "Latitude: %.6f\n" +
                "Longitude: %.6f\n" +
                "Altitude: %.2f m\n" +
                "Heading: %.1f°\n" +
                "Accuracy (Horiz): %.2f m\n" +
                "Accuracy (Vert): %.2f m\n" +
                "Accuracy (Heading): %.1f°\n" +
                "Using VPS: %s",
                pose.getLatitude(),
                pose.getLongitude(),
                pose.getAltitude(),
                pose.getHeading(),
                pose.getHorizontalAccuracy(),
                pose.getVerticalAccuracy(),
                pose.getOrientationYawAccuracy(),
                isUsingVps ? "Yes" : "No (GPS only)");

        // Update VPS status text based on whether VPS is being used
        if (isUsingVps) {
            updateVpsStatusText("VPS: Active and being used", false);
        } else if (isVpsAvailable) {
            updateVpsStatusText("VPS: Available but not being used", false);
        }

        // Update the UI on the main thread
        try {
            // Check if the fragment is attached to a context
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try {
                        if (geospatialInfoText != null) {
                            geospatialInfoText.setText(info);
                        }
                    } catch (Exception e) {
                        Timber.e(e, "Error updating geospatial info text");
                    }
                });
            }
        } catch (Exception e) {
            Timber.e(e, "Error updating geospatial info");
        }
    }

    /**
     * Updates the VPS status text with the provided message.
     *
     * @param message The status message to display
     * @param isError Whether this is an error message (changes text color)
     */
    private void updateVpsStatusText(String message, boolean isError) {
        try {
            // Check if the fragment is attached to a context
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try {
                        if (vpsStatusText != null) {
                            vpsStatusText.setText(message);
                            if (isError) {
                                vpsStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                            } else {
                                vpsStatusText.setTextColor(getResources().getColor(android.R.color.white));
                            }
                        }
                    } catch (Exception e) {
                        Timber.e(e, "Error updating VPS status text");
                    }
                });
            }
        } catch (Exception e) {
            Timber.e(e, "Error updating VPS status: %s", message);
        }
    }

    /**
     * Checks VPS availability at the current location.
     * This uses the device's location services to get the current position,
     * then queries the ARCore API to check if VPS is available at that location.
     */
    private void checkVpsAvailability() {
        if (isCheckingVps || session == null) return;

        isCheckingVps = true;
        updateVpsStatusText("Checking VPS availability...", false);

        // Get the current location using FusedLocationProviderClient
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            updateVpsStatusText("Location permission required for VPS check", true);
            isCheckingVps = false;
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        double latitude = location.getLatitude();
                        double longitude = location.getLongitude();

                        // Check VPS availability at this location using ARCore API
                        session.checkVpsAvailabilityAsync(
                                latitude, longitude,
                                availability -> {
                                    isCheckingVps = false;
                                    isVpsAvailable = (availability == VpsAvailability.AVAILABLE);

                                    String statusMessage;
                                    if (isVpsAvailable) {
                                        statusMessage = "VPS: Available at current location";
                                    } else {
                                        statusMessage = "VPS: Not available at current location";
                                    }

                                    updateVpsStatusText(statusMessage, !isVpsAvailable);

                                    // Log the result
                                    Timber.d("VPS availability at [%f, %f]: %s", latitude, longitude, availability);
                                });
                    } else {
                        updateVpsStatusText("VPS: Could not determine location", true);
                        isCheckingVps = false;
                    }
                })
                .addOnFailureListener(e -> {
                    updateVpsStatusText("VPS: Error getting location", true);
                    Timber.e(e, "Error getting location for VPS check");
                    isCheckingVps = false;
                });
    }
}
