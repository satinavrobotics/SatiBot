package com.satinavrobotics.satibot.robot;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.openbot.R;
import org.openbot.databinding.FragmentRobotInfoBinding;

import com.satinavrobotics.satibot.common.ControlsFragment;
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

    if (vehicle.getConnectionType().equals("USB")) {
      binding.usbToggle.setVisibility(View.VISIBLE);
      binding.bleToggle.setVisibility(View.GONE);
    } else if (vehicle.getConnectionType().equals("Bluetooth")) {
      binding.bleToggle.setVisibility(View.VISIBLE);
      binding.usbToggle.setVisibility(View.GONE);
    }

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

    binding.usbToggle.setOnCheckedChangeListener((buttonView, isChecked) -> refreshGui());
    binding.bleToggle.setOnCheckedChangeListener((buttonView, isChecked) -> refreshGui());
    binding.refreshToggle.setOnClickListener(v -> refreshGui());

    binding.lightsSlider.addOnChangeListener(
        (slider, value, fromUser) -> {
          vehicle.sendLightIntensity(value / 100, value / 100);
        });

    binding.motorsForwardButton.setOnClickListener(v -> vehicle.setControlVelocity(0.1f, 0.0f));

    binding.motorsBackwardButton.setOnClickListener(v -> vehicle.setControlVelocity(-0.1f, 0.0f));

    binding.motorsStopButton.setOnClickListener(v -> vehicle.setControlVelocity(0.0f, 0.0f));

    refreshGui();
  }

  private void refreshGui() {
    updateGui(false);
    binding.refreshToggle.setChecked(false);
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
      binding.voltageInfo.setText(R.string.voltage);
      binding.speedInfo.setText(R.string.rpm);
      binding.sonarInfo.setText(R.string.distance);
      binding.wheelEncoderValue.setText(getString(R.string.angular_velocity_format, "*.** "));
      binding.imuValue.setText(getString(R.string.angular_velocity_format, "*.** "));
      binding.fusedValue.setText(getString(R.string.angular_velocity_format, "*.** "));
      binding.pwmValue.setText(getString(R.string.pwm_format, "***", "***"));
      binding.wheelCountValue.setText(getString(R.string.wheel_count_format, "***", "***"));
      vehicle.setHasVoltageDivider(false);
      vehicle.setHasSonar(false);
      vehicle.setHasIndicators(false);
      vehicle.setHasLedsFront(false);
      vehicle.setHasLedsBack(false);
      vehicle.setHasLedsStatus(false);
      vehicle.setHasBumpSensor(false);
      vehicle.setHasWheelOdometryFront(false);
      vehicle.setHasWheelOdometryBack(false);
    }
    binding.voltageSwitch.setChecked(vehicle.isHasVoltageDivider());
    binding.sonarSwitch.setChecked(vehicle.isHasSonar());
    binding.indicatorLedsSwitch.setChecked(vehicle.isHasIndicators());
    binding.ledsFrontSwitch.setChecked(vehicle.isHasLedsFront());
    binding.ledsBackSwitch.setChecked(vehicle.isHasLedsBack());
    binding.ledsStatusSwitch.setChecked(vehicle.isHasLedsStatus());
    binding.bumpersSwitch.setChecked(vehicle.isHasBumpSensor());
    binding.wheelOdometryFrontSwitch.setChecked(vehicle.isHasWheelOdometryFront());
    binding.wheelOdometryBackSwitch.setChecked(vehicle.isHasWheelOdometryBack());

    if (vehicle.isHasLedsFront() && vehicle.isHasLedsBack()) {
      binding.ledsLabel.setVisibility(View.VISIBLE);
      binding.lightsSlider.setVisibility(View.VISIBLE);
    } else {
      binding.ledsLabel.setVisibility(View.INVISIBLE);
      binding.lightsSlider.setVisibility(View.INVISIBLE);
    }
  }

  @Override
  protected void processControllerKeyData(String command) {
    // Do nothing
  }

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
        binding.voltageInfo.setText(
            String.format(Locale.US, "%2.1f V", vehicle.getBatteryVoltage()));
        break;
      case 'w':
        binding.speedInfo.setText(
            String.format(
                Locale.US,
                "%3.0f,%3.0f rpm",
                vehicle.getLeftWheelRpm(),
                vehicle.getRightWheelRpm()));
        break;
      case 's':
        binding.sonarInfo.setText(String.format(Locale.US, "%3.0f cm", vehicle.getSonarReading()));
        break;
      case 'e': // Wheel encoder angular velocity
        Timber.d("e");
        if (FormatUtils.isNumeric(body)) {
          float value = Float.parseFloat(body);
          vehicle.setWheelEncoderAngularVelocity(value);
          updateWheelEncoderAngularVelocity(value);
        }
        break;
      case 'i': // IMU angular velocity
        Timber.d("i");
        if (FormatUtils.isNumeric(body)) {
          float value = Float.parseFloat(body);
          vehicle.setImuAngularVelocity(value);
          updateImuAngularVelocity(value);
        }
        break;
      case 'k': // Fused angular velocity
        Timber.d("k");
        if (FormatUtils.isNumeric(body)) {
          float value = Float.parseFloat(body);
          vehicle.setFusedAngularVelocity(value);
          updateFusedAngularVelocity(value);
        }
        break;
      case 'p': // PWM values
        Timber.d("p");
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
        Timber.d("c");
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

  private void updateWheelCountValues(float leftCount, float rightCount) {
    if (binding != null) {
      binding.wheelCountValue.setText(
          getString(R.string.wheel_count_format,
                   String.format(Locale.US, "%3.0f", leftCount),
                   String.format(Locale.US, "%3.0f", rightCount)));
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    binding.bleToggle.setChecked(vehicle.bleConnected());
  }
}
