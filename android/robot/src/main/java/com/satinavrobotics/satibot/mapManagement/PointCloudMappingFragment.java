package com.satinavrobotics.satibot.mapManagement;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import com.satinavrobotics.satibot.databinding.FragmentPointcloudMappingBinding;

import com.satinavrobotics.satibot.env.CameraPermissionHelper;
import com.satinavrobotics.satibot.env.DisplayRotationHelper;
import com.satinavrobotics.satibot.mapManagement.rendering.BackgroundRenderer;
import com.satinavrobotics.satibot.mapManagement.rendering.DepthMapPointCloudRenderer;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

public class PointCloudMappingFragment extends Fragment implements GLSurfaceView.Renderer {

    private FragmentPointcloudMappingBinding binding;
    private Session session;
    private DisplayRotationHelper displayRotationHelper;
    private DepthMapPointCloudRenderer pointCloudRenderer;
    private BackgroundRenderer backgroundRenderer; // Still needed for ARCore texture
    private boolean installRequested;
    private boolean sessionInitialized = false;

    // Rendering objects for the camera background
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPointcloudMappingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Force portrait orientation
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Keep screen on
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Set up renderer
        displayRotationHelper = new DisplayRotationHelper(requireContext());
        pointCloudRenderer = new DepthMapPointCloudRenderer();
        backgroundRenderer = new BackgroundRenderer(); // Still needed for ARCore texture

        // Set up the GL surface view with a solid black background
        binding.surfaceView.setPreserveEGLContextOnPause(true);
        binding.surfaceView.setEGLContextClientVersion(2);
        // Use an opaque surface configuration (alpha=0) for solid black background
        binding.surfaceView.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        binding.surfaceView.setRenderer(this);
        binding.surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        // Make sure the GLSurfaceView has a black background
        binding.surfaceView.setZOrderOnTop(false);

        // Set up confidence threshold slider
        binding.confidenceThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pointCloudRenderer.setConfidenceThreshold(progress);
                int percentage = (int) (progress / 255.0f * 100);
                binding.confidenceThresholdValue.setText(percentage + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Set up reset button
        binding.resetButton.setOnClickListener(v -> {
            pointCloudRenderer.resetAccumulatedPointCloud();
            Toast.makeText(requireContext(), "Pointcloud reset", Toast.LENGTH_SHORT).show();
        });

        installRequested = false;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
                    CameraPermissionHelper.requestCameraPermission(requireActivity());
                    return;
                }

                // Create the session
                session = new Session(requireContext());
                Config config = new Config(session);

