package com.satinavrobotics.satibot.depth;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.satinavrobotics.satibot.arcore.ArCoreHandler;
import com.satinavrobotics.satibot.depth.depth_sources.ARCoreDepthImageGenerator;
import com.satinavrobotics.satibot.depth.depth_sources.DepthImageGenerator;
import com.satinavrobotics.satibot.depth.depth_sources.ONNXDepthImageGenerator;
import com.satinavrobotics.satibot.depth.depth_sources.TFLiteDepthImageGenerator;
import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.arcore.rendering.DepthMapRenderer;
import com.satinavrobotics.satibot.robot.ControlsFragment;

import java.io.IOException;

import timber.log.Timber;

/**
 * Base class for depth-related fragments that provides common functionality
 * for depth visualization and processing.
 *
 * This class handles:
 * - ARCore session management
 * - Depth source management (ARCore, TFLite, ONNX)
 * - Common UI elements (status text, FPS display, display mode button)
 * - GLSurfaceView setup
 * - Lifecycle management
 * - Shared preferences and robot parameters
 */
public abstract class BaseDepthFragment extends ControlsFragment {

    // Depth source types
    protected static final int DEPTH_SOURCE_ARCORE = 0;
    protected static final int DEPTH_SOURCE_TFLITE = 1;
    protected static final int DEPTH_SOURCE_ONNX = 2;

    // Core components
    protected ArCoreHandler arCoreHandler;
    protected DepthMapRenderer depthMapRenderer;
    protected DepthProcessor depthProcessor;
    protected DepthImageGenerator depthImageGenerator;
    protected NavMapOverlay navMapOverlay;

    // UI elements
    protected GLSurfaceView surfaceView;
    protected TextView statusText;
    protected TextView fpsText;
    protected Button displayModeButton;

    // State variables
    protected int currentDepthSource = DEPTH_SOURCE_ARCORE;
    protected int currentDisplayMode = DepthMapRenderer.DISPLAY_MODE_DEPTH;
    protected boolean tfLiteInitialized = false;

    // FPS calculation
    protected long lastFrameTime;
    protected float fps;
    protected static final float FPS_ALPHA = 0.2f; // For exponential moving average

    // Managers
    protected SharedPreferencesManager sharedPreferencesManager;

    // Display mode names for the button text
    protected static final String[] DISPLAY_MODE_NAMES = {"Depth Map", "Nav Map"};

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Keep the screen on while the app is running
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize SharedPreferencesManager
        sharedPreferencesManager = new SharedPreferencesManager(requireContext());

        // Initialize RobotParametersManager with context for SharedPreferences access
        RobotParametersManager.getInstance().initialize(requireContext());

        // Force landscape orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Load saved depth source preference
        currentDepthSource = sharedPreferencesManager.getDepthSource();
        Timber.d("Loaded saved depth source: %d", currentDepthSource);

        // Initialize core components
        initializeCoreComponents(view);

