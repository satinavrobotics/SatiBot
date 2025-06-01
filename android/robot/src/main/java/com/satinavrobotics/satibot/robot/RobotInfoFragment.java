package com.satinavrobotics.satibot.robot;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.databinding.FragmentRobotInfoBinding;

import com.satinavrobotics.satibot.utils.FormatUtils;

import timber.log.Timber;

public class RobotInfoFragment extends ControlsFragment {
  private FragmentRobotInfoBinding binding;

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentRobotInfoBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @SuppressLint("RestrictedApi")
  @Override
  public void onViewCreated(@NotNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // Allow natural orientation changes - RobotInfoFragment supports both portrait and landscape
    // Unlike other fragments that force landscape, this fragment has layouts for both orientations
    // and should adapt to the device's natural orientation

    if (vehicle.getConnectionType().equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
    }

    mViewModel
        .getUsbStatus()
        .observe(getViewLifecycleOwner(), status -> binding.usbToggle.setChecked(status));

    binding.usbToggle.setChecked(vehicle.isUsbConnected());

    binding.usbToggle.setOnClickListener(
        v -> {
          binding.usbToggle.setChecked(vehicle.isUsbConnected());
          Navigation.findNavController(requireView()).navigate(R.id.open_usb_fragment);
        });



    binding.usbToggle.setOnCheckedChangeListener((buttonView, isChecked) -> refreshGui());
    binding.refreshToggle.setOnClickListener(v -> refreshGui());

    binding.lightsSlider.addOnChangeListener(
        (slider, value, fromUser) -> {
          vehicle.sendLightIntensity(value / 100, value / 100);
        });

    // Initialize speed multiplier slider
    int currentSpeedMultiplier = preferencesManager.getSpeedMultiplier();
    // Ensure vehicle speed multiplier matches the saved preference
    vehicle.setSpeedMultiplier(currentSpeedMultiplier);

    if (binding.speedMultiplierSlider != null) {
      binding.speedMultiplierSlider.setValue(currentSpeedMultiplier);
      updateSpeedMultiplierLabel(currentSpeedMultiplier);

      binding.speedMultiplierSlider.addOnChangeListener(
          (slider, value, fromUser) -> {
            if (fromUser) {
              int speedMultiplier = (int) value;
              preferencesManager.setSpeedMultiplier(speedMultiplier);
              vehicle.setSpeedMultiplier(speedMultiplier);
              updateSpeedMultiplierLabel(speedMultiplier);
              Timber.d("Linear speed multiplier set to: %d", speedMultiplier);
            }
          });
    } else {
      Timber.w("Speed multiplier slider not found in layout");
    }

    // Initialize angular multiplier slider
    int currentAngularMultiplier = preferencesManager.getAngularMultiplier();
    // Ensure vehicle angular multiplier matches the saved preference
    vehicle.setAngularMultiplier(currentAngularMultiplier);

    if (binding.angularMultiplierSlider != null) {
      binding.angularMultiplierSlider.setValue(currentAngularMultiplier);
      updateAngularMultiplierLabel(currentAngularMultiplier);

      binding.angularMultiplierSlider.addOnChangeListener(
          (slider, value, fromUser) -> {
            if (fromUser) {
              int angularMultiplier = (int) value;
              preferencesManager.setAngularMultiplier(angularMultiplier);
              vehicle.setAngularMultiplier(angularMultiplier);
              updateAngularMultiplierLabel(angularMultiplier);
              Timber.d("Angular speed multiplier set to: %d", angularMultiplier);
            }
          });
    } else {
      Timber.w("Angular multiplier slider not found in layout");
    }

    binding.motorsForwardButton.setOnClickListener(v -> vehicle.setControlVelocity(0.1f, 0.0f));

    binding.motorsBackwardButton.setOnClickListener(v -> vehicle.setControlVelocity(-0.1f, 0.0f));

    binding.motorsStopButton.setOnClickListener(v -> vehicle.setControlVelocity(0.0f, 0.0f));

    refreshGui();
  }

  private void refreshGui() {
    updateGui(false);
    binding.refreshToggle.setChecked(false);

    // Update multiplier labels with current values
    updateSpeedMultiplierLabel(vehicle.getSpeedMultiplier());
    updateAngularMultiplierLabel(vehicle.getAngularMultiplier());

    if (vehicle.isReady()) {
      vehicle.requestVehicleConfig();
    }
  }

