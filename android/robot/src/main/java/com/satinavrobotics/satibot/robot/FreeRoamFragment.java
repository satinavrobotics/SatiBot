package com.satinavrobotics.satibot.robot;

import static com.satibot.utils.Enums.SpeedMode;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
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
import com.github.anastr.speedviewlib.components.Section;
import com.google.android.material.internal.ViewUtils;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotTrackingException;
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
import org.json.JSONException;
import org.json.JSONObject;
import org.openbot.R;
import org.openbot.databinding.FragmentFreeRoamBinding;

import com.satinavrobotics.satibot.common.ControlsFragment;
import com.satinavrobotics.satibot.env.ImageUtils;
import com.satinavrobotics.satibot.env.PhoneController;
import com.satinavrobotics.satibot.env.StatusManager;
import com.satinavrobotics.satibot.googleServices.GoogleServices;
import com.satinavrobotics.satibot.logging.LocationService;
import com.satinavrobotics.satibot.pointGoalNavigation.ArCoreListener;
import com.satinavrobotics.satibot.pointGoalNavigation.CameraIntrinsics;
import com.satinavrobotics.satibot.pointGoalNavigation.ImageFrame;
import com.satinavrobotics.satibot.pointGoalNavigation.InfoDialogFragment;
import com.satinavrobotics.satibot.pointGoalNavigation.NavigationPoses;
import com.satinavrobotics.satibot.pointGoalNavigation.PermissionDialogFragment;
import com.satinavrobotics.satibot.pointGoalNavigation.SetGoalDialogFragment;
import com.satinavrobotics.satibot.projects.GoogleSignInCallback;
import com.satinavrobotics.satibot.tflite.Model;
import com.satinavrobotics.satibot.utils.ConnectionUtils;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.Enums;
import com.satinavrobotics.satibot.utils.PermissionUtils;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;

import io.livekit.android.room.track.video.CameraCapturerUtils;
import livekit.org.webrtc.VideoFrame;
import timber.log.Timber;

public class FreeRoamFragment extends ControlsFragment implements ArCoreListener {

  private FragmentFreeRoamBinding binding;
  private PhoneController phoneController;
  private ArCoreHandler arCore;
  private boolean isPermissionRequested = false;

  private ArCameraProvider cameraProvider;

  private ArCameraSession arCameraSession;
  private ExternalCameraSession externalCameraSession;

  private StatusManager statusManager;

  // LOGGING
  private Handler loggingHandler;
  private HandlerThread loggingHandlerThread;
  private GoogleServices googleServices;
  protected String logFolder;

  protected boolean loggingEnabled;
  private boolean loggingCanceled;
  private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

  private Matrix frameToCropTransform;
  private Bitmap croppedBitmap;
  private int sensorOrientation;
  private RectF cropRect;
  private boolean maintainAspectRatio;
  private String saveAs;


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

    arCameraSession = ArCameraSession.getInstance();
    //externalCameraSession = ExternalCameraSession.getInstance();
    cameraProvider = ArCameraProvider.getInstance();

    Intent serviceIntent = new Intent(requireActivity(), LocationService.class);
      requireContext().startService(serviceIntent);

      phoneController = PhoneController.getInstance(requireContext());

    Handler handlerMain = new Handler(Looper.getMainLooper());
    arCore = new ArCoreHandler(requireContext(), binding.surfaceView, handlerMain);
    arCameraSession.setArCoreHandler(arCore);

    //externalCameraSession.startSession();

    statusManager = StatusManager.getInstance();

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


    binding.voltageInfo.setText(getString(R.string.voltageInfo, "--.-"));
    binding.controllerContainer.speedInfo.setText(getString(R.string.speedInfo, "---,---"));
    binding.sonarInfo.setText(getString(R.string.distanceInfo, "---"));
    if (vehicle.getConnectionType().equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
      binding.bleToggle.setVisibility(View.GONE);
    } else if (vehicle.getConnectionType().equals("Bluetooth")) {
      binding.bleToggle.setVisibility(View.VISIBLE);
      binding.usbToggle.setVisibility(View.GONE);
    }

    setSpeedMode(SpeedMode.getByID(preferencesManager.getSpeedMode()));
    setControlMode();

    binding.controllerContainer.speedMode.setOnClickListener(
        v ->
            setSpeedMode(
                Enums.toggleSpeed(
                    Enums.Direction.CYCLIC.getValue(),
                    SpeedMode.getByID(preferencesManager.getSpeedMode()))));

    binding.speed.getSections().clear();
    binding.speed.addSections(
        new Section(
            0f,
            0.7f,
            getResources().getColor(R.color.green),
            ViewUtils.dpToPx(requireContext(), 24)),
        new Section(
            0.7f,
            0.8f,
            getResources().getColor(R.color.yellow),
            ViewUtils.dpToPx(requireContext(), 24)),
        new Section(
            0.8f,
            1.0f,
            getResources().getColor(R.color.red),
            ViewUtils.dpToPx(requireContext(), 24)));

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
    binding.bleToggle.setOnClickListener(
        v -> {
          binding.bleToggle.setChecked(vehicle.bleConnected());
          Navigation.findNavController(requireView()).navigate(R.id.open_bluetooth_fragment);
        });