        // Initialize depth visualization components
        initializeDepthVisualization();
    }

    /**
     * Initialize core components that are common to all depth fragments.
     * Subclasses should call this method and then initialize their specific UI elements.
     */
    protected void initializeCoreComponents(View view) {
        // Find common UI elements - subclasses should override to find their specific elements
        findUIElements(view);

        // Set up the GLSurfaceView
        if (surfaceView != null) {
            surfaceView.setPreserveEGLContextOnPause(true);
            surfaceView.setEGLContextClientVersion(2);
            surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        }

        // Initialize handlers
        Handler mainHandler = new Handler(Looper.getMainLooper());

        // Initialize ARCoreHandler with NULL renderer (we'll set our custom renderer later)
        if (surfaceView != null) {
            arCoreHandler = new ArCoreHandler(requireContext(), surfaceView, mainHandler, ArCoreHandler.RendererType.NULL);
        }

        // Create the depth processor
        depthProcessor = new DepthProcessor();

        // Set up display mode button if available
        setupDisplayModeButton();
    }

    /**
     * Find UI elements in the view. Subclasses should override this to find their specific elements.
     */
    protected abstract void findUIElements(View view);

    /**
     * Set up the display mode button functionality
     */
    protected void setupDisplayModeButton() {
        if (displayModeButton != null) {
            displayModeButton.setOnClickListener(v -> {
                if (depthMapRenderer != null) {
                    // Cycle the display mode
                    if (currentDisplayMode == DepthMapRenderer.DISPLAY_MODE_DEPTH) {
                        currentDisplayMode = DepthMapRenderer.DISPLAY_MODE_NAV;
                    } else {
                        currentDisplayMode = DepthMapRenderer.DISPLAY_MODE_DEPTH;
                    }
                    Timber.d("Display mode changed to: %d", currentDisplayMode);
                    updateDisplayModeButtonText(currentDisplayMode);

                    // Handle display mode change - subclasses can override
                    onDisplayModeChanged(currentDisplayMode);
                }
            });
        }
    }

    /**
     * Called when display mode changes. Subclasses can override to handle mode changes.
     */
    protected void onDisplayModeChanged(int newDisplayMode) {
        // Default implementation - subclasses can override
    }

    /**
     * Initialize navigation map overlay. Subclasses can override to provide specific implementation.
     */
    protected void initializeNavMapOverlay() {
        // Default implementation - subclasses can override
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

            switch (currentDepthSource) {
                case DEPTH_SOURCE_TFLITE:
                    if (TFLiteDepthImageGenerator.isTFLiteInitialized()) {
                        newGenerator = new TFLiteDepthImageGenerator();
                        updateStatusText("Switched to TensorFlow Lite depth source");
                    } else {
                        updateStatusText("TensorFlow Lite not initialized, using ARCore");
                        newGenerator = new ARCoreDepthImageGenerator();
                        currentDepthSource = DEPTH_SOURCE_ARCORE;
                    }
                    break;
                case DEPTH_SOURCE_ONNX:
                    newGenerator = new ONNXDepthImageGenerator();
                    updateStatusText("Switched to ONNX Runtime depth source");
                    break;
                case DEPTH_SOURCE_ARCORE:
                default:
                    newGenerator = new ARCoreDepthImageGenerator();
                    updateStatusText("Switched to ARCore depth source");
                    break;
            }

            // Update the depth image generator
            depthImageGenerator = newGenerator;

            try {
                // Set the new depth image generator in the depth processor
                depthProcessor.setDepthImageGenerator(depthImageGenerator, requireContext());

                // Save the depth source preference
                sharedPreferencesManager.setDepthSource(currentDepthSource);

                updateStatusText("Depth source updated successfully");

            } catch (IOException e) {
                Timber.e(e, "Error setting depth image generator: %s", e.getMessage());
                updateStatusText("Error setting depth image generator: " + e.getMessage());
            }

        } catch (Exception e) {
            updateStatusText("Error switching depth source: " + e.getMessage());
        }
    }

    /**
     * Get the depth source name for display
     */
    protected String getDepthSourceName(int depthSource) {
        switch (depthSource) {
            case DEPTH_SOURCE_TFLITE:
                return "TensorFlow Lite";
            case DEPTH_SOURCE_ONNX:
                return "ONNX Runtime";
            case DEPTH_SOURCE_ARCORE:
            default:
                return "ARCore";
        }
    }

    /**
     * Update status text in UI thread
     */
    @SuppressLint("DefaultLocale")
    protected void updateStatusText(final String text) {
        if (statusText == null) return;

        // Check if fragment is still attached to an activity
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // Check again in case fragment was detached while waiting for UI thread
                if (statusText != null) {
                    statusText.setText(text);
                }
            });
        }
    }

    /**
     * Update FPS text in UI thread
     */
    @SuppressLint("DefaultLocale")
    protected void updateFpsText(final float fps) {
        if (fpsText == null) return;

        // Check if fragment is still attached to an activity
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // Check again in case fragment was detached while waiting for UI thread
                if (fpsText != null) {
                    fpsText.setText(String.format("%.1f FPS", fps));
                }
            });
        }
    }

    /**
     * Updates the display mode button text based on the current mode.
     */
    protected void updateDisplayModeButtonText(int displayMode) {
        if (displayModeButton == null) return;

        // Check if fragment is still attached to an activity
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // Check again in case fragment was detached while waiting for UI thread
                if (displayModeButton != null) {
                    switch (displayMode) {
                        case DepthMapRenderer.DISPLAY_MODE_DEPTH:
                            displayModeButton.setText("Depth Map");
                            break;
                        case DepthMapRenderer.DISPLAY_MODE_NAV:
                            displayModeButton.setText("Navigation");
                            break;
                        default:
                            displayModeButton.setText("Display Mode");
                            break;
                    }
                }
            });
        }
    }

    /**
     * Initialize the depth visualization components and set them up with ARCoreHandler
     */
    protected void initializeDepthVisualization() {
        try {
            // Check if TFLite is initialized if needed
            if (currentDepthSource == DEPTH_SOURCE_TFLITE) {
                tfLiteInitialized = TFLiteDepthImageGenerator.isTFLiteInitialized();
            }

            // Create the appropriate depth image generator based on the current selection
            boolean onnxInitialized = true;
            if (currentDepthSource == DEPTH_SOURCE_TFLITE) {
                // Check the static initialization flag
                if (TFLiteDepthImageGenerator.isTFLiteInitialized()) {
                    depthImageGenerator = new TFLiteDepthImageGenerator();
                    updateStatusText("Using TensorFlow Lite depth source");
                } else {
                    // TFLite not initialized, initialize it now
                    updateStatusText("Initializing TensorFlow Lite...");
                    TFLiteDepthImageGenerator.initializeTFLite(requireContext(), () -> {
                        tfLiteInitialized = TFLiteDepthImageGenerator.isTFLiteInitialized();
                        if (tfLiteInitialized) {
                            // Try to recreate the renderer on the GL thread
                            requireActivity().runOnUiThread(() -> {
                                try {
                                    depthImageGenerator = new TFLiteDepthImageGenerator();
                                    depthProcessor.setDepthImageGenerator(depthImageGenerator, requireContext());
                                    updateStatusText("TensorFlow Lite initialized successfully");
                                } catch (Exception e) {
                                    Timber.e(e, "Error setting TFLite depth generator: %s", e.getMessage());
                                    updateStatusText("Error initializing TensorFlow Lite");
                                }
                            });
                        } else {
                            // Fall back to ARCore
                            updateStatusText("TensorFlow Lite initialization failed, using ARCore instead");
                            currentDepthSource = DEPTH_SOURCE_ARCORE;
                        }
                    });

                    // Use ARCore for now until TFLite is initialized
                    depthImageGenerator = new ARCoreDepthImageGenerator();
                    updateStatusText("Using ARCore depth source while initializing TensorFlow Lite");
                }
            } else if (currentDepthSource == DEPTH_SOURCE_ONNX && onnxInitialized) {
                depthImageGenerator = new ONNXDepthImageGenerator();
                updateStatusText("Using ONNX Runtime depth source");
            } else {
                depthImageGenerator = new ARCoreDepthImageGenerator();
                updateStatusText("Using ARCore depth source");

                // Update the current depth source if needed
                if (currentDepthSource != DEPTH_SOURCE_ARCORE) {
                    currentDepthSource = DEPTH_SOURCE_ARCORE;
                }
            }

            try {
                // Set the depth image generator in the depth processor
                depthProcessor.setDepthImageGenerator(depthImageGenerator, requireContext());

                // Create the depth map renderer
                depthMapRenderer = new DepthMapRenderer();

                // Set FPS update listener
                depthMapRenderer.setFpsUpdateListener(() -> {
                    // Calculate and update FPS
                    long currentTime = System.currentTimeMillis();
                    if (lastFrameTime > 0) {
                        long frameTime = currentTime - lastFrameTime;
                        float currentFps = 1000.0f / frameTime;
                        fps = fps == 0 ? currentFps : fps * (1 - FPS_ALPHA) + currentFps * FPS_ALPHA;
                        updateFpsText(fps);
                    }
                    lastFrameTime = currentTime;
                });

                // Set the DepthProcessor as the ARCoreProcessor for ARCoreHandler
                if (arCoreHandler != null) {
                    arCoreHandler.setProcessor(depthProcessor);
                    arCoreHandler.setRenderer(depthMapRenderer);
                }

                // Set initial confidence threshold from RobotParametersManager
                float threshold = RobotParametersManager.getInstance().getConfidenceThreshold();
                depthMapRenderer.setConfidenceThreshold(threshold);

                // Set initial color mode (rainbow)
                depthMapRenderer.setDepthColorMode(0);

                // Set initial display mode
                updateDisplayModeButtonText(currentDisplayMode);

                // Initialize navigation map overlay if needed
                initializeNavMapOverlay();

                updateStatusText("Depth visualization initialized");

            } catch (IOException e) {
                Timber.e(e, "Error setting depth image generator: %s", e.getMessage());
                updateStatusText("Error setting depth image generator: " + e.getMessage());
            }

        } catch (Exception e) {
            Timber.e(e, "Error initializing depth visualization: %s", e.getMessage());
            updateStatusText("Error initializing depth visualization: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Force landscape orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Resume ARCoreHandler
        try {
            if (arCoreHandler != null) {
                arCoreHandler.resume();
            }
        } catch (UnavailableArcoreNotInstalledException e) {
            updateStatusText("Please install ARCore");
            Timber.e(e, "ARCore not installed");
        } catch (UnavailableApkTooOldException e) {
            updateStatusText("Please update ARCore");
            Timber.e(e, "ARCore too old");
        } catch (UnavailableSdkTooOldException e) {
            updateStatusText("Please update this app");
            Timber.e(e, "SDK too old");
        } catch (UnavailableDeviceNotCompatibleException e) {
            updateStatusText("This device does not support ARCore");
            Timber.e(e, "Device not compatible");
        } catch (CameraNotAvailableException e) {
            updateStatusText("Camera not available. Try restarting the app.");
            Timber.e(e, "Camera not available");
        } catch (Exception e) {
            updateStatusText("Failed to resume AR session: " + e.getMessage());
            Timber.e(e, "Failed to resume AR session");
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        try {
            // Pause ARCoreHandler
            if (arCoreHandler != null) {
                arCoreHandler.pause();
            }
        } catch (Exception e) {
            Timber.e(e, "Error during fragment pause: %s", e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        try {
            // Reset orientation to default - check if activity is still available
            if (getActivity() != null) {
                getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }

            // Close ARCoreHandler
            if (arCoreHandler != null) {
                arCoreHandler.closeSession();
                arCoreHandler = null;
            }

            if (depthMapRenderer != null) {
                depthMapRenderer.cleanup();
                depthMapRenderer = null;
            }

            // Release the depth processor
            if (depthProcessor != null) {
                depthProcessor.release();
                depthProcessor = null;
            }

            // Clean up the navigation map overlay
            navMapOverlay = null;

        } catch (Exception e) {
            Timber.e(e, "Error during fragment destruction: %s", e.getMessage());
        }
    }

    /**
     * Gets the depth processor.
     * @return The depth processor
     */
    public DepthProcessor getDepthProcessor() {
        return depthProcessor;
    }

    /**
     * Gets the current depth source.
     * @return The current depth source
     */
    public int getCurrentDepthSource() {
        return currentDepthSource;
    }

    /**
     * Sets the current depth source and updates the visualization.
     * @param depthSource The new depth source
     */
    public void setCurrentDepthSource(int depthSource) {
        if (depthSource != currentDepthSource) {
            currentDepthSource = depthSource;
            updateDepthSource();
        }
    }

    /**
     * Gets the current display mode.
     * @return The current display mode
     */
    public int getCurrentDisplayMode() {
        return currentDisplayMode;
    }

    /**
     * Sets the current display mode.
     * @param displayMode The new display mode
     */
    public void setCurrentDisplayMode(int displayMode) {
        if (displayMode != currentDisplayMode) {
            currentDisplayMode = displayMode;
            updateDisplayModeButtonText(currentDisplayMode);
            onDisplayModeChanged(currentDisplayMode);
        }
    }
}