  private void updateGui(boolean isConnected) {
    if (isConnected) {
      binding.robotTypeInfo.setText(vehicle.getVehicleType());
      switch (vehicle.getVehicleType()) {
        case "DIY":
        case "PCB_V1":
        case "PCB_V2":
          binding.robotIcon.setImageResource(R.drawable.diy);
          break;
        case "RTR_TT":
        case "RTR_TT2":
          binding.robotIcon.setImageResource(R.drawable.rtr_tt);
          break;
        case "RTR_520":
          binding.robotIcon.setImageResource(R.drawable.rtr_520);
          break;
        case "RC_CAR":
          binding.robotIcon.setImageResource(R.drawable.rc_car);
          break;
        case "MTV":
          binding.robotIcon.setImageResource(R.drawable.mtv);
          break;
        default:
          binding.robotIcon.setImageResource(R.drawable.ic_openbot);
          break;
      }
    } else {
      binding.robotTypeInfo.setText(getString(R.string.n_a));
      binding.robotIcon.setImageResource(R.drawable.ic_openbot);
      binding.batteryInfo.setText(R.string.battery_percentage);
      binding.batteryProgressBar.setProgress(0);
      binding.wheelEncoderValue.setText(getString(R.string.angular_velocity_format, "*.** "));
      binding.imuValue.setText(getString(R.string.angular_velocity_format, "*.** "));
      binding.fusedValue.setText(getString(R.string.angular_velocity_format, "*.** "));
      binding.pwmValue.setText(getString(R.string.pwm_format, "***", "***"));
      binding.wheelCountValue.setText(getString(R.string.wheel_count_format, "***", "***"));
      vehicle.setHasSonar(false);
      vehicle.setHasIndicators(false);
      vehicle.setHasLedsFront(false);
      vehicle.setHasLedsBack(false);
      vehicle.setHasLedsStatus(false);
      vehicle.setHasBumpSensor(false);
      vehicle.setHasWheelOdometryFront(false);
      vehicle.setHasWheelOdometryBack(false);
    }

    if (vehicle.isHasLedsFront() && vehicle.isHasLedsBack()) {
      binding.ledsLabel.setVisibility(View.VISIBLE);
      binding.lightsSlider.setVisibility(View.VISIBLE);
    } else {
      binding.ledsLabel.setVisibility(View.INVISIBLE);
      binding.lightsSlider.setVisibility(View.INVISIBLE);
    }
  }

  @Override
  protected void processControllerKeyData(String command) {}

  @Override
  protected void processUSBData(String data) {
    if (!vehicle.isReady()) {
      vehicle.setReady(true);
      vehicle.requestVehicleConfig();
    }
    char header = data.charAt(0);
    String body = data.substring(1);
    // int type = -1;
    switch (header) {
      case 'r':
        vehicle.requestVehicleConfig();
      case 'f':
        binding.refreshToggle.setChecked(vehicle.isReady());
        updateGui(vehicle.isReady());
        break;
      case 'v':
        // Expecting: percentage,voltage
        String[] batteryParts = body.split(",");
        if (batteryParts.length == 2 && FormatUtils.isNumeric(batteryParts[0]) && FormatUtils.isNumeric(batteryParts[1])) {
          float percentage = Float.parseFloat(batteryParts[0]);
          float voltage = Float.parseFloat(batteryParts[1]);
          vehicle.setBatteryPercentage(percentage);
          updateBatteryDisplay((int) percentage, voltage);
        } else if (FormatUtils.isNumeric(body)) { // fallback for old format
          float percentage = Float.parseFloat(body);
          vehicle.setBatteryPercentage(percentage);
          updateBatteryDisplay((int) percentage, -1);
        }
        break;

      case 'e': // Wheel encoder angular velocity
        if (FormatUtils.isNumeric(body)) {
          float value = Float.parseFloat(body);
          vehicle.setWheelEncoderAngularVelocity(value);
          updateWheelEncoderAngularVelocity(value);
        }
        break;
      case 'i': // IMU angular velocity
        if (FormatUtils.isNumeric(body)) {
          float value = Float.parseFloat(body);
          vehicle.setImuAngularVelocity(value);
          updateImuAngularVelocity(value);
        }
        break;
      case 'k': // Fused angular velocity
        if (FormatUtils.isNumeric(body)) {
          float value = Float.parseFloat(body);
          vehicle.setFusedAngularVelocity(value);
          updateFusedAngularVelocity(value);
        }
        break;
      case 'p': // PWM values
        String[] pwmValues = body.split(",");
        if (pwmValues.length == 2 && FormatUtils.isNumeric(pwmValues[0]) && FormatUtils.isNumeric(pwmValues[1])) {
          float leftPwm = Float.parseFloat(pwmValues[0]);
          float rightPwm = Float.parseFloat(pwmValues[1]);
          vehicle.setLeftPwm(leftPwm);
          vehicle.setRightPwm(rightPwm);
          updatePwmValues(leftPwm, rightPwm);
        }
        break;
      case 'c': // Wheel hall effect counts
        String[] wheelCountValues = body.split(",");
        if (wheelCountValues.length == 2 && FormatUtils.isNumeric(wheelCountValues[0]) && FormatUtils.isNumeric(wheelCountValues[1])) {
          float leftCount = Float.parseFloat(wheelCountValues[0]);
          float rightCount = Float.parseFloat(wheelCountValues[1]);
          vehicle.setLeftWheelCount(leftCount);
          vehicle.setRightWheelCount(rightCount);
          updateWheelCountValues(leftCount, rightCount);
        }
        break;
    }
  }

