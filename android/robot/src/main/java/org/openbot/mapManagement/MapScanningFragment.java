package org.openbot.mapManagement;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.common.base.Preconditions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.material.snackbar.Snackbar;

import java.util.Collection;
import java.util.List;

import org.openbot.R;
import org.openbot.databinding.FragmentMapScanningBinding;
import org.openbot.env.CameraPermissionHelper;
import org.openbot.env.DisplayRotationHelper;
import org.openbot.mapManagement.SnackbarHelper;
import org.openbot.mapManagement.rendering.BackgroundRenderer;
import org.openbot.mapManagement.rendering.ObjectRenderer;
import org.openbot.mapManagement.rendering.PlaneRenderer;
import org.openbot.mapManagement.rendering.PointCloudRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

public class MapScanningFragment extends Fragment implements GLSurfaceView.Renderer {
    private static final String TAG = "MapScanningFragment";
    private static final String ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES";
    private static final float[] OBJECT_COLOR = new float[] {139.0f/255.0f, 195.0f/255.0f, 74.0f/255.0f, 1.0f};

    // UI elements
    private FragmentMapScanningBinding binding;
    private TextView statusText;
    private TextView anchorCountText;
    private Button saveButton;
    private Button cancelButton;
    private final SnackbarHelper snackbarHelper = new SnackbarHelper();

    // ARCore components
    private boolean installRequested;
    private Session session;
    private DisplayRotationHelper displayRotationHelper;
    private GestureDetector gestureDetector;
    private final Object singleTapLock = new Object();
    private MotionEvent queuedSingleTap;
    private SharedPreferences sharedPreferences;
    private TrackingStateHelper trackingStateHelper;

    // Rendering components
    private GLSurfaceView surfaceView;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();
    private final ObjectRenderer anchorRenderer = new ObjectRenderer();
    private final ObjectRenderer featureMapQualityBarRenderer = new ObjectRenderer();

    // Feature Map Quality UI
    private FeatureMapQualityUi featureMapQualityUi;
    private static final float QUALITY_THRESHOLD = 0.6f;
    private Pose anchorPose;
    private boolean hostedAnchor = false;
    private long lastEstimateTimestampMillis;
    private static final float MIN_DISTANCE = 0.2f;
    private static final float MAX_DISTANCE = 2.0f;
    private float[] anchorTranslation = new float[4]; // Initialize with size 4 for homogeneous coordinates
    private boolean shouldDrawFeatureMapQualityUi = false;

