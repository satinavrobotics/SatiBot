package org.openbot.robot;

import static org.openbot.utils.Enums.ControlMode;
import static org.openbot.utils.Enums.DriveMode;
import static org.openbot.utils.Enums.SpeedMode;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import com.github.anastr.speedviewlib.components.Section;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.Earth;
import com.google.ar.core.GeospatialPose;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;
import org.jetbrains.annotations.NotNull;
import org.openbot.R;
import org.openbot.common.ControlsFragment;
import org.openbot.databinding.FragmentFreeRoamBinding;
import org.openbot.env.PhoneController;
import org.openbot.pointGoalNavigation.ArCore;
import org.openbot.pointGoalNavigation.ArCoreListener;
import org.openbot.pointGoalNavigation.CameraIntrinsics;
import org.openbot.pointGoalNavigation.ImageFrame;
import org.openbot.pointGoalNavigation.NavigationPoses;
import org.openbot.utils.Constants;
import org.openbot.utils.Enums;
import org.openbot.utils.PermissionUtils;
import timber.log.Timber;

public class FreeRoamFragment extends ControlsFragment implements ArCoreListener {

  private FragmentFreeRoamBinding binding;
  private PhoneController phoneController;
  private ArCore arCore;

  // Parameter: how frequently (in ms) the location update is sent.
  private long locationUpdateInterval = 500; // 1000 ms = 1 second
  private long lastLocationSentTime = 0;

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentFreeRoamBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  public static int dpToPx(Context context, int dp) {
    return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.getResources().getDisplayMetrics()
    );
  }

  @SuppressLint("RestrictedApi")
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    boolean AR = false;
    if (AR) {
      // Initialize ARCore for Free Roam mode.
      Handler handlerMain = new Handler(Looper.getMainLooper());

      arCore = new ArCore(requireContext(), binding.surfaceView, handlerMain);
      arCore.setArCoreListener(this);
      Timber.d("Starting ARCore with Geospatial support");
    } else {
      arCore = null;
      Timber.d("Not using ARCore...");
    }

    phoneController = PhoneController.getInstance(requireContext(), arCore);

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
    setControlMode(ControlMode.getByID(preferencesManager.getControlMode()));
    setDriveMode(DriveMode.getByID(preferencesManager.getDriveMode()));

    binding.controllerContainer.controlMode.setOnClickListener(
        v -> {
          ControlMode controlMode = ControlMode.getByID(preferencesManager.getControlMode());
          if (controlMode != null) setControlMode(Enums.switchControlMode(controlMode));
        });
    binding.controllerContainer.driveMode.setOnClickListener(
        v -> setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode())));

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
            dpToPx(requireContext(), 24)),
        new Section(
            0.7f,
            0.8f,
            getResources().getColor(R.color.yellow),
            dpToPx(requireContext(), 24)),
        new Section(
            0.8f,
            1.0f,
            getResources().getColor(R.color.red),
            dpToPx(requireContext(), 24)));

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
  
  }

  @Override
  public void onResume() {
    super.onResume();
    if (arCore != null) {
      try {
        arCore.resume();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    binding.bleToggle.setChecked(vehicle.bleConnected());
  }

  @Override
  public void onPause() {
    super.onPause();
    if (arCore != null) {
      arCore.pause();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (arCore != null) {
      arCore.closeSession();
    }
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
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();
    binding.controllerContainer.controlInfo.setText(
        String.format(Locale.US, "%.0f,%.0f", left, right));

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

  private void setControlMode(ControlMode controlMode) {
    if (controlMode != null) {
      switch (controlMode) {
        case GAMEPAD:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_controller);
          disconnectPhoneController();
          break;
        case PHONE:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_phone);
          if (!PermissionUtils.hasControllerPermissions(requireActivity()))
            requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
          else connectPhoneController();
          break;
        case WEBSERVER:
          binding.controllerContainer.controlMode.setImageResource(R.drawable.ic_server);
          if (!PermissionUtils.hasControllerPermissions(requireActivity()))
            requestPermissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
          else {
            connectWebController();
          }
          break;
      }
      Timber.d("Updating  controlMode: %s", controlMode);
      preferencesManager.setControlMode(controlMode.getValue());
    }
  }

  protected void setDriveMode(DriveMode driveMode) {
    if (driveMode != null) {
      switch (driveMode) {
        case DUAL:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_dual);
          break;
        case GAME:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_game);
          break;
        case JOYSTICK:
          binding.controllerContainer.driveMode.setImageResource(R.drawable.ic_joystick);
          break;
      }

      Timber.d("Updating  driveMode: %s", driveMode);
      vehicle.setDriveMode(driveMode);
      preferencesManager.setDriveMode(driveMode.getValue());
    }
  }

  private void connectPhoneController() {
    phoneController.connect(requireContext());
    DriveMode oldDriveMode = currentDriveMode;
    // Currently only dual drive mode supported
    setDriveMode(DriveMode.DUAL);
    binding.controllerContainer.driveMode.setAlpha(0.5f);
    binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(oldDriveMode.getValue());
  }

  private void connectWebController() {
    phoneController.connectWebServer();
    Enums.DriveMode oldDriveMode = currentDriveMode;
    // Currently only dual drive mode supported
    setDriveMode(Enums.DriveMode.GAME);
    binding.controllerContainer.driveMode.setAlpha(0.5f);
    binding.controllerContainer.driveMode.setEnabled(false);
    preferencesManager.setDriveMode(oldDriveMode.getValue());
  }

  private void disconnectPhoneController() {
    phoneController.disconnect();
    setDriveMode(DriveMode.getByID(preferencesManager.getDriveMode()));
    binding.controllerContainer.driveMode.setEnabled(true);
    binding.controllerContainer.driveMode.setAlpha(1.0f);
  }

  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        handleDriveCommand();
        break;

      case Constants.WAYPT_DRIVE:
        updateWaypoints();
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
        setDriveMode(Enums.switchDriveMode(vehicle.getDriveMode()));
        break;

      case Constants.CMD_DISCONNECTED:
        handleDriveCommand();
        setControlMode(ControlMode.GAMEPAD);
        break;

      case Constants.CMD_SPEED_DOWN:
        setSpeedMode(
            Enums.toggleSpeed(
                Enums.Direction.DOWN.getValue(),
                Enums.SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;

      case Constants.CMD_SPEED_UP:
        setSpeedMode(
            Enums.toggleSpeed(
                Enums.Direction.UP.getValue(),
                Enums.SpeedMode.getByID(preferencesManager.getSpeedMode())));
        break;
    }
  }

  public void updateWaypoints() {
    arCore.detachAnchors();
    double lat = vehicle.waypoints.lat;
    double lon = vehicle.waypoints.lon;
    Timber.d("Updating waypoints: %f, %f", lat, lon);
    Pose startPose = arCore.getStartPose();
    if (startPose == null) {
//        showInfoDialog(getString(R.string.no_initial_ar_core_pose));
      return;
    }
    arCore.setStartAnchorAtCurrentPose();
    arCore.setTargetAnchor(startPose.compose(Pose.makeTranslation(1, 0.0f, 1)));
  }

  /**
   * ARCoreListener callback. Using the Geospatial API when available (VPS/GPS/Street level)
   * and falling back to the standard ARCore pose otherwise.
   * Only send a location update as frequently as specified by locationUpdateInterval.
   */
  @Override
  public void onArCoreUpdate(NavigationPoses navigationPoses, ImageFrame rgb, CameraIntrinsics cameraIntrinsics, long timestamp) {
    long currentTime = SystemClock.elapsedRealtime();
    if (currentTime - lastLocationSentTime < locationUpdateInterval) {
      return;  // Skip update if the interval hasn't elapsed.
    }
    lastLocationSentTime = currentTime;

    JSONObject json = new JSONObject();
    try {
      json.put("timestamp", timestamp);
      if (arCore.isGeospatialModeAvailable()) {
        // If geospatial mode is available, use it.
        GeospatialPose geoPose = arCore.getGeospatialPose();
        json.put("type", "geospatialPoseUpdate");
        json.put("latitude", geoPose.getLatitude());
        json.put("longitude", geoPose.getLongitude());
        json.put("altitude", geoPose.getAltitude());
        json.put("heading", geoPose.getHeading());
        // Optionally, add accuracy parameters if needed:
        json.put("horizontalAccuracy", geoPose.getHorizontalAccuracy());
        json.put("verticalAccuracy", geoPose.getVerticalAccuracy());
      } else {
        // Fallback to using the basic ARCore pose.
        Pose currentPose = navigationPoses.getCurrentPose();
        if (currentPose != null) {
          json.put("type", "arPoseUpdate");
          JSONObject translation = new JSONObject();
          translation.put("x", currentPose.tx());
          translation.put("y", currentPose.ty());
          translation.put("z", currentPose.tz());
          
          JSONObject rotation = new JSONObject();
          rotation.put("x", currentPose.qx());
          rotation.put("y", currentPose.qy());
          rotation.put("z", currentPose.qz());
          rotation.put("w", currentPose.qw());
          
          json.put("translation", translation);
          json.put("rotation", rotation);
        }
      }
    } catch (JSONException e) {
      e.printStackTrace();
    }
    phoneController.send(json);
  }

  @Override
  public void onArCoreTrackingFailure(long timestamp, TrackingFailureReason trackingFailureReason) {
    // Optionally handle any tracking failures.
  }

  @Override
  public void onArCoreSessionPaused(long timestamp) {
    // Optionally handle session pause events.
  }
}
