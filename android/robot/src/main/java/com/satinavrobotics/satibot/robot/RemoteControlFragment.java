package com.satinavrobotics.satibot.robot;

import static com.satinavrobotics.satibot.utils.Enums.SpeedMode;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.arcore.ArCoreHandler;
import com.satinavrobotics.satibot.databinding.FragmentFreeRoamBinding;

import com.satinavrobotics.satibot.env.ImageUtils;
import com.satinavrobotics.satibot.env.StatusManager;
import com.satinavrobotics.satibot.googleServices.GoogleServices;
import com.satinavrobotics.satibot.livekit.LiveKitServer;
import com.satinavrobotics.satibot.livekit.stream.ArCameraProvider;
import com.satinavrobotics.satibot.livekit.stream.ArCameraSession;
import com.satinavrobotics.satibot.livekit.stream.ExternalCameraSession;
import com.satinavrobotics.satibot.logging.LocationService;
import com.satinavrobotics.satibot.arcore.ArCoreListener;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;
import com.satinavrobotics.satibot.arcore.ImageFrame;
import com.satinavrobotics.satibot.arcore.InfoDialogFragment;
import com.satinavrobotics.satibot.arcore.PermissionDialogFragment;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.PermissionUtils;

import io.livekit.android.renderer.SurfaceViewRenderer;
import io.livekit.android.room.Room;
import com.satinavrobotics.satibot.googleServices.GoogleSignInCallback;
import com.satinavrobotics.satibot.utils.ConnectionUtils;
import com.satinavrobotics.satibot.utils.Enums;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;

import io.livekit.android.room.track.video.CameraCapturerUtils;
import livekit.org.webrtc.VideoFrame;
import timber.log.Timber;

public class RemoteControlFragment extends ControlsFragment implements ArCoreListener {

  private FragmentFreeRoamBinding binding;
  private ArCoreHandler arCore;
  private boolean isPermissionRequested = false;

  private ArCameraProvider cameraProvider;

  private ArCameraSession arCameraSession;
  private ExternalCameraSession externalCameraSession;

  // LOGGING
  private Handler loggingHandler;
  private HandlerThread loggingHandlerThread;
  private GoogleServices googleServices;
  protected String logFolder;

  protected boolean loggingEnabled;
  private boolean loggingCanceled;
  private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