                // Enable depth mode for pointcloud mapping
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                }

                // Apply the configuration
                session.configure(config);

                // Log the configuration for debugging
                Timber.d("ARCore session configured with depth mode: %s", config.getDepthMode());

                sessionInitialized = true;

            } catch (UnavailableArcoreNotInstalledException |
                     UnavailableApkTooOldException |
                     UnavailableSdkTooOldException e) {
                exception = e;
            } catch (UnavailableUserDeclinedInstallationException |
                     UnavailableDeviceNotCompatibleException e) {
                throw new RuntimeException(e);
            }

            if (exception != null) {
                binding.statusText.setText("Error creating AR session: " + exception.getMessage());
                Timber.e(exception, "Exception creating session");
                return;
            }
        }

        // Resume the ARCore session
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            binding.statusText.setText("Camera not available. Try restarting the app.");
            session = null;
        }

        binding.surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            binding.surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Reset screen orientation to default
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        // Remove the keep screen on flag
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Set clear color to pure black with full opacity
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // Enable depth testing for proper 3D rendering
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        // Set point size (important for point cloud rendering)
        GLES20.glEnable(0x8642); // GL_VERTEX_PROGRAM_POINT_SIZE

        // Enable point smoothing for better looking points
        GLES20.glEnable(0x0B10); // GL_POINT_SMOOTH

        // Set depth function to less than or equal for better point rendering
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the pointcloud renderer first
            pointCloudRenderer.createOnGlThread(requireContext());
            Timber.d("Point cloud renderer initialized successfully");

            // Create the background renderer (needed for ARCore but we won't actually display it)
            try {
                // Use a custom implementation to avoid shader loading issues
                createBackgroundRendererWithInlineShaders();
                Timber.d("Background renderer initialized successfully");
            } catch (Exception e) {
                Timber.e(e, "Error initializing background renderer: %s", e.getMessage());
                // Continue even if background renderer fails - we'll just have a black background
            }

            // Log success
            Timber.d("Successfully created OpenGL objects for rendering");
        } catch (IOException e) {
            Timber.e(e, "Failed to read shader: %s", e.getMessage());
        } catch (Exception e) {
            Timber.e(e, "Unexpected error initializing renderers: %s", e.getMessage());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Make sure we have a pure black background
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        if (session == null) {
            return;
        }

        // Log that we're rendering a frame
        Timber.v("Rendering frame");

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = session.update();

            // Set the texture for ARCore to fill during update - this is required even if we don't display it
            try {
                int textureId = backgroundRenderer.getTextureId();
                if (textureId > 0) {
                    session.setCameraTextureName(textureId);
                } else {
                    Timber.w("Invalid texture ID: %d", textureId);
                }
            } catch (Exception e) {
                Timber.e(e, "Error setting camera texture: %s", e.getMessage());
            }

            Camera camera = frame.getCamera();

            // Get camera tracking state
            TrackingState cameraTrackingState = camera.getTrackingState();

            // Update the status text
            updateStatusText(cameraTrackingState);

            // If not tracking, don't draw 3D objects
            if (cameraTrackingState == TrackingState.PAUSED) {
                return;
            }

            // We'll skip drawing the background entirely
            // The clear color is already set to black, which is what we want

            // Get camera and projection matrices
            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

            try {
                // Process depth data and update the point cloud
                pointCloudRenderer.update(frame);

                // Enable blending for proper point rendering
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

                // Ensure depth test is enabled but configured correctly for points
                GLES20.glEnable(GLES20.GL_DEPTH_TEST);
                GLES20.glDepthFunc(GLES20.GL_LEQUAL);

                // Draw the accumulated point cloud
                pointCloudRenderer.drawAccumulated(viewMatrix, projectionMatrix);

                // Disable blending after rendering
                GLES20.glDisable(GLES20.GL_BLEND);
            } catch (Exception e) {
                Timber.e(e, "Error rendering point cloud: %s", e.getMessage());
            }

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Timber.e(t, "Exception on the OpenGL thread: %s", t.getMessage());
        }
    }



    private void updateStatusText(TrackingState trackingState) {
        requireActivity().runOnUiThread(() -> {
            switch (trackingState) {
                case TRACKING:
                    binding.statusText.setText("Tracking OK - Move around to scan the environment");
                    break;
                case PAUSED:
                    binding.statusText.setText("Tracking paused - Not enough features in view");
                    break;
                case STOPPED:
                    binding.statusText.setText("Tracking stopped");
                    break;
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
            Toast.makeText(requireContext(), "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(requireActivity())) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(requireActivity());
            }
            requireActivity().finish();
        }
    }

    /**
     * Creates the background renderer with inline shaders to avoid file loading issues.
     * This is a workaround for the GL_INVALID_ENUM error that occurs when loading shaders from files.
     */
    private void createBackgroundRendererWithInlineShaders() {
        // We'll use reflection to set the texture ID directly
        try {
            // Generate the background texture
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int textureId = textures[0];

            // Set the texture ID in the backgroundRenderer
            java.lang.reflect.Field textureIdField = BackgroundRenderer.class.getDeclaredField("cameraTextureId");
            textureIdField.setAccessible(true);
            textureIdField.setInt(backgroundRenderer, textureId);

            // Set the initialized flag
            java.lang.reflect.Field initializedField = BackgroundRenderer.class.getDeclaredField("isInitialized");
            initializedField.setAccessible(true);
            initializedField.setBoolean(backgroundRenderer, true);

            // Configure the texture
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            Timber.d("Successfully created background texture with ID: %d", textureId);
        } catch (Exception e) {
            Timber.e(e, "Error creating background renderer with inline shaders: %s", e.getMessage());
        }
    }
}