    showStartDialog();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    // Prevent memory leaks by clearing references to Views
    binding = null;
  }


  @Override
  protected void processUSBData(String data) {

    binding.controllerContainer.speedInfo.setText(
        getString(
            R.string.speedInfo,
            String.format(
                Locale.US, "%3.0f,%3.0f", vehicle.getLeftWheelRpm(), vehicle.getRightWheelRpm())));

    binding.voltageInfo.setText(
        getString(
            R.string.voltageInfo, String.format(Locale.US, "%2.1f", vehicle.getBatteryVoltage())));
    binding.battery.setProgress(vehicle.getBatteryPercentage());
    if (vehicle.getBatteryPercentage() < 15) {
      binding.battery.setProgressTintList(
          ColorStateList.valueOf(getResources().getColor(R.color.red)));
      binding.battery.setProgressBackgroundTintList(
          ColorStateList.valueOf(getResources().getColor(R.color.red)));
    } else {
      binding.battery.setProgressTintList(
          ColorStateList.valueOf(getResources().getColor(R.color.green)));
      binding.battery.setProgressBackgroundTintList(
          ColorStateList.valueOf(getResources().getColor(R.color.green)));
    }

    binding.sonar.setProgress((int) (vehicle.getSonarReading() / 3));
    if (vehicle.getSonarReading() / 3 < 15) {
      binding.sonar.setProgressTintList(
          ColorStateList.valueOf(getResources().getColor(R.color.red)));
    } else if (vehicle.getSonarReading() / 3 < 45) {
      binding.sonar.setProgressTintList(
          ColorStateList.valueOf(getResources().getColor(R.color.yellow)));
    } else {
      binding.sonar.setProgressTintList(
          ColorStateList.valueOf(getResources().getColor(R.color.green)));
    }

    binding.sonarInfo.setText(
        getString(
            R.string.distanceInfo, String.format(Locale.US, "%3.0f", vehicle.getSonarReading())));
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
    // Get linear and angular velocity
    float linear = vehicle.getLinearVelocity();
    float angular = vehicle.getAngularVelocity();

    // Also get left and right wheel speeds for backward compatibility
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();

    // Display linear and angular velocity
    binding.controllerContainer.controlInfo.setText(
        String.format(Locale.US, "L:%.0f,A:%.0f", linear, angular));

    binding.speed.speedPercentTo(vehicle.getSpeedPercent());
    binding.steering.setRotation(vehicle.getRotation());
    binding.driveGear.setText(vehicle.getDriveGear());
  }

  private void setSpeedMode(SpeedMode speedMode) {
    if (speedMode != null) {
      switch (speedMode) {
        case SLOW:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_low);
          break;
        case NORMAL:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_medium);
          break;
        case FAST:
          binding.controllerContainer.speedMode.setImageResource(R.drawable.ic_speed_high);
          break;
      }

      Timber.d("Updating  controlSpeed: %s", speedMode);
      preferencesManager.setSpeedMode(speedMode.getValue());
      vehicle.setSpeedMultiplier(speedMode.getValue());
    }
  }

  private void setControlMode() {
      if (!PermissionUtils.hasControllerPermissions(requireActivity()))
        requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
      else {
        connectLiveKitController();
      }
  }

  private void connectLiveKitController() {
    phoneController.connectLiveKitServer();
    updateDriveMode(Enums.DriveMode.GAME);
  }

  private void updateDriveMode(Enums.DriveMode driveMode) {
    Enums.DriveMode oldDriveMode = currentDriveMode;
    // Currently only dual drive mode supported
    //setDriveMode(driveMode);
    //binding.controllerContainer.driveMode.setAlpha(0.5f);
    //binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(oldDriveMode.getValue());
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        try {
          // NOTE: added runOnUiThread, because if throws an exception, if controlled by web-server
          requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
              handleDriveCommand();
            }
          });
        } catch (IllegalStateException e) {
          e.printStackTrace();
        }

        break;

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

      case Constants.CMD_INDICATOR_LEFT:
        toggleIndicator(Enums.VehicleIndicator.LEFT.getValue());
        break;

      case Constants.CMD_INDICATOR_RIGHT:
        toggleIndicator(Enums.VehicleIndicator.RIGHT.getValue());
        break;

      case Constants.CMD_INDICATOR_STOP:
        toggleIndicator(Enums.VehicleIndicator.STOP.getValue());
        break;

      case Constants.CMD_DRIVE_MODE:
        //setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode()));
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
        arCore.detachAnchors();
        arCore.setStartAnchorAtCurrentPose();
        Pose startPose = arCore.getStartPose();
        JSONObject nextWaypoint = getNextWaypointInLocalCoordinates();
        try {
          if (nextWaypoint != null) {
            Timber.d("setting goal at (" + nextWaypoint.getString("x") + ", " + nextWaypoint.getString("z") + ")");
            float goalX = -Float.parseFloat(nextWaypoint.getString("x"));
            float goalZ = -Float.parseFloat(nextWaypoint.getString("z"));
            arCore.setTargetAnchor(startPose.compose(Pose.makeTranslation(goalX, 0.0f, goalZ)));
          }
        } catch (JSONException e) {
          e.printStackTrace();
          arCore.setTargetAnchor(null);
        }


    }
  }

  @Override
  public void onArCoreUpdate(NavigationPoses navigationPoses, ImageFrame frame, CameraIntrinsics cameraIntrinsics, long timestamp) {
    //if (!arCameraSession.isReady()) return;
    //Timber.d("ARCORE UPDATE");
    //VideoFrame videoFrame = new VideoFrame(i420Buffer, 0, timestamp);
    //arCameraSession.onFrameCapturedInCurrentSession(videoFrame);
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

  private void resume() {
    if (arCore == null)
      return;
    Timber.d("RESUME:  Checking for camera permissions...");
    if (!PermissionUtils.hasCameraPermission(requireActivity())) {
      getCameraPermission();
    } else if (PermissionUtils.shouldShowRational(requireActivity(), Constants.PERMISSION_CAMERA)) {
      PermissionUtils.showCameraPermissionsPreviewToast(requireActivity());
    } else {
      Timber.d("RESUME: START");
      setupArCore();
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    Timber.d("ON START");
    if (arCore != null)
      arCore.setArCoreListener(this);
  }

  @Override
  public void onResume() {
    loggingHandlerThread = new HandlerThread("logging");
    loggingHandlerThread.start();
    loggingHandler = new Handler(loggingHandlerThread.getLooper());
    super.onResume();
    binding.bleToggle.setChecked(vehicle.bleConnected());
    resume();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (arCore != null) {
      arCore.closeSession();
      CameraCapturerUtils.INSTANCE.unregisterCameraProvider(cameraProvider);
      cameraProvider = null;
      arCameraSession = null;
    }
  }

  @Override
  public void onPause() {
    loggingHandlerThread.quitSafely();
    try {
      loggingHandlerThread.join();
      loggingHandlerThread = null;
      loggingHandler = null;
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
    super.onPause();
    if (arCore != null)
      arCore.pause();
  }

  @Override
  public void onStop() {
    super.onStop();
    if (arCore != null)
      arCore.removeArCoreListener();
    phoneController.disconnectLiveKitServer();
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


  private void startDriving(float goalX, float goalZ) {
    Timber.i("setting goal at (" + goalX + ", " + goalZ + ")");

    try {
      arCore.detachAnchors();
      arCore.setStartAnchorAtCurrentPose();

      Pose startPose = arCore.getStartPose();
      if (startPose == null) {
        showInfoDialog(getString(R.string.no_initial_ar_core_pose));
        return;
      }
      arCore.setTargetAnchor(startPose.compose(Pose.makeTranslation(goalX, 0.0f, goalZ)));
    } catch (NotTrackingException e) {
      e.printStackTrace();
      showInfoDialog(getString(R.string.tracking_lost));
      return;
    }

    //isRunning = true;
  }

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

                      if (restart) {
                        showStartDialog();
                      } else {
                        requireActivity().onBackPressed();
                      }
                    });
  }

  private void showStartDialog() {
    if (getChildFragmentManager().findFragmentByTag(SetGoalDialogFragment.TAG) == null) {
      SetGoalDialogFragment dialog = SetGoalDialogFragment.newInstance();
      dialog.setCancelable(false);
      dialog.show(getChildFragmentManager(), SetGoalDialogFragment.TAG);
    }

    getChildFragmentManager()
            .setFragmentResultListener(
                    SetGoalDialogFragment.TAG,
                    getViewLifecycleOwner(),
                    (requestKey, result) -> {
                      Boolean start = result.getBoolean("start");

                      if (start) {
                        Float forward = result.getFloat("forward");
                        Float left = result.getFloat("left");

                        // x: right, z: backwards
                        startDriving(-left, -forward);
                      } else {
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
    statusManager.updateStatus(ConnectionUtils.createStatus("LOGS", loggingEnabled));

    binding.recordingIndicator.setVisibility(loggingEnabled ? View.VISIBLE : View.INVISIBLE);
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


  @Override
  protected void setModel(Model selected) {
    frameToCropTransform = null;
    //binding.cropInfo.setText(
    //        String.format(
    //                Locale.US,
    //                "%d x %d",
    //                selected.getInputSize().getWidth(),
    //                selected.getInputSize().getHeight()));

    croppedBitmap =
            Bitmap.createBitmap(
                    selected.getInputSize().getWidth(),
                    selected.getInputSize().getHeight(),
                    Bitmap.Config.ARGB_8888);

    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());
    if (selected.type == Model.TYPE.CMDNAV) {
      cropRect = new RectF(0.0f, 240.0f / 720.0f, 0.0f, 0.0f);
      maintainAspectRatio = true;
    } else {
      cropRect = new RectF(0.0f, 0.0f, 0.0f, 0.0f);
      maintainAspectRatio = false;
    }
  }




}