  // LiveKit functionality
  private LiveKitServer liveKitServer;
  private SurfaceViewRenderer videoRenderer;
  private ActivityResultLauncher<String[]> requestPermissionLauncher;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getArguments() != null) {}
  }

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentFreeRoamBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @SuppressLint("RestrictedApi")
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // Force landscape orientation
    requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    // Initialize ARCore first
    Handler handlerMain = new Handler(Looper.getMainLooper());
    arCore = new ArCoreHandler(requireContext(), binding.surfaceView, handlerMain);

    // Then initialize camera sessions and set the ARCore handler
    arCameraSession = ArCameraSession.getInstance();
    arCameraSession.setArCoreHandler(arCore);

    externalCameraSession = ExternalCameraSession.getInstance();

    // Finally initialize camera provider (this should be last as it may trigger camera creation)
    cameraProvider = ArCameraProvider.getInstance();

    Intent serviceIntent = new Intent(requireActivity(), LocationService.class);
      requireContext().startService(serviceIntent);

    // Set up anchor resolution listener
    arCore.setAnchorResolutionListener(this::updateResolvedCountText);

    // Initialize LiveKit functionality
    liveKitServer = LiveKitServer.getInstance(requireContext());
    requestPermissionLauncher = liveKitServer.createPermissionLauncher(this);

    // Setup video renderer immediately - don't wait for post
    videoRenderer = liveKitServer.setupVideoRenderer(this);
    Timber.d("Video renderer set up: %s", videoRenderer != null ? "success" : "failed");

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
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();

    // Clean up LiveKit video renderer
    if (liveKitServer != null && videoRenderer != null) {
      liveKitServer.cleanupVideoRenderer(this, videoRenderer);
      videoRenderer = null;
    }

    // Reset orientation when the view is destroyed
    if (getActivity() != null) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // Prevent memory leaks by clearing references to Views
    binding = null;
  }


  @Override
  protected void processUSBData(String data) {
    // USB data processing for remote control
  }

  private void toggleIndicator(int value) {
    binding.indicatorRight.clearAnimation();
    binding.indicatorLeft.clearAnimation();
    binding.indicatorRight.setVisibility(View.INVISIBLE);
    binding.indicatorLeft.setVisibility(View.INVISIBLE);

    if (value == Enums.VehicleIndicator.RIGHT.getValue()) {
      binding.indicatorRight.startAnimation(startAnimation);
      binding.indicatorRight.setVisibility(View.VISIBLE);
    } else if (value == Enums.VehicleIndicator.LEFT.getValue()) {
      binding.indicatorLeft.startAnimation(startAnimation);
      binding.indicatorLeft.setVisibility(View.VISIBLE);
    }
  }

  protected void handleDriveCommand() {
    binding.steering.setRotation(vehicle.getRotation());
    binding.driveGear.setText(vehicle.getDriveGear());
  }

  private void setSpeedMode(SpeedMode speedMode) {
    if (speedMode != null) {
      preferencesManager.setSpeedMode(speedMode.getValue());
      vehicle.setSpeedMultiplier(speedMode.getValue());
    }
  }



  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        try {
            requireActivity().runOnUiThread(() -> handleDriveCommand());
        } catch (IllegalStateException e) {
          e.printStackTrace();
        }

        break;

      case Constants.CMD_LOGS:
        try {
          requireActivity().runOnUiThread(() -> toggleLogging());
        } catch (IllegalStateException e) {
          e.printStackTrace();
        }
        break;

      case Constants.CMD_INDICATOR_LEFT:
        toggleIndicator(Enums.VehicleIndicator.LEFT.getValue());
        break;

      case Constants.CMD_INDICATOR_RIGHT:
        toggleIndicator(Enums.VehicleIndicator.RIGHT.getValue());
        break;

      case Constants.CMD_INDICATOR_STOP:
        toggleIndicator(Enums.VehicleIndicator.STOP.getValue());
        break;

      case Constants.CMD_DISCONNECTED:
        handleDriveCommand();
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
        // Waypoint handling removed - only visualizing anchors
        break;


    }
  }

  @Override
  public void onArCoreUpdate(Pose currentPose, ImageFrame frame, CameraIntrinsics cameraIntrinsics, long timestamp) {
    // Process current pose in local coordinates if at least one anchor has been resolved
    if (currentPose != null && arCore.getResolvedAnchorsCount() > 0) {
      // Compute the origin pose from the closest resolved anchor
      Pose originPose = arCore.computeOriginPose();

      if (originPose != null) {
        // Compute local coordinates based on the origin pose
        Pose localPose = arCore.computeLocalPose(currentPose, originPose);

        if (localPose != null) {
          // Create JSON with local pose data
          JSONObject localPoseJson = arCore.createLocalPoseJson(localPose);

          // Update status manager with local pose
          StatusManager.getInstance().updateARCorePose(localPoseJson);
        }
      }
    }

    // Handle logging if enabled
    if (!arCameraSession.isReady()) return;
    if (logFolder == null) return;
    if (!loggingEnabled) return;
    executorService.submit(() -> {
      try {
        Bitmap bitmap = convertRGBFrameToScaledBitmap(frame, 160.f / 480.f);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        bitmap = Bitmap.createBitmap(bitmap, 0, 30, 160, 90);
        processFrame(bitmap, width, height);
      } catch (Exception e) {
        Timber.e(e, "Error processing frame");
      }
    });
  }

  @Override
  public void onRenderedFrame(VideoFrame.I420Buffer frame, long timestamp) {
    if (!arCameraSession.isReady()) return;
    VideoFrame videoFrame = new VideoFrame(frame, 0, timestamp);
    arCameraSession.onFrameCapturedInCurrentSession(videoFrame);
  }

  @Override
  public void onArCoreTrackingFailure(long timestamp, TrackingFailureReason trackingFailureReason) {

  }

  @Override
  public void onArCoreSessionPaused(long timestamp) {

  }

    private void setupArCore() {
    try {
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

    showInfoDialog(
            "ARCore failure. Make sure that your device is compatible and the ARCore SDK is installed.");
  }

  // Map loading and anchor resolution is now handled by ArCoreHandler

  /**
   * Updates the UI with the current anchor resolution status
   * This method is called by ArCoreHandler through the AnchorResolutionListener
   *
   * @param resolvedCount Number of anchors resolved so far
   * @param totalCount Total number of anchors to resolve
   */
  private void updateResolvedCountText(int resolvedCount, int totalCount) {
    if (binding != null) {
      binding.anchorStatus.setText(String.format("Anchors: %d/%d", resolvedCount, totalCount));

      // Make sure the status is visible
      binding.anchorStatus.setVisibility(View.VISIBLE);
    }
  }


  // Local pose computation methods are now handled by ArCoreHandler

  private void resume() {
    if (arCore == null) return;

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
    if (arCore != null) {
      arCore.setArCoreListener(this);
    }

    if (liveKitServer != null && PermissionUtils.hasControllerPermissions(requireActivity())) {
      if (liveKitServer.canStartStreaming()) {
        Timber.d("Room is connected, starting streaming...");
        // Simple approach: always start streaming when we return to the fragment
        binding.getRoot().postDelayed(() -> {
          liveKitServer.startStreaming();
        }, 200);
      } else {
        // Log current state for debugging
        Room.State currentState = liveKitServer.getCurrentRoomState();
        Timber.d("Room not ready for streaming, current state: %s", currentState);
        liveKitServer.connect();
      }
    }
  }

  @Override
  public void onResume() {
    loggingHandlerThread = new HandlerThread("logging");
    loggingHandlerThread.start();
    loggingHandler = new Handler(loggingHandlerThread.getLooper());
    super.onResume();

    // Force landscape orientation
    requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    // External camera session will be started when user switches to external camera via LiveKit

    if (binding != null) {
      binding.bleToggle.setChecked(vehicle.bleConnected());
    }
    resume();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    // Close ARCore session first
    if (arCore != null) {
      try {
        arCore.closeSession(() -> {
          // After ARCore is closed, clean up local references but don't reset singletons
          // unless this is a final cleanup (activity being destroyed)
          cleanupLocalReferences();
        });
      } catch (Exception e) {
        Timber.w(e, "Error closing ARCore session during destroy");
        // Force cleanup even if closeSession fails
        cleanupLocalReferences();
      }
    } else {
      // No ARCore session, just clean up local references
      cleanupLocalReferences();
    }
  }

  /**
   * Cleans up local references without resetting singletons.
   * Singletons are only reset during LiveKit disconnect or app termination.
   */
  private void cleanupLocalReferences() {
    cameraProvider = null;
    arCameraSession = null;
    externalCameraSession = null;
    arCore = null;
  }

  @Override
  public void onPause() {
    stopCameraSessions();

    if (loggingHandlerThread != null) {
      loggingHandlerThread.quitSafely();
      try {
        loggingHandlerThread.join();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      loggingHandlerThread = null;
      loggingHandler = null;
    }

    super.onPause();

    if (arCore != null) {
      try {
        arCore.pause();
      } catch (Exception e) {
        Timber.w(e, "Error pausing ARCore");
      }
    }
  }

  @Override
  public void onStop() {
    super.onStop();

    if (arCore != null) {
      try {
        arCore.removeArCoreListener();
      } catch (Exception e) {
        Timber.w(e, "Error removing ARCore listener");
      }
    }

    if (liveKitServer != null) {
      new Thread(() -> {
        try {
          liveKitServer.stopStreaming();
        } catch (Exception e) {
          Timber.w(e, "Error stopping LiveKit streaming");
        }
      }).start();
    }
  }

  /**
   * Stops camera sessions without resetting singletons
   */
  private void stopCameraSessions() {
    if (externalCameraSession != null) {
      try {
        externalCameraSession.stop();
      } catch (Exception e) {
        Timber.w(e, "Error stopping external camera session");
      }
    }

    if (arCameraSession != null && arCameraSession.isRunning()) {
      try {
        arCameraSession.stop();
      } catch (Exception e) {
        Timber.w(e, "Error stopping ARCore camera session");
      }
    }
  }

  @Override
  public void onDetach() {
    super.onDetach();

    // Reset orientation when the fragment is detached from its activity
    if (getActivity() != null) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
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

                      if (choice.equals("settings")) {
                        PermissionUtils.startInstalledAppDetailsActivity(requireActivity());
                      } else if (choice.equals("retry")) {
                        isPermissionRequested = false;
                        resume();
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
                      setupArCore();
                    } else {
                      showPermissionDialog();
                    }
                  });


  private void showInfoDialog(String message) {
    if (getChildFragmentManager().findFragmentByTag(InfoDialogFragment.TAG) == null) {
      InfoDialogFragment dialog = InfoDialogFragment.newInstance(message);
      dialog.setCancelable(false);
      dialog.show(getChildFragmentManager(), InfoDialogFragment.TAG);
    }

    getChildFragmentManager()
            .setFragmentResultListener(
                    InfoDialogFragment.TAG,
                    getViewLifecycleOwner(),
                    (requestKey, result) -> {
                      Boolean restart = result.getBoolean("restart");

                      if (!restart) {
                        requireActivity().onBackPressed();
                      }
                    });
  }



  private void startLogging() {
    logFolder =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    .getAbsolutePath()
                    + File.separator
                    + getString(R.string.app_name)
                    + File.separator
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
  }

  private void stopLogging(boolean isCancel) {
    Timber.d("Stopping loggings");

    // Pack and upload the collected data
    runInBackground(() -> {
      try {
        File folder = new File(logFolder);
        googleServices.uploadLogData(zip(folder));
        TimeUnit.MILLISECONDS.sleep(500);
        FileUtils.deleteQuietly(folder);
      } catch (InterruptedException e) {
        Timber.e(e, "Got interrupted.");
      }
    });
    loggingEnabled = false;
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

  protected void setLoggingActive(boolean enableLogging) {
    if (enableLogging && !loggingEnabled) {
      if (!PermissionUtils.hasLoggingPermissions(requireActivity())) {
        Timber.d("No permissions");
        requestPermissionLauncherLogging.launch(Constants.PERMISSIONS_LOGGING);
        loggingEnabled = false;
      } else {
        startLogging();
        loggingEnabled = true;
      }
    } else if (!enableLogging && loggingEnabled) {
      stopLogging(loggingCanceled);
      loggingEnabled = false;
    }
    getStatusManager().updateStatus(ConnectionUtils.createStatus("LOGS", loggingEnabled));

    // Check if binding is not null before accessing its properties
    if (binding != null) {
      binding.recordingIndicator.setVisibility(loggingEnabled ? View.VISIBLE : View.INVISIBLE);
    }
  }


  protected synchronized void runInBackground(final Runnable r) {
    if (loggingHandler != null) {
      loggingHandler.post(r);
    }
  }

  private boolean allGranted = true;
  protected final ActivityResultLauncher<String[]> requestPermissionLauncherLogging =
          registerForActivityResult(
                  new ActivityResultContracts.RequestMultiplePermissions(),
                  result -> {
                    result.forEach((permission, granted) -> allGranted = allGranted && granted);
                    if (allGranted) setLoggingActive(true);
                    else {
                      PermissionUtils.showLoggingPermissionsToast(requireActivity());
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

  public static float computeDeltaYaw(Pose pose, Pose goalPose) {
    // compute robot forward axis (global coordinate system)
    float[] forward = new float[] {0.f, 0.f, -1.f};
    float[] forwardRotated = pose.rotateVector(forward);

    // distance vector to goal (global coordinate system)
    float dx = goalPose.tx() - pose.tx();
    float dz = goalPose.tz() - pose.tz();

    double yaw = Math.atan2(forwardRotated[2], forwardRotated[0]) - Math.atan2(dz, dx);

    // fit to range (-pi, pi]
    if (yaw > Math.PI) {
      yaw -= 2 * Math.PI;
    } else if (yaw <= -Math.PI) {
      yaw += 2 * Math.PI;
    }

    return (float) yaw;
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


  protected void processFrame(Bitmap bitmap, int width, int height) {
    ++frameNum;
    if (binding != null) {
      //if (isAdded())
      //  requireActivity()
      //          .runOnUiThread(
      //                  () ->
      //                          binding.frameInfo.setText(
      //                                  String.format(Locale.US, "%d x %d", width, height)));

      //if (!binding.recordingIndicator.isVisible()) return;

      //if (binding.previewCheckBox.isChecked() || binding.trainingDataCheckBox.isChecked()) {
      //  sendFrameNumberToSensorService(frameNum);
      //}

      //if (binding.previewCheckBox.isChecked()) {
      if (bitmap != null)
        ImageUtils.saveBitmap(
                bitmap, logFolder + File.separator + "images", frameNum + "_preview.jpeg");
      //}
      //if (binding.trainingDataCheckBox.isChecked()) {
      //if (frameToCropTransform == null)
      //  frameToCropTransform =
      //          ImageUtils.getTransformationMatrix(
      //                  getMaxAnalyseImageSize().getWidth(),
      //                  getMaxAnalyseImageSize().getHeight(),
      //                  croppedBitmap.getWidth(),
      //                  croppedBitmap.getHeight(),
      //                  sensorOrientation,
      //                  cropRect,
      //                  maintainAspectRatio);

      //  final Canvas canvas = new Canvas(croppedBitmap);
      //  canvas.drawBitmap(bitmap, frameToCropTransform, null);
      //  ImageUtils.saveBitmap(
      //          croppedBitmap, logFolder + File.separator + "images", frameNum + "_crop.jpeg");
      //}
    }
  }

}