    // Temporary matrices for rendering
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] anchorMatrix = new float[16];

    // Cloud anchor components
    private final MapScanningManager mapScanningManager = new MapScanningManager();
    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private OnBackPressedCallback onBackPressedCallback;

    // Anchors
    private final Object anchorLock = new Object();
    @GuardedBy("anchorLock") private List<Anchor> pendingAnchors = new ArrayList<>();

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

        // Log user email if available
        if (currentUser != null) {
            Timber.d("Current user: %s", currentUser.getEmail());
        } else {
            Timber.d("No user logged in");
        }

        // Handle back button press
        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showCancelConfirmationDialog();
            }
        };

        requireActivity().getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
        Timber.d("finished");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMapScanningBinding.inflate(inflater, container, false);
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
        anchorCountText = binding.anchorCountText;
        saveButton = binding.saveButton;
        cancelButton = binding.cancelButton;

        // Initialize TrackingStateHelper
        trackingStateHelper = new TrackingStateHelper(requireActivity());

        // Set up touch listener
        gestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) {
                    queuedSingleTap = e;
                }
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        surfaceView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        // Set up renderer
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending

        // Set up render mode
        // Using RENDERMODE_CONTINUOUSLY to ensure the camera feed is constantly updated
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);
        installRequested = false;

        Timber.d("GLSurfaceView configured with RENDERMODE_CONTINUOUSLY to ensure camera feed is displayed");

        // Set up button listeners
        saveButton.setOnClickListener(v -> showSaveMapDialog());
        cancelButton.setOnClickListener(v -> showCancelConfirmationDialog());

        // Update UI
        updateAnchorCountText();
    }

    @Override
    public void onResume() {
        super.onResume();
        createSession();
        surfaceView.onResume();
        displayRotationHelper.onResume();

        // Check if API key is properly configured
        checkApiKeyConfiguration();
    }

    /**
     * Checks if ARCore is properly configured in the manifest.
     * This is required for cloud anchors to work.
     */
    private void checkApiKeyConfiguration() {
        try {
            // Get the application info
            android.content.pm.ApplicationInfo appInfo = requireContext().getPackageManager()
                    .getApplicationInfo(requireContext().getPackageName(), android.content.pm.PackageManager.GET_META_DATA);

            // Check if ARCore is set to required
            if (appInfo.metaData != null && appInfo.metaData.containsKey("com.google.ar.core")) {
                String arCoreValue = appInfo.metaData.getString("com.google.ar.core");
                if ("required".equals(arCoreValue)) {
                    Timber.d("ARCore is properly configured as required");
                } else {
                    Timber.w("ARCore is configured as '%s', but 'required' is recommended for cloud anchors", arCoreValue);
                }
            } else {
                Timber.e("ARCore is not configured in AndroidManifest.xml");
                snackbarHelper.showError(requireActivity(), "ARCore is not configured in AndroidManifest.xml");
            }

            // Check if Firebase Auth is initialized (needed for keyless authentication)
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                Timber.d("Firebase Auth is initialized with user: %s", FirebaseAuth.getInstance().getCurrentUser().getEmail());
            } else {
                Timber.w("Firebase Auth user is not signed in, which may cause authentication issues with Cloud Anchors");
                snackbarHelper.showMessageWithDismiss(requireActivity(), "You are not signed in, which may cause issues with Cloud Anchors");
            }
        } catch (Exception e) {
            Timber.e(e, "Error checking ARCore configuration");
        }
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
    public void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        // Reset orientation when leaving this fragment
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        onBackPressedCallback.remove();
        super.onDestroy();
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

                // Enable cloud anchors - this is critical for hosting and resolving cloud anchors
                config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);

                // Enable plane detection - this is critical for surface rendering
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);

                // Enable light estimation for better visual features
                config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);

                // Set focus mode to auto for better visual features
                config.setFocusMode(Config.FocusMode.AUTO);

                // Enable depth for better tracking
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                }

                // Apply the configuration
                session.configure(config);

                // Log the configuration for debugging
                Timber.d("ARCore session configured with:\n" +
                        "- Cloud Anchor Mode: %s\n" +
                        "- Plane Finding Mode: %s\n" +
                        "- Light Estimation Mode: %s\n" +
                        "- Focus Mode: %s\n" +
                        "- Depth Mode: %s",
                        config.getCloudAnchorMode(),
                        config.getPlaneFindingMode(),
                        config.getLightEstimationMode(),
                        config.getFocusMode(),
                        config.getDepthMode());

                Timber.d("ARCore session configured with plane finding mode: %s",
                        config.getPlaneFindingMode());

                // Set the session in the manager
                mapScanningManager.setSession(session);

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
            Timber.d("Initializing background renderer");
            backgroundRenderer.createOnGlThread(requireContext());
            Timber.d("Background renderer initialized successfully with texture ID: %d", backgroundRenderer.getTextureId());

            // Load the plane renderer with the texture
            try {
                // Make sure to use the correct path to the texture
                Timber.d("Loading plane renderer texture from assets");
                planeRenderer.createOnGlThread(requireContext(), "models/trigrid.png");
                Timber.d("Loaded plane renderer with texture: models/trigrid.png");
            } catch (IOException e) {
                // Fallback to the simplified version
                Timber.e(e, "Failed to load plane texture, using fallback");
            }

            pointCloudRenderer.createOnGlThread(requireContext());

            // Load the anchor renderer with the 3D model
            try {
                anchorRenderer.createOnGlThread(requireContext(), "models/anchor.obj", "models/anchor.png");
                anchorRenderer.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
                anchorRenderer.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
                Timber.d("Loaded anchor renderer with 3D model: models/andy.obj");

                // Initialize the feature map quality bar renderer with custom shaders
                featureMapQualityBarRenderer.createOnGlThread(
                    requireContext(),
                    "models/map_quality_bar.obj",
                    "models/map_quality_bar.png",
                    "shaders/feature_map_quality.vert",
                    "shaders/feature_map_quality.frag");
                featureMapQualityBarRenderer.setMaterialProperties(0.0f, 2.0f, 0.02f, 0.5f); // Use same properties as in persistent_cloud_example
                featureMapQualityBarRenderer.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
                Timber.d("Loaded feature map quality bar renderer with model: models/map_quality_bar.obj and texture: models/map_quality_bar.png");
            } catch (IOException e) {
                Timber.e(e, "Failed to load 3D models, using fallback");
                anchorRenderer.createOnGlThread(requireContext(), "models/andy.obj", "models/andy.png");
                anchorRenderer.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
                anchorRenderer.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
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

    // Track frame timestamps to prevent out-of-order rendering
    private long lastFrameTimestamp = 0;

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Enable depth testing for proper 3D rendering
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        // Enable blending for transparent objects
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        if (session == null) {
            Timber.d("Session is null, skipping frame");
            return;
        }

        // Notify ARCore session that the view size changed
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            // Get the camera texture ID from the background renderer
            int textureId = backgroundRenderer.getTextureId();
            Timber.d("Setting camera texture name to: %d", textureId);

            // Set the camera texture name in the session
            session.setCameraTextureName(textureId);

            // Obtain the current frame from ARSession
            Frame frame = session.update();

            // Check frame timestamp to prevent out-of-order rendering
            long frameTimestamp = frame.getTimestamp();
            Timber.d("Frame timestamp: %d", frameTimestamp);

            if (frameTimestamp < lastFrameTimestamp) {
                Timber.w("Skipping out-of-order frame: current=%d, last=%d",
                        frameTimestamp, lastFrameTimestamp);
                return;
            }
            lastFrameTimestamp = frameTimestamp;

            Camera camera = frame.getCamera();
            TrackingState cameraTrackingState = camera.getTrackingState();

            // Log camera tracking state
            Timber.d("Camera tracking state: %s", cameraTrackingState);
            if (cameraTrackingState == TrackingState.PAUSED) {
                Timber.d("Tracking paused");
            }

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops
            trackingStateHelper.updateKeepScreenOnFlag(cameraTrackingState);

            // Log plane detection info
            Collection<Plane> planes = session.getAllTrackables(Plane.class);
            int trackingPlanes = 0;
            for (Plane plane : planes) {
                if (plane.getTrackingState() == TrackingState.TRACKING) {
                    trackingPlanes++;
                }
            }
            Timber.d("Detected planes: %d, Tracking planes: %d", planes.size(), trackingPlanes);

            // Update UI with enhanced guidance for better scene capture
            updateTrackingStatusAndGuidance(camera, trackingPlanes);

            // Notify the manager of all updates
            // Process any completed cloud anchor tasks
            mapScanningManager.onUpdate();

            // Check for updates to cloud anchor states
            checkCloudAnchorStates();

            // Handle user input
            handleTap(frame, cameraTrackingState);

            // Draw camera preview
            Timber.d("Drawing camera preview with frame timestamp: %d", frameTimestamp);
            backgroundRenderer.draw(frame);
            Timber.d("Camera preview drawn");

            // Force a redraw to ensure the camera feed is updated
            if (surfaceView != null) {
                surfaceView.requestRender();
            }

            // If not tracking, don't draw 3D objects
            if (cameraTrackingState == TrackingState.PAUSED) {
                Timber.d("Camera tracking is paused, skipping 3D object rendering");
                return;
            }

            // Get camera and projection matrices
            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

            // Create a copy of the projection matrix for rendering
            float[] portraitProjectionMatrix = new float[16];
            System.arraycopy(projectionMatrix, 0, portraitProjectionMatrix, 0, 16);

            // Visualize tracked points
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                pointCloudRenderer.update(pointCloud);
                pointCloudRenderer.draw(viewMatrix, projectionMatrix);
            }

            // Visualize planes
            Collection<Plane> allPlanes = session.getAllTrackables(Plane.class);
            Timber.d("Drawing planes: %d", allPlanes.size());
            // Use the display-oriented pose for plane rendering, which handles the orientation correctly
            // This is the key difference from the cloud_anchors_example that makes planes render correctly
            planeRenderer.drawPlanes(allPlanes, camera.getDisplayOrientedPose(), projectionMatrix);

            // Visualize anchors
            float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            // Draw all hosted anchors
            for (MapScanningManager.AnchorWithCloudId anchorWithCloudId : mapScanningManager.getHostedAnchors()) {
                Anchor anchor = anchorWithCloudId.getAnchor();
                if (anchor.getTrackingState() == TrackingState.TRACKING) {
                    // Get the current pose of the Anchor in world space
                    anchor.getPose().toMatrix(anchorMatrix, 0);

                    // Update the model matrix and draw the 3D object
                    float scaleFactor = 1.0f; // Match the size used in persistent_cloud_example
                    anchorRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
                    anchorRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR);
                }
            }

            // Draw any pending anchors that are being hosted
            synchronized (anchorLock) {
                for (Anchor anchor : pendingAnchors) {
                    if (anchor.getTrackingState() == TrackingState.TRACKING) {
                        // Get the current pose of the Anchor in world space
                        anchor.getPose().toMatrix(anchorMatrix, 0);

                        // Update the model matrix and draw the 3D object
                        float scaleFactor = 1.0f; // Match the size used in persistent_cloud_example
                        anchorRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
                        anchorRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR);

                        // If this is the anchor we're evaluating for feature map quality
                        if (anchorPose != null && !hostedAnchor && featureMapQualityUi != null) {
                            // Set the flag to draw the feature map quality UI
                            shouldDrawFeatureMapQualityUi = true;
                            Timber.d("Set shouldDrawFeatureMapQualityUi to true");
                        }
                    }
                }
            }

            // Render the Feature Map Quality Indicator UI if needed
            if (shouldDrawFeatureMapQualityUi && anchorPose != null && !hostedAnchor && featureMapQualityUi != null) {
                Timber.d("Drawing feature map quality UI");
                updateFeatureMapQualityUi(camera, colorCorrectionRgba);
            }

            // Request next frame render - this helps with frame ordering
            if (surfaceView.getRenderMode() == GLSurfaceView.RENDERMODE_WHEN_DIRTY) {
                surfaceView.requestRender();
            }

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions
            Timber.e(t, "Exception on the OpenGL thread");
        }
    }

    /**
     * Updates the status text with enhanced guidance for better scene capture.
     * This provides specific feedback based on tracking state and feature map quality.
     *
     * @param camera The ARCore camera
     * @param trackingPlanes The number of currently tracking planes
     */
    private void updateTrackingStatusAndGuidance(Camera camera, int trackingPlanes) {
        if (session == null) {
            return;
        }

        // Skip updating the global status if we're currently evaluating an anchor
        if (anchorPose != null && !hostedAnchor) {
            return;
        }

        TrackingState cameraTrackingState = camera.getTrackingState();

        requireActivity().runOnUiThread(() -> {
            StringBuilder statusBuilder = new StringBuilder();

            if (cameraTrackingState != TrackingState.TRACKING) {
                // If not tracking, show the tracking failure reason
                String trackingFailureReason = TrackingStateHelper.getTrackingFailureReasonString(camera);
                if (!trackingFailureReason.isEmpty()) {
                    statusBuilder.append("⚠️ ").append(trackingFailureReason).append("\n");
                } else {
                    statusBuilder.append("⚠️ Tracking not stabilized yet. Please wait.\n");
                }
            } else {
                // If tracking, provide guidance based on plane detection and feature map quality
                if (trackingPlanes > 0) {
                    statusBuilder.append("✓ Detected ").append(trackingPlanes)
                            .append(" surface").append(trackingPlanes > 1 ? "s" : "").append(".\n");
                    statusBuilder.append("👆 Tap on a surface to place an anchor.\n");
                } else {
                    statusBuilder.append("🔍 Move device to detect surfaces...\n");
                }

                // Add general guidance for better feature mapping
                statusBuilder.append("\nFor best results:\n")
                        .append("• Move slowly in a circular pattern\n")
                        .append("• Ensure good lighting conditions\n")
                        .append("• Look for surfaces with texture or patterns\n")
                        .append("• Avoid plain, reflective, or moving surfaces");
            }

            statusText.setText(statusBuilder.toString());
        });
    }

    private void checkCloudAnchorStates() {
        if (session == null) {
            return;
        }

        // Check all anchors in the manager
        for (MapScanningManager.AnchorWithCloudId anchorWithCloudId : mapScanningManager.getHostedAnchors()) {
            Anchor anchor = anchorWithCloudId.getAnchor();
            TrackingState trackingState = anchor.getTrackingState();

            // Log the current state of each anchor
            Timber.d("Hosted anchor state: trackingState=%s, pose=%s",
                    trackingState, anchor.getPose());
        }

        // Check all pending anchors - with the new API, we don't need to check cloud anchor states here
        // as they are handled by the MapScanningManager.onUpdate() method and callbacks
        synchronized (anchorLock) {
            List<Anchor> anchorsToRemove = new ArrayList<>();
            for (Anchor anchor : pendingAnchors) {
                TrackingState trackingState = anchor.getTrackingState();

                // Log the current state of each pending anchor
                Timber.d("Pending anchor state: trackingState=%s, pose=%s",
                        trackingState, anchor.getPose());

                // Remove anchors that are no longer tracking
                if (trackingState == TrackingState.STOPPED) {
                    Timber.w("Pending anchor stopped tracking, removing");
                    anchorsToRemove.add(anchor);

                    // If this was our feature map quality anchor, reset the state
                    if (anchorPose != null && !hostedAnchor) {
                        anchorPose = null;
                        featureMapQualityUi = null;
                        hostedAnchor = false;
                    }
                }
            }

            // Remove processed anchors from the pending list
            pendingAnchors.removeAll(anchorsToRemove);
        }
    }

    /**
     * Updates and renders the feature map quality UI.
     * This method evaluates the quality of the feature map around the anchor
     * and automatically hosts the anchor when the quality is sufficient.
     *
     * @param camera The ARCore camera
     * @param colorCorrectionRgba The color correction values
     */
    private void updateFeatureMapQualityUi(Camera camera, float[] colorCorrectionRgba) {
        if (anchorPose == null || featureMapQualityUi == null || hostedAnchor || session == null) {
            return;
        }

        // Calculate distance from camera to anchor for guidance
        Pose featureMapQualityUiPose = anchorPose.compose(featureMapQualityUi.getUiTransform());
        float[] cameraUiFrame = featureMapQualityUiPose.inverse().compose(camera.getPose()).getTranslation();
        double distance = Math.hypot(cameraUiFrame[0], cameraUiFrame[2]);

        long now = SystemClock.uptimeMillis();
        // Call estimateFeatureMapQualityForHosting() every 500ms
        if (now - lastEstimateTimestampMillis > 500 &&
                FeatureMapQualityUi.isAnchorInView(anchorTranslation, viewMatrix, projectionMatrix)) {
            lastEstimateTimestampMillis = now;

            // Update the FeatureMapQuality for the current camera viewpoint
            Session.FeatureMapQuality currentQuality = session.estimateFeatureMapQualityForHosting(camera.getPose());

            // Update the quality bar for the current viewpoint
            featureMapQualityUi.updateQualityForViewpoint(cameraUiFrame, currentQuality);

            // Calculate the overall quality across all bars
            float averageQuality = featureMapQualityUi.computeOverallQuality();
            Timber.d("Feature map quality: %s, Average quality: %f", currentQuality, averageQuality);

            // Update UI with distance guidance and quality information
            requireActivity().runOnUiThread(() -> {
                StringBuilder statusBuilder = new StringBuilder();
                statusBuilder.append("\n=== ANCHOR QUALITY ASSESSMENT ===\n\n");

                if (distance < MIN_DISTANCE) {
                    statusBuilder.append("⚠️ Too close to the anchor. Move back.\n");
                } else if (distance > MAX_DISTANCE) {
                    statusBuilder.append("⚠️ Too far from the anchor. Move closer.\n");
                } else {
                    statusBuilder.append("✓ Good distance. Move around the anchor to improve quality.\n");
                }

                // Add the current quality information with color indicators
                statusBuilder.append("\nQuality assessment:\n");

                // Display the average quality as a percentage
                float qualityPercentage = averageQuality * 100;
                statusBuilder.append(String.format("Overall quality: %.1f%%\n", qualityPercentage));

                // Add color indicators for quality levels
                if (qualityPercentage < 60) {
                    statusBuilder.append("🔴 More scanning needed\n");
                } else if (qualityPercentage < 80) {
                    statusBuilder.append("🟡 Quality is sufficient\n");
                } else {
                    statusBuilder.append("🟢 Good quality\n");
                }

                // Add guidance for improving quality
                statusBuilder.append("\nTo improve quality:\n");
                statusBuilder.append("• Move around the anchor in a circular pattern\n");
                statusBuilder.append("• Capture the anchor from different angles\n");
                statusBuilder.append("• Keep the anchor in view while moving\n");

                // Add the current average quality information
                statusBuilder.append("\nOverall quality: ").append(String.format("%.2f", averageQuality));
                statusBuilder.append(" (threshold: ").append(String.format("%.2f", QUALITY_THRESHOLD)).append(")");

                // Add guidance based on quality
                if (averageQuality < QUALITY_THRESHOLD) {
                    statusBuilder.append("\n\n💡 TIP: Move around the anchor in a circle to capture it from different angles.");
                } else {
                    statusBuilder.append("\n\n🎉 Quality is sufficient! Preparing to host...");
                }

                statusText.setText(statusBuilder.toString());
            });

            // Host the anchor automatically if the quality threshold is reached
            if (averageQuality >= QUALITY_THRESHOLD) {
                Timber.d("Feature map quality threshold reached (%f >= %f), hosting anchor",
                        averageQuality, QUALITY_THRESHOLD);

                // Find the anchor in the pending list
                synchronized (anchorLock) {
                    for (Anchor anchor : pendingAnchors) {
                        if (anchor.getTrackingState() == TrackingState.TRACKING) {
                            // Host the cloud anchor
                            hostedAnchor = true;

                            // Create a listener for the cloud anchor hosting result
                            MapScanningManager.CloudAnchorHostListener hostListener =
                                    new MapScanningManager.CloudAnchorHostListener() {
                                @Override
                                public void onCloudTaskComplete(@Nullable String cloudAnchorId,
                                        CloudAnchorState cloudAnchorState, Anchor anchor) {
                                    Timber.d("Cloud anchor hosting completed with state: %s, id: %s",
                                            cloudAnchorState, cloudAnchorId);
                                    handleCloudAnchorResult(cloudAnchorId, cloudAnchorState, anchor);

                                    // Reset the feature map quality UI state
                                    anchorPose = null;
                                    featureMapQualityUi = null;
                                }
                            };

                            // Host the cloud anchor using the manager
                            mapScanningManager.hostCloudAnchor(anchor, hostListener);

                            // No need to show message when hosting

                            break;
                        }
                    }
                }
            }
        }

        // Render the feature map quality UI
        // Save current OpenGL state
        int[] oldDepthFunc = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_DEPTH_FUNC, oldDepthFunc, 0);
        boolean depthTest = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);

        // Set up OpenGL state for feature map quality UI rendering
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // Draw the feature map quality UI
        featureMapQualityUi.drawUi(anchorPose, viewMatrix, projectionMatrix, colorCorrectionRgba);

        // Restore previous OpenGL state
        if (!depthTest) GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        if (!blend) GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthFunc(oldDepthFunc[0]);

        // Log that we're drawing the feature map quality UI
        Timber.d("Drawing feature map quality UI at pose: %s", anchorPose.toString());
    }

    private void handleCloudAnchorResult(String cloudAnchorId, CloudAnchorState cloudAnchorState, Anchor anchor) {
        requireActivity().runOnUiThread(() -> {
            if (!cloudAnchorState.isError()) {
                // Only process if we have a valid cloud ID
                if (cloudAnchorId != null && anchor != null) {
                    // The anchor is already added to the manager in the callback
                    // Just update the UI
                    updateAnchorCountText(); // This will also update the save button state
                    // No need to show success message

                    // Remove from pending anchors list since it's now hosted
                    synchronized (anchorLock) {
                        pendingAnchors.remove(anchor);
                    }
                }
            } else {
                // Don't show error message
                if (anchor != null) {
                    // Remove from pending anchors list
                    synchronized (anchorLock) {
                        pendingAnchors.remove(anchor);
                    }
                    anchor.detach();
                }
            }
        });
    }

    private void handleTap(Frame frame, TrackingState cameraTrackingState) {
        // Skip tap handling if camera is not tracking
        if (cameraTrackingState != TrackingState.TRACKING) {
            // Don't show any message, just return
            return;
        }

        // If we already have an anchor that we're evaluating for quality and the user taps again,
        // simply dismiss the new tap without showing any message
        if (anchorPose != null && !hostedAnchor) {
            // Dismiss the new tap - don't allow creating a new anchor until the current one is hosted
            synchronized (singleTapLock) {
                queuedSingleTap = null; // Clear the tap to prevent processing
            }
            return;
        }

        synchronized (singleTapLock) {
            if (queuedSingleTap == null) {
                return;
            }

            // Process the tap
            MotionEvent tap = queuedSingleTap;
            queuedSingleTap = null; // Clear the tap immediately to prevent double processing

            // Perform hit test
            List<HitResult> hitResults = frame.hitTest(tap);
            if (hitResults.isEmpty()) {
                Timber.d("No hit results for tap");
                // Don't show any message, just return
                return;
            }

            // Find the first valid hit result
            for (HitResult hit : hitResults) {
                if (shouldCreateAnchorWithHit(hit)) {
                    if (mapScanningManager.isMaxAnchorsReached()) {
                        // Don't show any message, just break
                        break;
                    }

                    try {
                        // Create a new anchor but don't host it yet
                        Anchor newAnchor = hit.createAnchor();
                        Timber.d("Created new anchor at pose: %s", newAnchor.getPose());

                        // Store the anchor pose for feature map quality UI
                        anchorPose = newAnchor.getPose();
                        anchorPose.getTranslation(anchorTranslation, 0);
                        anchorTranslation[3] = 1.0f; // Set w component for homogeneous coordinates
                        hostedAnchor = false;
                        shouldDrawFeatureMapQualityUi = true;
                        lastEstimateTimestampMillis = 0;
                        Timber.d("Set anchor pose: %s and translation: [%f, %f, %f, %f]",
                                anchorPose.toString(), anchorTranslation[0], anchorTranslation[1], anchorTranslation[2], anchorTranslation[3]);

                        // Create the appropriate feature map quality UI based on the plane type
                        Trackable trackable = hit.getTrackable();
                        if (trackable instanceof Plane) {
                            Plane plane = (Plane) trackable;
                            if (plane.getType() == Plane.Type.VERTICAL) {
                                featureMapQualityUi = FeatureMapQualityUi.createVerticalFeatureMapQualityUi(featureMapQualityBarRenderer);
                                Timber.d("Created vertical feature map quality UI");
                            } else {
                                featureMapQualityUi = FeatureMapQualityUi.createHorizontalFeatureMapQualityUi(featureMapQualityBarRenderer);
                                Timber.d("Created horizontal feature map quality UI");
                            }
                        } else {
                            // Default to horizontal for other trackable types
                            featureMapQualityUi = FeatureMapQualityUi.createHorizontalFeatureMapQualityUi(featureMapQualityBarRenderer);
                            Timber.d("Created default horizontal feature map quality UI");
                        }

                        // Add to pending anchors list to visualize
                        synchronized (anchorLock) {
                            pendingAnchors.add(newAnchor);
                        }

                        // No need to show guidance message

                        break;
                    } catch (Exception e) {
                        Timber.e(e, "Error creating anchor");
                        // Don't show error message
                    }
                }
            }
        }
    }

    private static boolean shouldCreateAnchorWithHit(HitResult hit) {
        Trackable trackable = hit.getTrackable();
        if (trackable instanceof Plane) {
            // Check if the hit was within the plane's polygon
            return ((Plane) trackable).isPoseInPolygon(hit.getHitPose());
        } else if (trackable instanceof Point) {
            // Check if the hit was against an oriented point
            return ((Point) trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL;
        }
        return false;
    }

    private void updateAnchorCountText() {
        int anchorCount = mapScanningManager.getAnchorCount();
        int maxAnchors = mapScanningManager.getMaxAnchors();
        anchorCountText.setText("Anchors: " + anchorCount + "/" + maxAnchors);

        // Enable save button if there are anchors
        saveButton.setEnabled(anchorCount > 0);
    }

    private void showSaveMapDialog() {
        if (mapScanningManager.getAnchorCount() == 0) {
            snackbarHelper.showError(requireActivity(), "Please add at least one anchor before saving");
            return;
        }

        // Check if user is logged in
        if (currentUser == null) {
            snackbarHelper.showError(requireActivity(), "Please sign in to save maps");
            return;
        }

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_map_name, null);
        EditText mapNameInput = dialogView.findViewById(R.id.map_name_input);

        // Set a default map name with timestamp
        String defaultMapName = "Map_" + new Date().getTime();
        mapNameInput.setText(defaultMapName);
        mapNameInput.selectAll();

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Save Map")
                .setMessage("You are about to save a map with " + mapScanningManager.getAnchorCount() + " anchors.")
                .setView(dialogView)
                .setPositiveButton("Save", null) // Set in the show() call to prevent auto-dismiss
                .setNegativeButton("Cancel", null)
                .create();

        dialog.show();

        // Set the positive button click listener after show() to prevent auto-dismiss on validation error
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String mapName = mapNameInput.getText().toString().trim();
            if (mapName.isEmpty()) {
                mapNameInput.setError("Please enter a map name");
            } else {
                dialog.dismiss();
                saveMap(mapName);
            }
        });
    }

    private void saveMap(String mapName) {
        // This check is redundant now as we check in showSaveMapDialog(), but keeping it for safety
        if (currentUser == null) {
            Toast.makeText(requireContext(), "Please sign in to save maps", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if we have any anchors to save
        if (mapScanningManager.getAnchorCount() == 0) {
            snackbarHelper.showError(requireActivity(), "No anchors to save. Please place at least one anchor.");
            return;
        }

        snackbarHelper.showMessage(requireActivity(), "Saving map...");

        // Create a new map object
        long currentTime = new Date().getTime();
        org.openbot.mapManagement.Map map = new org.openbot.mapManagement.Map(
                null, // ID will be set by Firestore
                mapName,
                currentUser.getEmail(),
                currentUser.getUid(),
                new ArrayList<>(),
                currentTime,
                currentTime
        );

        // Add all anchors to the map
        List<MapScanningManager.AnchorWithCloudId> hostedAnchors = mapScanningManager.getHostedAnchors();
        for (int i = 0; i < hostedAnchors.size(); i++) {
            MapScanningManager.AnchorWithCloudId anchorWithCloudId = hostedAnchors.get(i);
            Pose pose = anchorWithCloudId.getPose();

            // Create a new anchor object
            org.openbot.mapManagement.Map.Anchor anchor = new org.openbot.mapManagement.Map.Anchor(
                    anchorWithCloudId.getCloudAnchorId(),
                    pose.tx(),
                    pose.ty(),
                    pose.tz(),
                    "Anchor " + (i + 1),
                    currentTime
            );

            map.addAnchor(anchor);
        }

        // Log the map data before saving
        Timber.d("Saving map with %d anchors", map.getAnchors().size());
        for (org.openbot.mapManagement.Map.Anchor anchor : map.getAnchors()) {
            Timber.d("Anchor: id=%s, position=(%f, %f, %f)",
                    anchor.getCloudAnchorId(),
                    anchor.getLatitude(),
                    anchor.getLongitude(),
                    anchor.getAltitude());
        }

        // Save the map to Firestore
        db.collection("maps")
                .add(map)
                .addOnSuccessListener(documentReference -> {
                    String mapId = documentReference.getId();
                    Timber.d("Map saved successfully with ID: %s", mapId);

                    // First hide the "Saving map..." message
                    snackbarHelper.hide(requireActivity());

                    // Show success message with a dismiss button
                    Snackbar successSnackbar = Snackbar.make(
                            requireActivity().findViewById(android.R.id.content),
                            "Map saved successfully with " + map.getAnchors().size() + " anchors",
                            Snackbar.LENGTH_INDEFINITE);

                    successSnackbar.setAction("OK", v -> {
                        // Navigate back to the map management fragment when user dismisses the message
                        Navigation.findNavController(requireView()).popBackStack();
                    });

                    successSnackbar.show();
                })
                .addOnFailureListener(e -> {
                    Timber.e(e, "Error saving map");
                    // Hide the "Saving map..." message first
                    snackbarHelper.hide(requireActivity());
                    snackbarHelper.showError(requireActivity(), "Error saving map: " + e.getMessage());
                });
    }

    private void showCancelConfirmationDialog() {
        if (mapScanningManager.getAnchorCount() > 0) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Cancel Scanning")
                    .setMessage("Are you sure you want to cancel? All " + mapScanningManager.getAnchorCount() +
                            " scanned anchors will be lost.")
                    .setPositiveButton("Yes, Discard Anchors", (dialog, which) -> {
                        // Clear all anchors
                        synchronized (anchorLock) {
                            for (Anchor anchor : pendingAnchors) {
                                anchor.detach();
                            }
                            pendingAnchors.clear();
                        }
                        mapScanningManager.clear();

                        // Navigate back
                        Navigation.findNavController(requireView()).popBackStack();
                    })
                    .setNegativeButton("No, Keep Scanning", null)
                    .show();
        } else {
            Navigation.findNavController(requireView()).popBackStack();
        }
    }


}
