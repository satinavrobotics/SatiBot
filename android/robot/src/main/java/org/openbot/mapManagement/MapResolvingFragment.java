package org.openbot.mapManagement;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import org.openbot.R;
import org.openbot.databinding.FragmentMapResolvingBinding;
import org.openbot.env.CameraPermissionHelper;
import org.openbot.env.DisplayRotationHelper;
import org.openbot.env.SharedPreferencesManager;
import org.openbot.mapManagement.rendering.BackgroundRenderer;
import org.openbot.mapManagement.rendering.ObjectRenderer;
import org.openbot.mapManagement.rendering.PlaneRenderer;
import org.openbot.mapManagement.rendering.PointCloudRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

public class MapResolvingFragment extends Fragment implements GLSurfaceView.Renderer {
    private static final String TAG = "MapResolvingFragment";
    private static final float[] OBJECT_COLOR = new float[] {0.0f, 1.0f, 0.0f, 1.0f}; // Green color for resolved anchors
    private static final float[] EARTH_OBJECT_COLOR = new float[] {0.0f, 0.0f, 1.0f, 1.0f}; // Blue color for Earth mode

    // UI elements
    private FragmentMapResolvingBinding binding;
    private TextView statusText;
    private TextView resolvedCountText;
    private Button doneButton;
    private final SnackbarHelper snackbarHelper = new SnackbarHelper();

    // ARCore components
    private boolean installRequested;
    private Session session;
    private DisplayRotationHelper displayRotationHelper;
    private SharedPreferences sharedPreferences;
    private TrackingStateHelper trackingStateHelper;

    // Rendering components
    private GLSurfaceView surfaceView;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();
    private final ObjectRenderer anchorRenderer = new ObjectRenderer();

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

    // Geospatial API components
    private Earth earth;
    private boolean isEarthMode = false;
    private TextView geospatialInfoText;
    private TextView vpsStatusText;
    private FusedLocationProviderClient fusedLocationClient;
    private GeospatialPose lastGeospatialPose;
    private boolean isVpsAvailable = false;
    private boolean isCheckingVps = false;

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

        // Handle back button press
        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Clean up resources before navigating back
                cleanupResources();

                // Navigate back
                if (isAdded() && getView() != null) {
                    Navigation.findNavController(getView()).popBackStack();
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
        geospatialInfoText = binding.geospatialInfoText;
        vpsStatusText = binding.vpsStatusText;
        doneButton = binding.doneButton;

        // Initially hide the geospatial info and VPS status texts
        geospatialInfoText.setVisibility(View.GONE);
        vpsStatusText.setVisibility(View.GONE);

        // Initialize TrackingStateHelper
        trackingStateHelper = new TrackingStateHelper(requireActivity());

        // Set up renderer
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending

        // Set up render mode
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);
        installRequested = false;

        // Set up button listeners
        doneButton.setOnClickListener(v -> {
            // Clean up resources before navigating back
            cleanupResources();

            // Navigate back
            if (isAdded() && getView() != null) {
                Navigation.findNavController(getView()).popBackStack();
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
                Navigation.findNavController(getView()).popBackStack();
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
                                Navigation.findNavController(getView()).popBackStack();
                            }
                        }
                    } else {
                        showError("Map not found.");
                        // Clean up and navigate back
                        cleanupResources();
                        if (isAdded() && getView() != null) {
                            Navigation.findNavController(getView()).popBackStack();
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    showError("Error loading map: " + e.getMessage());
                    // Clean up and navigate back
                    cleanupResources();
                    if (isAdded() && getView() != null) {
                        Navigation.findNavController(getView()).popBackStack();
                    }
                });
    }

    private void updateMapInfo() {
        if (currentMap == null) return;

        // Handle Earth mode differently
        if (isEarthMode) {
            requireActivity().runOnUiThread(() -> {
                statusText.setText("Map: " + currentMap.getName());
                // Hide the resolved count text in Earth mode
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
        // Skip anchor resolution for Earth mode
        if (isEarthMode) {
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

                // Enable plane detection - this is helpful for visualization
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);

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

            // Handle Earth mode or normal cloud anchor mode
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
            }

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions
            Timber.e(t, "Exception on the OpenGL thread");
        }
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
     * Cleans up resources used by this fragment.
     * This method should be called before navigating away from the fragment
     * to ensure that all resources are properly released.
     */
    private void cleanupResources() {
        // Cancel any pending operations
        isResolvingAnchors = false;
        isCheckingVps = false;

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

        // Remove any pending handlers
        if (getView() != null) {
            getView().removeCallbacks(null);
        }

        // Hide any snackbars
        if (isAdded() && getActivity() != null) {
            snackbarHelper.hide(getActivity());
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