  private void updateWheelEncoderAngularVelocity(float value) {
    if (binding != null) {
      binding.wheelEncoderValue.setText(
          getString(R.string.angular_velocity_format, String.format(Locale.US, "%.2f", value)));
    }
  }

  private void updateImuAngularVelocity(float value) {
    if (binding != null) {
      binding.imuValue.setText(
          getString(R.string.angular_velocity_format, String.format(Locale.US, "%.2f", value)));
    }
  }

  private void updateFusedAngularVelocity(float value) {
    if (binding != null) {
      binding.fusedValue.setText(
          getString(R.string.angular_velocity_format, String.format(Locale.US, "%.2f", value)));
    }
  }

  private void updatePwmValues(float leftPwm, float rightPwm) {
    if (binding != null) {
      binding.pwmValue.setText(
          getString(R.string.pwm_format,
                   String.format(Locale.US, "%3.0f", leftPwm),
                   String.format(Locale.US, "%3.0f", rightPwm)));
    }
  }

  private void updateBatteryDisplay(int percentage) {
    if (binding != null) {
      binding.batteryInfo.setText(String.format(Locale.US, "%d%%", percentage));
      binding.batteryProgressBar.setProgress(percentage);

      // Set color based on battery level
      int color;
      if (percentage > 50) {
        color = getResources().getColor(android.R.color.holo_green_light, null);
      } else if (percentage > 20) {
        color = getResources().getColor(android.R.color.holo_orange_light, null);
      } else {
        color = getResources().getColor(android.R.color.holo_red_light, null);
      }
      binding.batteryProgressBar.getProgressDrawable().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
    }
  }

  private void updateBatteryDisplay(int percentage, float voltage) {
    if (binding != null) {
      binding.batteryInfo.setText(String.format(Locale.US, "%d%%", percentage));
      binding.batteryProgressBar.setProgress(percentage);

      // Set color based on battery level
      int color;
      if (percentage > 50) {
        color = getResources().getColor(android.R.color.holo_green_light, null);
      } else if (percentage > 20) {
        color = getResources().getColor(android.R.color.holo_orange_light, null);
      } else {
        color = getResources().getColor(android.R.color.holo_red_light, null);
      }
      binding.batteryProgressBar.getProgressDrawable().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);

      // Update voltage display if voltage data is available
      if (voltage >= 0) {
        binding.batteryInfo.append(String.format(Locale.US, " (%.1fV)", voltage));
      }
    }
  }

  private void updateWheelCountValues(float leftCount, float rightCount) {
    if (binding != null) {
      binding.wheelCountValue.setText(
          getString(R.string.wheel_count_format,
                   String.format(Locale.US, "%3.0f", leftCount),
                   String.format(Locale.US, "%3.0f", rightCount)));
    }
  }

  private void updateSpeedMultiplierLabel(int value) {
    if (binding != null && binding.speedMultiplierLabel != null) {
      binding.speedMultiplierLabel.setText(getString(R.string.speed_multiplier, value));
    }
  }

  private void updateAngularMultiplierLabel(int value) {
    if (binding != null && binding.angularMultiplierLabel != null) {
      binding.angularMultiplierLabel.setText(getString(R.string.angular_multiplier, value));
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    // Ensure GUI is refreshed when resuming, especially after orientation changes
    // This helps maintain consistent state across orientation changes
    refreshGui();
  }

  @Override
  public void onConfigurationChanged(@NotNull android.content.res.Configuration newConfig) {
    super.onConfigurationChanged(newConfig);

    // Handle orientation changes gracefully
    // The layout will be automatically reloaded, but we should refresh the GUI state
    if (binding != null) {
      refreshGui();
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();

    // Reset orientation to unspecified when leaving this fragment
    // This ensures other fragments can set their preferred orientation
    if (getActivity() != null) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // Prevent memory leaks by clearing references to Views
    binding = null;
  }

  @Override
  public void onDetach() {
    super.onDetach();

    // Ensure orientation is reset when fragment is detached from its activity
    // This is a safety measure to prevent orientation lock issues
    if (getActivity() != null) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
  }
}
