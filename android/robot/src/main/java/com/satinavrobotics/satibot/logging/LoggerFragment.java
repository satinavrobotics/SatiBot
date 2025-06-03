package com.satinavrobotics.satibot.logging;

import static com.satinavrobotics.satibot.utils.Enums.SpeedMode;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import com.google.ar.core.Pose;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;

import com.satinavrobotics.satibot.R;

import com.satinavrobotics.satibot.robot.ControlsFragment;
import com.satinavrobotics.satibot.databinding.FragmentLoggerBinding;
import com.satinavrobotics.satibot.env.ImageUtils;
import com.satinavrobotics.satibot.googleServices.GoogleServices;
import com.satinavrobotics.satibot.logging.render.BitmapRenderer;
import com.satinavrobotics.satibot.logging.sources.ArCoreImageSourceHandler;
import com.satinavrobotics.satibot.logging.sources.CameraImageSourceHandler;
import com.satinavrobotics.satibot.logging.sources.ExternalCameraImageSourceHandler;
import com.satinavrobotics.satibot.logging.sources.ImageSourceHandler;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;
import com.satinavrobotics.satibot.arcore.ImageFrame;
import com.satinavrobotics.satibot.arcore.PermissionDialogFragment;
import com.satinavrobotics.satibot.googleServices.GoogleSignInCallback;
import com.satinavrobotics.satibot.arcore.ArCoreHandler;
import com.satinavrobotics.satibot.utils.ImageSource;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.Enums;
import com.satinavrobotics.satibot.utils.PermissionUtils;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;
import org.json.JSONObject;
import org.json.JSONException;

import timber.log.Timber;

public class LoggerFragment extends ControlsFragment implements ImageSourceHandler.ImageSourceListener {

  private FragmentLoggerBinding binding;
  private ArCoreHandler arCore;
  private boolean isPermissionRequested = false;
  private boolean gamepadConnected = false;

  // Image source handling
  private ImageSourceHandler currentImageSourceHandler;
  private ArCoreImageSourceHandler arCoreImageSourceHandler;
  private CameraImageSourceHandler cameraImageSourceHandler;
  private ExternalCameraImageSourceHandler externalCameraImageSourceHandler;
  private ImageSource currentImageSource = ImageSource.ARCORE;
  private BitmapRenderer bitmapRenderer;

  // LOGGING
  private Handler loggingHandler;
  private HandlerThread loggingHandlerThread;
  private GoogleServices googleServices;
  protected String logFolder;

  protected boolean loggingEnabled;
  private boolean loggingCanceled;
  private static final ExecutorService executorService = Executors.newSingleThreadExecutor();


  @Override
  public View onCreateView(
          @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentLoggerBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @SuppressLint("RestrictedApi")
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // Force landscape orientation
    requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    // Keep screen on
    requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    Handler handlerMain = new Handler(Looper.getMainLooper());
    // Use SimpleARCoreRenderer instead of the default LiveKitARCoreRenderer
    arCore = new ArCoreHandler(requireContext(), binding.surfaceView, handlerMain, ArCoreHandler.RendererType.SIMPLE);

    // Initialize image source handlers
    initializeImageSourceHandlers();

    // Force "No Map" mode for simplified pose logging
    preferencesManager.setCurrentMapId("no_map");

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


    if (vehicle.getConnectionType().equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
      binding.bleToggle.setVisibility(View.GONE);
    } else if (vehicle.getConnectionType().equals("Bluetooth")) {
      binding.bleToggle.setVisibility(View.VISIBLE);
      binding.usbToggle.setVisibility(View.GONE);
    }

    setSpeedMode(SpeedMode.getByID(preferencesManager.getSpeedMode()));
    if (!PermissionUtils.hasControllerPermissions(requireActivity()))
      requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);

    mViewModel
            .getUsbStatus()
            .observe(getViewLifecycleOwner(), status -> binding.usbToggle.setChecked(status));

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

    // Check for connected gamepads
    checkForConnectedGamepads();

    // Set up logger switch to start/stop logging
    binding.loggerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (isChecked != loggingEnabled) {
        Timber.d("Logger switch toggled to %s", isChecked ? "ON" : "OFF");
        toggleLogging();
      }
    });

    // Restore saved image source spinner value
    int savedImageSource = preferencesManager.getLoggerImageSource();
    binding.imageSourceSpinner.setSelection(savedImageSource);
    currentImageSource = ImageSource.getByValue(savedImageSource);

    // Restore saved resolution spinner value
    int savedResolution = preferencesManager.getLoggerResolution();
    binding.resolutionSpinner.setSelection(savedResolution);

    // Restore saved FPS spinner value
    int savedFPS = preferencesManager.getLoggerFPS();
    binding.fpsSpinner.setSelection(savedFPS);

    // Restore saved save destination spinner value
    int savedSaveDestination = preferencesManager.getLoggerSaveDestination();
    binding.saveAs.setSelection(savedSaveDestination);

    // Set up listeners for spinners to save their values when changed
    binding.imageSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        preferencesManager.setLoggerImageSource(position);
        ImageSource newImageSource = ImageSource.getByValue(position);
        // Don't update currentImageSource here - let switchImageSource do it
        switchImageSource(newImageSource);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
        // Do nothing
      }
    });

    binding.resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        preferencesManager.setLoggerResolution(position);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
        // Do nothing
      }
    });

    binding.fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        preferencesManager.setLoggerFPS(position);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
        // Do nothing
      }
    });

    binding.saveAs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        preferencesManager.setLoggerSaveDestination(position);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
        // Do nothing
      }
    });
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();

    // Reset orientation when the view is destroyed
    if (getActivity() != null) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      // Clear screen-on flag
      getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Make sure ARCore is properly cleaned up
    if (arCore != null) {
      arCore.removeArCoreListener();
      arCore.pause();
    }

    // Prevent memory leaks by clearing references to Views
    binding = null;
  }


  @Override
  protected void processUSBData(String data) {}

  private void setSpeedMode(SpeedMode speedMode) {
    if (speedMode != null) {
      // Note: Speed mode UI removed with controllerContainer
      Timber.d("Updating  controlSpeed: %s", speedMode);
      preferencesManager.setSpeedMode(speedMode.getValue());
      vehicle.setSpeedMultiplier(speedMode.getValue());
    }
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    // If we receive any controller key data, update the gamepad connection status
    if (Objects.equals(commandType, Constants.CMD_INDICATOR_LEFT))
      if (!gamepadConnected) {
        gamepadConnected = true;
        requireActivity().runOnUiThread(() -> {
          if (binding != null) {
            binding.gamepadIndicator.setChecked(true);
            Timber.d("Gamepad connected (from controller input)");
          }
        });
      }

    switch (commandType) {
      case Constants.CMD_LOGS:
        try {
          // NOTE: added runOnUiThread, because if throws an exception, if controlled by web-server
          requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
              toggleLogging();
            }
          });
        } catch (IllegalStateException e) {
          e.printStackTrace();
        }
        break;

      case Constants.CMD_SPEED_DOWN:
        setSpeedMode(
                Enums.toggleSpeed(
                        Enums.Direction.DOWN.getValue(),
                        SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;

      case Constants.CMD_SPEED_UP:
        setSpeedMode(
                Enums.toggleSpeed(
                        Enums.Direction.UP.getValue(),
                        SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;

      case Constants.CMD_WAYPOINTS:
        // Waypoint handling removed as we're only visualizing anchors now
        Timber.d("Waypoint command received but ignored - only visualizing anchors");


    }
  }

  /**
   * Parse the resolution string from the spinner into a Size object
   *
   * @param resolutionString String in format "width x height" (e.g., "256 x 256")
   * @return Size object with width and height
   */
  private com.satinavrobotics.satibot.env.Size parseResolution(String resolutionString) {
    // Remove any spaces and split by 'x'
    String[] parts = resolutionString.replace(" ", "").split("x");
    if (parts.length == 2) {
      try {
        int width = Integer.parseInt(parts[1]);
        int height = Integer.parseInt(parts[0]);
        return new com.satinavrobotics.satibot.env.Size(width, height);
      } catch (NumberFormatException e) {
        Timber.e(e, "Error parsing resolution: %s", resolutionString);
      }
    }
    // Default to 256x256 if parsing fails
    return new com.satinavrobotics.satibot.env.Size(256, 256);
  }

  /**
   * Get the currently selected resolution from the spinner
   *
   * @return Size object with the selected width and height
   */
  private com.satinavrobotics.satibot.env.Size getSelectedResolution() {
    if (binding == null) {
      // Default to 256x256 if binding is null
      return new com.satinavrobotics.satibot.env.Size(256, 256);
    }

    String resolutionString = binding.resolutionSpinner.getSelectedItem().toString();
    return parseResolution(resolutionString);
  }

  /**
   * Get the currently selected FPS from the spinner
   *
   * @return FPS value as integer
   */
  private int getSelectedFPS() {
    if (binding == null) {
      // Default to 15 FPS if binding is null
      return 15;
    }

    String fpsString = binding.fpsSpinner.getSelectedItem().toString();
    // Extract the number from strings like "15 FPS"
    String[] parts = fpsString.split(" ");
    if (parts.length > 0) {
      try {
        return Integer.parseInt(parts[0]);
      } catch (NumberFormatException e) {
        Timber.e(e, "Error parsing FPS: %s", fpsString);
      }
    }
    // Default to 15 FPS if parsing fails
    return 15;
  }

  /**
   * Save metadata (FPS and resolution) to metadata.json file in the save folder
   */
  private void saveMetadata() {
    if (logFolder == null) {
      Timber.w("Cannot save metadata: logFolder is null");
      return;
    }

    try {
      // Get current settings
      com.satinavrobotics.satibot.env.Size resolution = getSelectedResolution();
      int fps = getSelectedFPS();

      // Create JSON object with metadata
      JSONObject metadata = new JSONObject();
      metadata.put("fps", fps);
      metadata.put("resolution_width", resolution.width);
      metadata.put("resolution_height", resolution.height);
      metadata.put("resolution_string", resolution.width + "x" + resolution.height);
      metadata.put("timestamp", System.currentTimeMillis());
      metadata.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

      // Write to metadata.json file
      File metadataFile = new File(logFolder, "metadata.json");
      try (FileWriter writer = new FileWriter(metadataFile)) {
        writer.write(metadata.toString(2)); // Pretty print with 2-space indentation
      }

      Timber.d("Saved metadata: FPS=%d, Resolution=%dx%d to %s", fps, resolution.width, resolution.height, metadataFile.getAbsolutePath());

    } catch (JSONException | java.io.IOException e) {
      Timber.e(e, "Error saving metadata");
    }
  }

  private void setupArCore() {
    try {
      // Make sure we're using "No Map" mode
      preferencesManager.setCurrentMapId("no_map");

      // Resume ARCore session
      arCore.resume();
      return;
    } catch (SecurityException e) {
      e.printStackTrace();
      showPermissionDialog();
      return;
    } catch (UnavailableSdkTooOldException e) {
      e.printStackTrace();
    } catch (UnavailableDeviceNotCompatibleException e) {
      e.printStackTrace();
    } catch (UnavailableArcoreNotInstalledException e) {
      e.printStackTrace();
    } catch (UnavailableApkTooOldException e) {
      e.printStackTrace();
    } catch (CameraNotAvailableException e) {
      e.printStackTrace();
    }
  }


  // Local pose computation methods are now handled by ArCoreHandler
  private void resume() {
    if (arCore == null)
      return;
    if (!PermissionUtils.hasCameraPermission(requireActivity())) {
      getCameraPermission();
    } else if (PermissionUtils.shouldShowRational(requireActivity(), Constants.PERMISSION_CAMERA)) {
      PermissionUtils.showCameraPermissionsPreviewToast(requireActivity());
    } else {
      setupArCore();
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    // Note: ARCore listener is now managed by the image source handlers

    // Check for connected gamepads when the fragment starts
    checkForConnectedGamepads();
  }

  @Override
  public void onResume() {
    loggingHandlerThread = new HandlerThread("logging");
    loggingHandlerThread.start();
    loggingHandler = new Handler(loggingHandlerThread.getLooper());
    super.onResume();

    // Force landscape orientation
    requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    // Keep screen on
    requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    // Check logging permissions status
    boolean hasLoggingPermissions = PermissionUtils.hasLoggingPermissions(requireActivity());
    if (!hasLoggingPermissions) {
      Timber.d("Some logging permissions are missing");
    }

    // Check if binding is not null before accessing its properties
    if (binding != null) {
      binding.bleToggle.setChecked(vehicle.bleConnected());
      // Initialize logger switch state
      binding.loggerSwitch.setChecked(loggingEnabled);
      // Check for connected gamepads
      checkForConnectedGamepads();
    }

    // Only start ARCore session if the current image source needs it
    if (needsArCoreSession(currentImageSource)) {
      resume();
    }

    // Start capture for the current image source handler
    if (currentImageSourceHandler != null) {
      currentImageSourceHandler.startCapture();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    // Cleanup image source handlers
    if (arCoreImageSourceHandler != null) {
      arCoreImageSourceHandler.cleanup();
      arCoreImageSourceHandler = null;
    }
    if (cameraImageSourceHandler != null) {
      cameraImageSourceHandler.cleanup();
      cameraImageSourceHandler = null;
    }
    if (externalCameraImageSourceHandler != null) {
      externalCameraImageSourceHandler.cleanup();
      externalCameraImageSourceHandler = null;
    }
    currentImageSourceHandler = null;

    // Cleanup bitmap renderer
    if (bitmapRenderer != null) {
      bitmapRenderer.stopRendering();
      bitmapRenderer = null;
    }

    // Close ARCore session
    if (arCore != null) {
      arCore.closeSession();
      arCore = null;
    }
  }

  @Override
  public void onPause() {
    // Stop logging if it's active
    if (loggingEnabled) {
      setLoggingActive(false);
    }

    // Stop all image source handlers
    if (currentImageSourceHandler != null) {
      currentImageSourceHandler.stopCapture();
    }

    // First handle the logging thread
    if (loggingHandlerThread != null) {
      loggingHandlerThread.quitSafely();
      try {
        loggingHandlerThread.join();
        loggingHandlerThread = null;
        loggingHandler = null;
      } catch (final InterruptedException e) {
        Timber.e(e, "Error shutting down logging thread");
        Thread.currentThread().interrupt();
      }
    }

    super.onPause();

    // Only pause ARCore if the current image source needs it
    if (needsArCoreSession(currentImageSource)) {
      arCore.pause();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    arCore.removeArCoreListener();
  }

  @Override
  public void onDetach() {
    super.onDetach();

    // Reset orientation when the fragment is detached from its activity
    if (getActivity() != null) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      // Clear screen-on flag
      getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  private void getCameraPermission() {
    if (!isPermissionRequested) {
      requestPermissionLauncherCamera.launch(Constants.PERMISSION_CAMERA);
      isPermissionRequested = true;
    } else {
      showPermissionDialog();
    }
  }

  private void showPermissionDialog() {
    if (getChildFragmentManager().findFragmentByTag(PermissionDialogFragment.TAG) == null) {
      PermissionDialogFragment dialog = new PermissionDialogFragment();
      dialog.setCancelable(false);
      dialog.show(getChildFragmentManager(), PermissionDialogFragment.TAG);
    }

    getChildFragmentManager()
            .setFragmentResultListener(
                    PermissionDialogFragment.TAG,
                    getViewLifecycleOwner(),
                    (requestKey, result) -> {
                      String choice = result.getString("choice");

                        assert choice != null;
                        if (choice.equals("settings")) {
                        PermissionUtils.startInstalledAppDetailsActivity(requireActivity());
                      } else if (choice.equals("retry")) {
                        isPermissionRequested = false;
                        if (needsArCoreSession(currentImageSource)) {
                          resume();
                        }
                        if (currentImageSourceHandler != null) {
                          currentImageSourceHandler.startCapture();
                        }
                      } else {
                        requireActivity().onBackPressed();
                      }
                    });
  }

  private final ActivityResultLauncher<String> requestPermissionLauncherCamera =
          registerForActivityResult(
                  new ActivityResultContracts.RequestPermission(),
                  isGranted -> {
                    if (isGranted) {
                      if (needsArCoreSession(currentImageSource)) {
                        setupArCore();
                      }
                      if (currentImageSourceHandler != null) {
                        currentImageSourceHandler.startCapture();
                      }
                    } else {
                      showPermissionDialog();
                    }
                  });



  private void startLogging() {
    logFolder =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    .getAbsolutePath()
                    + File.separator
                    + getString(R.string.app_name)
                    + File.separator
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

    // Create the log directory if it doesn't exist
    File logDir = new File(logFolder);
    if (!logDir.exists()) {
      if (logDir.mkdirs()) {
        Timber.d("Created log directory: %s", logFolder);
      } else {
        Timber.e("Failed to create log directory: %s", logFolder);
      }
    }

    // Create the images directory
    File imagesDir = new File(logFolder + File.separator + "images");
    if (!imagesDir.exists()) {
      if (imagesDir.mkdirs()) {
        Timber.d("Created images directory: %s", imagesDir.getAbsolutePath());
      } else {
        Timber.e("Failed to create images directory: %s", imagesDir.getAbsolutePath());
      }
    }

    // Create the poses directory
    File posesDir = new File(logFolder + File.separator + "poses");
    if (!posesDir.exists()) {
      if (posesDir.mkdirs()) {
        Timber.d("Created poses directory: %s", posesDir.getAbsolutePath());
      } else {
        Timber.e("Failed to create poses directory: %s", posesDir.getAbsolutePath());
      }
    }

    // Reset frame counter and pose header flag for new logging session
    frameNum = 0;
    poseHeaderWritten = false;

    // Save metadata
    saveMetadata();

    Timber.d("Started logging to folder: %s", logFolder);
  }

  private void stopLogging(boolean isCancel) {
    Timber.d("Stopping logging, isCancel=%s", isCancel);

    if (logFolder == null) {
      Timber.w("Cannot stop logging: logFolder is null");
      return;
    }

    // Pack and upload the collected data
    runInBackground(() -> {
      try {
        File folder = new File(logFolder);
        if (folder.exists()) {
          if (isCancel) {
            Timber.d("Logging canceled, deleting data without uploading");
            FileUtils.deleteQuietly(folder);
          } else {
            Timber.d("Zipping log data from %s", folder.getAbsolutePath());
            File zipFile = zip(folder);
            Timber.d("Created zip file: %s", zipFile.getAbsolutePath());

            // Check save destination - only upload to Google Drive if it's selected
            int saveDestination = preferencesManager.getLoggerSaveDestination();
            if (saveDestination == 1) { // 1 = Google Drive
              Timber.d("Save destination is Google Drive, uploading data");
              googleServices.uploadLogData(zipFile);
              Timber.d("Upload initiated, waiting before cleanup");
              TimeUnit.MILLISECONDS.sleep(500);
            } else {
              Timber.d("Save destination is Local Storage, skipping Google Drive upload");
              // Keep the zip file locally without uploading
            }

            // Clean up the original folder (not the zip file)
            Timber.d("Cleaning up log folder: %s", folder.getAbsolutePath());
            FileUtils.deleteQuietly(folder);
          }
        } else {
          Timber.w("Log folder does not exist: %s", folder.getAbsolutePath());
        }
      } catch (InterruptedException e) {
        Timber.e(e, "Got interrupted during log cleanup");
      } catch (Exception e) {
        Timber.e(e, "Error during log cleanup");
      }
    });
    loggingEnabled = false;
    Timber.d("Logging stopped");
  }

  private File zip(File folder) {
    String zipFileName = folder + ".zip";
    File zip = new File(zipFileName);
    ZipUtil.pack(folder, zip);
    return zip;
  }

  private void cancelLogging() {
    loggingCanceled = true;
    setLoggingActive(false);
    audioPlayer.playFromString("Log deleted!");
  }

  protected void toggleLogging() {
    loggingCanceled = false;
    Timber.d("Logging toggled");
    setLoggingActive(!loggingEnabled);
    audioPlayer.playLogging(voice, loggingEnabled);
  }

  // Single toast instance to prevent toast queue overflow
  private Toast currentToast;

  /**
   * Shows a toast message, canceling any previous toast to prevent queue overflow
   * @param message The message to display
   */
  private void showToast(String message) {
    if (currentToast != null) {
      currentToast.cancel();
    }
    currentToast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT);
    currentToast.show();
  }

  protected void setLoggingActive(boolean enableLogging) {

    if (enableLogging && !loggingEnabled) {
      // Double-check permissions to avoid infinite loop
      boolean hasAllPermissions = PermissionUtils.hasLoggingPermissions(requireActivity());

      if (!hasAllPermissions) {
        // Request all required permissions
        requestPermissionLauncherLogging.launch(Constants.PERMISSIONS_LOGGING);
        loggingEnabled = false;

        // Show a toast to inform the user
        showToast("Requesting permissions for logging");
      } else {
        startLogging();
        loggingEnabled = true;

        showToast("Logging started");
      }
    } else if (!enableLogging && loggingEnabled) {
      stopLogging(loggingCanceled);
      loggingEnabled = false;
      showToast("Logging stopped");
    }

    // Check if binding is not null before accessing its properties
    if (binding != null) {
      // Update the recording indicator visibility
      binding.recordingIndicator.setVisibility(loggingEnabled ? View.VISIBLE : View.INVISIBLE);

      // Update the logger switch state without triggering the listener
      binding.loggerSwitch.setOnCheckedChangeListener(null);
      binding.loggerSwitch.setChecked(loggingEnabled);
      binding.loggerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
        if (isChecked != loggingEnabled) {
          toggleLogging();
        }
      });
    }


  }

  /**
   * Checks for connected gamepads and updates the gamepad indicator
   */
  private void checkForConnectedGamepads() {
    if (binding == null) return;

    // Get all input devices
    int[] deviceIds = InputDevice.getDeviceIds();
    boolean hasGamepad = false;

    // Check if any of them are gamepads
    for (int deviceId : deviceIds) {
      InputDevice device = InputDevice.getDevice(deviceId);
      // Check if this is a gamepad
      if (device != null &&
          ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
           (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) {
        hasGamepad = true;
        break;
      }
    }

    // Always update the gamepad indicator to ensure it's properly set
    gamepadConnected = hasGamepad;
    requireActivity().runOnUiThread(() -> {
      if (binding != null) {
        binding.gamepadIndicator.setChecked(gamepadConnected);
      }
    });
  }


  protected synchronized void runInBackground(final Runnable r) {
    if (loggingHandler != null) {
      loggingHandler.post(r);
    }
  }

  protected final ActivityResultLauncher<String[]> requestPermissionLauncherLogging =
          registerForActivityResult(
                  new ActivityResultContracts.RequestMultiplePermissions(),
                  result -> {
                    // Reset the flag for each permission request
                    boolean allGranted = true;

                    // Check if all permissions were granted
                    for (Boolean granted : result.values()) {
                      allGranted = allGranted && granted;
                    }

                    if (allGranted) {

                      // Start logging directly without going through setLoggingActive again
                      startLogging();
                      loggingEnabled = true;

                      showToast("Logging started");

                      // Update UI
                      if (binding != null) {
                        // Update the recording indicator visibility
                        binding.recordingIndicator.setVisibility(View.VISIBLE);

                        // Update the logger switch state without triggering the listener
                        binding.loggerSwitch.setOnCheckedChangeListener(null);
                        binding.loggerSwitch.setChecked(true);
                        binding.loggerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                          if (isChecked != loggingEnabled) {
                            toggleLogging();
                          }
                        });
                      }
                    } else {
                      // Show a single toast message instead of multiple ones
                      showToast("Some permissions were denied. Logging requires all permissions.");

                      // Update the switch to reflect that logging couldn't be started
                      if (binding != null) {
                        binding.loggerSwitch.setOnCheckedChangeListener(null);
                        binding.loggerSwitch.setChecked(false);
                        binding.loggerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                          if (isChecked != loggingEnabled) {
                            toggleLogging();
                          }
                        });
                      }
                    }
                  });



  public static Bitmap convertRGBFrameToScaledBitmap(ImageFrame bImg, float resizeFactor) {
    int previewHeight = bImg.getHeight();
    int previewWidth = bImg.getWidth();
    if (bImg == null || previewHeight == 0 || previewWidth == 0 || resizeFactor < 0) {
      throw new IllegalArgumentException();
    }

    int width = (int) (resizeFactor * previewWidth);
    int height = (int) (resizeFactor * previewHeight);

    Bitmap rgbFrameBitmap =
            Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
    int[] rgbBytes = new int[previewWidth * previewHeight];

    convertYUV420ToARGB8888(
            bImg.getYuvBytes()[0],
            bImg.getYuvBytes()[1],
            bImg.getYuvBytes()[2],
            previewWidth,
            previewHeight,
            bImg.getYRowStride(),
            bImg.getUvRowStride(),
            bImg.getUvPixelStride(),
            rgbBytes);

    rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
    return Bitmap.createScaledBitmap(rgbFrameBitmap, width, height, true);
  }

  public static void convertYUV420ToARGB8888(
          byte[] yData,
          byte[] uData,
          byte[] vData,
          int width,
          int height,
          int yRowStride,
          int uvRowStride,
          int uvPixelStride,
          int[] out) {
    int yp = 0;
    for (int j = 0; j < height; j++) {
      int pY = yRowStride * j;
      int pUV = uvRowStride * (j >> 1);

      for (int i = 0; i < width; i++) {
        int uv_offset = pUV + (i >> 1) * uvPixelStride;

        out[yp++] = YUV2RGB(0xff & yData[pY + i], 0xff & uData[uv_offset], 0xff & vData[uv_offset]);
      }
    }
  }

  static final int kMaxChannelValue = 262143;
  private static int YUV2RGB(int y, int u, int v) {
    // Adjust and check YUV values
    y = Math.max((y - 16), 0);
    u -= 128;
    v -= 128;

    // This is the floating point equivalent. We do the conversion in integer
    // because some Android devices do not have floating point in hardware.
    // nR = (int)(1.164 * nY + 2.018 * nU);
    // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
    // nB = (int)(1.164 * nY + 1.596 * nV);
    int y1192 = 1192 * y;
    int r = (y1192 + 1634 * v);
    int g = (y1192 - 833 * v - 400 * u);
    int b = (y1192 + 2066 * u);

    // Clipping RGB values to be inside boundaries [ 0 , kMaxChannelValue ]
    r = r > kMaxChannelValue ? kMaxChannelValue : (Math.max(r, 0));
    g = g > kMaxChannelValue ? kMaxChannelValue : (Math.max(g, 0));
    b = b > kMaxChannelValue ? kMaxChannelValue : (Math.max(b, 0));

    return 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
  }

  private long frameNum = 0;
  private boolean poseHeaderWritten = false;

  protected void processFrame(Bitmap bitmap, int width, int height) {
    ++frameNum;
    if (binding != null) {
      if (bitmap != null)
        ImageUtils.saveBitmap(
                bitmap, logFolder + File.separator + "images", frameNum + "_preview.jpeg");
    }
  }

  /**
   * Save pose data to a text file
   * @param pose The ARCore pose to save
   * @param timestamp The timestamp of the pose
   */
  private void savePoseData(Pose pose, long timestamp) {
    try {
      // Create the poses directory if it doesn't exist
      File posesDir = new File(logFolder + File.separator + "poses");
      if (!posesDir.exists()) {
        posesDir.mkdirs();
      }

      // Create or open the poses file
      File posesFile = new File(posesDir, "poses.txt");

      // Write header if this is the first time
      if (!poseHeaderWritten || !posesFile.exists()) {
        // Write the header explaining the data format
        String header = "# ARCore pose data format:\n" +
                "# frame_number timestamp tx ty tz qx qy qz qw\n" +
                "# tx, ty, tz: position in meters\n" +
                "# qx, qy, qz, qw: rotation quaternion\n";

        // Use FileWriter to write the header
        try (FileWriter writer = new FileWriter(posesFile, false)) {
          writer.write(header);
        }
        poseHeaderWritten = true;
      }

      // Extract translation
      float[] translation = new float[3];
      pose.getTranslation(translation, 0);

      // Extract rotation as quaternion
      float[] rotation = new float[4];
      pose.getRotationQuaternion(rotation, 0);

      // Format the pose data
      String poseData = String.format(Locale.US, "%d %d %.6f %.6f %.6f %.6f %.6f %.6f %.6f\n",
              frameNum, timestamp,
              translation[0], translation[1], translation[2],
              rotation[0], rotation[1], rotation[2], rotation[3]);

      // Append the pose data to the file using FileWriter
      try (FileWriter writer = new FileWriter(posesFile, true)) {
        writer.write(poseData);
      }



    } catch (Exception e) {
      Timber.e(e, "Error saving pose data");
    }
  }

  /**
   * Determines if the given image source requires an ARCore session
   * @param imageSource The image source to check
   * @return true if ARCore session is needed, false otherwise
   */
  private boolean needsArCoreSession(ImageSource imageSource) {
    switch (imageSource) {
      case ARCORE:
        // ARCore source needs ARCore for both camera and pose data
        return true;
      case CAMERA:
        // Camera source uses CameraX only, doesn't need ARCore
        return false;
      case EXTERNAL_CAMERA:
        // External camera needs ARCore for pose data synchronization
        return true;
      default:
        // Default to needing ARCore for safety
        return true;
    }
  }

  /**
   * Initialize all image source handlers
   */
  private void initializeImageSourceHandlers() {
    // Create bitmap renderer for external camera sources only
    bitmapRenderer = new BitmapRenderer(binding.cameraSurfaceView);

    // Initialize ARCore image source handler
    arCoreImageSourceHandler = new ArCoreImageSourceHandler(arCore);
    arCoreImageSourceHandler.setImageSourceListener(this);
    arCoreImageSourceHandler.setBitmapRenderer(bitmapRenderer); // ARCore won't use it
    arCoreImageSourceHandler.initialize();

    // Initialize Camera image source handler with PreviewView for hardware acceleration
    cameraImageSourceHandler = new CameraImageSourceHandler(requireContext(), this);
    cameraImageSourceHandler.setImageSourceListener(this);
    cameraImageSourceHandler.setPreviewView(binding.cameraPreviewView); // Use PreviewView instead of BitmapRenderer
    cameraImageSourceHandler.initialize();

    // Initialize External Camera image source handler
    externalCameraImageSourceHandler = new ExternalCameraImageSourceHandler(arCore);
    externalCameraImageSourceHandler.setImageSourceListener(this);
    externalCameraImageSourceHandler.setBitmapRenderer(bitmapRenderer);
    // Set a default stream URL - this could be made configurable via settings
    externalCameraImageSourceHandler.setStreamUrl("http://192.168.0.10:81/stream");
    externalCameraImageSourceHandler.initialize();

    // Set the initial image source handler - but don't start capture yet
    // Capture will be started in onResume() when the fragment is fully ready
    setInitialImageSource(currentImageSource);
  }

  /**
   * Set the initial image source without starting capture
   * This is used during initialization to set up the UI
   * @param imageSource The image source to set
   */
  private void setInitialImageSource(ImageSource imageSource) {
    // Handle rendering mode based on image source
    switch (imageSource) {
      case ARCORE:
        // Show GLSurfaceView and hide camera surface views
        binding.surfaceView.setVisibility(View.VISIBLE);
        binding.cameraPreviewView.setVisibility(View.GONE);
        binding.cameraSurfaceView.setVisibility(View.GONE);
        currentImageSourceHandler = arCoreImageSourceHandler;
        break;
      case CAMERA:
        // Hide GLSurfaceView and show camera preview view
        binding.surfaceView.setVisibility(View.GONE);
        binding.cameraPreviewView.setVisibility(View.VISIBLE);
        binding.cameraSurfaceView.setVisibility(View.GONE);
        currentImageSourceHandler = cameraImageSourceHandler;
        break;
      case EXTERNAL_CAMERA:
        // Hide GLSurfaceView and show camera surface view for bitmap rendering
        binding.surfaceView.setVisibility(View.GONE);
        binding.cameraPreviewView.setVisibility(View.GONE);
        binding.cameraSurfaceView.setVisibility(View.VISIBLE);
        currentImageSourceHandler = externalCameraImageSourceHandler;
        break;
      default:
        // Default to ARCore
        binding.surfaceView.setVisibility(View.VISIBLE);
        binding.cameraPreviewView.setVisibility(View.GONE);
        binding.cameraSurfaceView.setVisibility(View.GONE);
        currentImageSourceHandler = arCoreImageSourceHandler;
        break;
    }

    currentImageSource = imageSource;
  }

  /**
   * Switch to a different image source
   *
   * @param imageSource The image source to switch to
   */
  private void switchImageSource(ImageSource imageSource) {
    // Stop current image source handler if any
    if (currentImageSourceHandler != null) {
      currentImageSourceHandler.stopCapture();
    }

    // Determine if we need to start/stop ARCore session based on the new image source
    boolean currentNeedsArCore = needsArCoreSession(currentImageSource);
    boolean newNeedsArCore = needsArCoreSession(imageSource);

    // Handle ARCore session lifecycle
    if (currentNeedsArCore && !newNeedsArCore) {
      // Switching from ARCore-dependent to ARCore-independent source - pause ARCore
      arCore.pause();
    } else if (!currentNeedsArCore && newNeedsArCore) {
      // Switching from ARCore-independent to ARCore-dependent source - resume ARCore
      resume();
    }

    // Handle rendering mode based on image source
    switch (imageSource) {
      case ARCORE:
        // Show GLSurfaceView and hide camera surface views
        binding.surfaceView.setVisibility(View.VISIBLE);
        binding.cameraPreviewView.setVisibility(View.GONE);
        binding.cameraSurfaceView.setVisibility(View.GONE);
        currentImageSourceHandler = arCoreImageSourceHandler;
        break;
      case CAMERA:
        // Check camera permission for camera sources
        if (!PermissionUtils.hasCameraPermission(requireActivity())) {
          // Switch back to ARCore
          binding.imageSourceSpinner.setSelection(ImageSource.ARCORE.getValue());
          return;
        }

        // Hide GLSurfaceView and show camera preview view
        binding.surfaceView.setVisibility(View.GONE);
        binding.cameraPreviewView.setVisibility(View.VISIBLE);
        binding.cameraSurfaceView.setVisibility(View.GONE);
        currentImageSourceHandler = cameraImageSourceHandler;
        break;
      case EXTERNAL_CAMERA:
        // Hide GLSurfaceView and show camera surface view for bitmap rendering
        binding.surfaceView.setVisibility(View.GONE);
        binding.cameraPreviewView.setVisibility(View.GONE);
        binding.cameraSurfaceView.setVisibility(View.VISIBLE);
        // Clear the surface for bitmap rendering
        if (bitmapRenderer != null) {
          bitmapRenderer.clearSurface();
        }
        currentImageSourceHandler = externalCameraImageSourceHandler;
        break;
      default:
        // Default to ARCore
        binding.surfaceView.setVisibility(View.VISIBLE);
        binding.cameraPreviewView.setVisibility(View.GONE);
        binding.cameraSurfaceView.setVisibility(View.GONE);
        currentImageSourceHandler = arCoreImageSourceHandler;
        break;
    }

    // Start the new image source handler with appropriate timing
    if (imageSource == ImageSource.ARCORE && !currentNeedsArCore && newNeedsArCore) {
      // When switching to ARCore from non-ARCore source, add delay to ensure ARCore session is ready
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
        if (currentImageSource == ImageSource.ARCORE) {
          currentImageSourceHandler.startCapture();
        }
      }, 300);
    } else if (imageSource == ImageSource.CAMERA && currentNeedsArCore && !newNeedsArCore) {
      // When switching to Camera from ARCore source, add delay to ensure ARCore is fully stopped
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
        if (currentImageSource == ImageSource.CAMERA) {
          currentImageSourceHandler.startCapture();
        }
      }, 300);
    } else {
      // For other transitions, start immediately
      currentImageSourceHandler.startCapture();
    }

    currentImageSource = imageSource;
  }

  // ImageSourceHandler.ImageSourceListener implementation
  @Override
  public void onFrameAvailable(Bitmap bitmap, Pose pose, CameraIntrinsics cameraIntrinsics, long timestamp) {
    if (logFolder == null || !loggingEnabled) {
      return;
    }

    executorService.submit(() -> {
      try {
        // Get the selected resolution from the spinner
        com.satinavrobotics.satibot.env.Size targetSize = getSelectedResolution();

        // Process the bitmap
        Bitmap processedBitmap = bitmap;
        if (bitmap != null) {
          // Resize bitmap if needed
          if (bitmap.getWidth() != targetSize.width || bitmap.getHeight() != targetSize.height) {
            processedBitmap = Bitmap.createScaledBitmap(bitmap, targetSize.width, targetSize.height, true);
          }
          processFrame(processedBitmap, targetSize.width, targetSize.height);
        }

        // Log pose data if available
        if (pose != null) {
          savePoseData(pose, timestamp);
        }

      } catch (Exception e) {
        Timber.e(e, "Error processing frame from image source");
      }
    });
  }

  @Override
  public void onError(String error) {
    Timber.e("Image source error: %s", error);
    requireActivity().runOnUiThread(() -> {
      // You could show a toast or update UI to indicate the error
    });
  }
}
