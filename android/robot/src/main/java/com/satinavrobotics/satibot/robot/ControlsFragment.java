package com.satinavrobotics.satibot.common;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.satinavrobotics.satibot.R;

import com.satinavrobotics.satibot.env.AudioPlayer;
import com.satinavrobotics.satibot.env.ControllerToBotEventBus;

import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.env.StatusManager;
import com.satinavrobotics.satibot.navigation.WaypointsManager;
import com.satinavrobotics.satibot.main.MainViewModel;
import com.satinavrobotics.satibot.tflite.Model;
import com.satinavrobotics.satibot.utils.ConnectionUtils;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.Enums;
import com.satinavrobotics.satibot.utils.FileUtils;
import com.satinavrobotics.satibot.utils.FormatUtils;

import com.satinavrobotics.satibot.utils.PermissionUtils;
import com.satinavrobotics.satibot.vehicle.Control;
import com.satinavrobotics.satibot.vehicle.Vehicle;

import timber.log.Timber;

public abstract class ControlsFragment extends Fragment  {

  protected MainViewModel mViewModel;
  protected Vehicle vehicle;
  protected Animation startAnimation;
  protected SharedPreferencesManager preferencesManager;

  protected AudioPlayer audioPlayer;

  protected final String voice = "matthew";
  protected List<Model> masterList;

  //protected ServerCommunication serverCommunication;
  private Spinner modelSpinner;
  private StatusManager statusManager;

  private WaypointsManager waypointsManager;

  private static final long CONTROL_TIMEOUT_MS = 250;
  private static final long CONTROL_UPDATE_INTERVAL_MS = 50; // Same as BluetoothControlFragment

  private Handler controlTimeoutHandler;
  private Handler controlUpdateHandler;
  private boolean isControlUpdateRunning = false;

  private final Runnable controlTimeoutRunnable = new Runnable() {
    @Override
    public void run() {
      // If no new control command has been received within the timeout, stop the vehicle
      if(vehicle != null) {
        try {
          JSONObject driveValue = new JSONObject();
          driveValue.put("l", 0); // linear velocity
          driveValue.put("a", 0); // angular velocity
          JSONObject driveCmd = new JSONObject();
          driveCmd.put("driveCmd", driveValue);
          vehicle.setControlVelocity(0, 0);
          processControllerKeyData(Constants.CMD_DRIVE);

          // Also reset the controller state
          if (vehicle.getGameController() != null) {
            vehicle.getGameController().resetControllerState();
          }
        } catch (JSONException e) {
          e.printStackTrace();
        }
        Timber.d("Control timeout reached, vehicle stopped.");
      }
    }
  };

  private final Runnable controlUpdateRunnable = new Runnable() {
    @Override
    public void run() {
      // If vehicle and game controller exist, send the current control state
      if (vehicle != null && vehicle.getGameController() != null &&
          Enums.ControlMode.getByID(preferencesManager.getControlMode()) == Enums.ControlMode.GAMEPAD) {

        // Get the current control state from the game controller
        Control currentControl = vehicle.getGameController().getCurrentControl();

        // Only send if there's actual input (non-zero values)
        if (currentControl.linear() != 0 || currentControl.angular() != 0) {
          vehicle.setControl(currentControl);
          processControlCommand(Constants.CMD_DRIVE);
        }
      }

      // Schedule the next update if still running
      if (isControlUpdateRunning && controlUpdateHandler != null) {
        controlUpdateHandler.postDelayed(this, CONTROL_UPDATE_INTERVAL_MS);
      }
    }
  };

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // create before inflateFragment() to prevent npe when calling addCamera()
    preferencesManager = new SharedPreferencesManager(requireContext());
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    requireActivity()
        .getWindow()
        .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


    waypointsManager = WaypointsManager.getInstance();
    statusManager = StatusManager.getInstance();
    audioPlayer = new AudioPlayer(requireContext());
    masterList = FileUtils.loadConfigJSONFromAsset(requireActivity());

    // Initialize control timeout handler
    controlTimeoutHandler = new Handler(Looper.getMainLooper());
    resetControlTimer();

    // Start periodic control updates
    startControlUpdates();

    requireActivity()
        .getSupportFragmentManager()
        .setFragmentResultListener(
            Constants.GENERIC_MOTION_EVENT,
            this,
            (requestKey, result) -> {
              MotionEvent motionEvent = result.getParcelable(Constants.DATA);
              // Process joystick input to update controller state
              vehicle.getGameController().processJoystickInput(motionEvent, -1);
              // The periodic update handler will take care of sending the control commands
            });
    requireActivity()
        .getSupportFragmentManager()
        .setFragmentResultListener(
            Constants.KEY_EVENT,
            this,
            (requestKey, result) -> {
              KeyEvent event = result.getParcelable(Constants.DATA);
              if (KeyEvent.ACTION_UP == event.getAction()) {
                processKeyEvent(result.getParcelable(Constants.DATA));
              }
              // Process the button input to update controller state
              vehicle.getGameController().processButtonInput(result.getParcelable(Constants.DATA));
              // The periodic update handler will take care of sending the control commands
            });

    mViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

    vehicle = mViewModel.getVehicle().getValue();
    startAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.blink);

    mViewModel
        .getDeviceData()
        .observe(
            getViewLifecycleOwner(),
            data -> {
              char header = data.charAt(0);
              String body = data.substring(1);

              switch (header) {
                case 'r':
                  vehicle.setReady(true);
                  break;
                case 'f':
                  vehicle.processVehicleConfig(body);
                  break;
                case 'v':
                  if (FormatUtils.isNumeric(body)) {
                    vehicle.setBatteryVoltage(Float.parseFloat(body));
                  }
                  break;
                case 's':
                  if (FormatUtils.isNumeric(body)) {
                    //vehicle.setSonarReading(Float.parseFloat(body));

                  }
                  break;
                case 'w':
                  String[] itemList = body.split(",");
                  if (itemList.length == 2
                      && FormatUtils.isNumeric(itemList[0])
                      && FormatUtils.isNumeric(itemList[1])) {
                    vehicle.setLeftWheelRpm(Float.parseFloat(itemList[0]));
                    vehicle.setRightWheelRpm(Float.parseFloat(itemList[1]));
                  }
                  break;
                case 'b':
                  // do nothing
                  break;
              }

              processUSBData(data);
            });

    handlePhoneControllerEvents();
  }

  protected void processKeyEvent(KeyEvent keyCode) {
    if (Enums.ControlMode.getByID(preferencesManager.getControlMode())
        == Enums.ControlMode.GAMEPAD) {
      switch (keyCode.getKeyCode()) {
        case KeyEvent.KEYCODE_BUTTON_X: // square
          toggleIndicatorEvent(Enums.VehicleIndicator.LEFT.getValue());
          processControllerKeyData(Constants.CMD_INDICATOR_LEFT);
          break;
        case KeyEvent.KEYCODE_BUTTON_Y: // triangle
          toggleIndicatorEvent(Enums.VehicleIndicator.STOP.getValue());
          processControllerKeyData(Constants.CMD_INDICATOR_STOP);
          break;
        case KeyEvent.KEYCODE_BUTTON_B: // circle
          toggleIndicatorEvent(Enums.VehicleIndicator.RIGHT.getValue());
          processControllerKeyData(Constants.CMD_INDICATOR_RIGHT);
          break;
        case KeyEvent.KEYCODE_BUTTON_A: // x
          processControllerKeyData(Constants.CMD_LOGS);
          break;
        case KeyEvent.KEYCODE_BUTTON_L1:
          break;
        case KeyEvent.KEYCODE_BUTTON_R1:
          processControllerKeyData(Constants.CMD_NETWORK);
          break;
        case KeyEvent.KEYCODE_BUTTON_THUMBL:
          processControllerKeyData(Constants.CMD_SPEED_DOWN);
          audioPlayer.playSpeedMode(
              voice, Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()));
          break;
        case KeyEvent.KEYCODE_BUTTON_THUMBR:
          processControllerKeyData(Constants.CMD_SPEED_UP);

          audioPlayer.playSpeedMode(
              voice, Enums.SpeedMode.getByID(preferencesManager.getSpeedMode()));
          break;

        default:
          break;
      }
    }
  }

  private void handlePhoneControllerEvents() {
    ControllerToBotEventBus.subscribe(
        this.getClass().getSimpleName(),
        event -> {
          Timber.d("Event received: %s", event.toString());
          String commandType = "";
          if (event.has("command")) {
            commandType = event.getString("command");
          } else if (event.has("driveCmd")) {
            commandType = Constants.CMD_DRIVE;
          }

          switch (commandType) {
            case Constants.CMD_DRIVE:
              Timber.d("Drive command received");
              Timber.d("Drive command received: " + event.toString());
              JSONObject driveValue = event.getJSONObject("driveCmd");

              // Check if we have linear and angular velocity (new format)
              if (driveValue.has("l") && driveValue.has("a")) {
                vehicle.setControlVelocity(
                    Float.parseFloat(driveValue.getString("l")),
                    Float.parseFloat(driveValue.getString("a")));
              }

            case Constants.CMD_INDICATOR_LEFT:
              toggleIndicatorEvent(Enums.VehicleIndicator.LEFT.getValue());
              break;

            case Constants.CMD_INDICATOR_RIGHT:
              toggleIndicatorEvent(Enums.VehicleIndicator.RIGHT.getValue());
              break;

            case Constants.CMD_INDICATOR_STOP:
              toggleIndicatorEvent(Enums.VehicleIndicator.STOP.getValue());
              break;

              // We re connected to the controller, send back status info
            case Constants.CMD_CONNECTED:
              statusManager.updateStatus(
                  ConnectionUtils.getStatus(
                      false, false, vehicle.getIndicator()));
              break;

            case Constants.CMD_DISCONNECTED:
              vehicle.setControlVelocity(0, 0);
              break;

            case Constants.CMD_WAYPOINTS:
              JSONArray waypoints = event.getJSONArray("waypoints");
              waypointsManager.setWaypoints(waypoints);
              break;

          }

          processControlCommand(commandType);
        },
        error -> {
          Timber.d("Error occurred in ControllerToBotEventBus: %s", error);
        },
        event -> event.has("command") || event.has("driveCmd") || event.has("server") // filter out everything else
        );
  }


  private void toggleIndicatorEvent(int value) {
    vehicle.setIndicator(value);
    statusManager.updateStatus(ConnectionUtils.createStatus("INDICATOR_LEFT", value == -1));
    statusManager.updateStatus(ConnectionUtils.createStatus("INDICATOR_RIGHT", value == 1));
    statusManager.updateStatus(ConnectionUtils.createStatus("INDICATOR_STOP", value == 0));
  }



  @NotNull
  protected List<String> getModelNames(Predicate<Model> filter) {
    return masterList.stream()
        .filter(filter)
        .map(f -> FileUtils.nameWithoutExtension(f.name))
        .collect(Collectors.toList());
  }

  @Override
  public void onResume() {
    super.onResume();
    // Restart control updates when fragment resumes
    startControlUpdates();
  }

  @Override
  public void onDestroy() {
    Timber.d("onDestroy");
    // Stop control updates
    stopControlUpdates();
    controlUpdateHandler = null;

    ControllerToBotEventBus.unsubscribe(this.getClass().getSimpleName());
    vehicle.setControlVelocity(0, 0);
    super.onDestroy();
  }

  @Override
  public synchronized void onPause() {
    Timber.d("onPause");
    // Stop control updates when fragment pauses
    stopControlUpdates();
    vehicle.setControlVelocity(0, 0);
    super.onPause();
  }

  @Override
  public void onStop() {
    Timber.d("onStop");
    super.onStop();
  }

  protected void initModelSpinner(Spinner spinner, List<String> models, String selected) {
      ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, models);
    modelAdapter.setDropDownViewResource(android.R.layout.simple_dropdown_item_1line);
    modelSpinner = spinner;
    modelSpinner.setAdapter(modelAdapter);
    if (!selected.isEmpty())
      modelSpinner.setSelection(
          Math.max(0, modelAdapter.getPosition(FileUtils.nameWithoutExtension(selected))));
    modelSpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String selected = parent.getItemAtPosition(position).toString();
            try {
              masterList.stream()
                  .filter(f -> f.name.contains(selected))
                  .findFirst()
                  .ifPresent(value -> setModel(value));

            } catch (IllegalArgumentException e) {
              e.printStackTrace();
            }
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
  }


  protected void setModel(Model model) {}

  protected abstract void processControllerKeyData(String command);

  protected abstract void processUSBData(String data);

  protected void resetControlTimer() {
    controlTimeoutHandler.removeCallbacks(controlTimeoutRunnable);
    controlTimeoutHandler.postDelayed(controlTimeoutRunnable, CONTROL_TIMEOUT_MS);
  }

  /**
   * Initialize and start the periodic control update handler
   */
  protected void startControlUpdates() {
    // Initialize the handler if needed
    if (controlUpdateHandler == null) {
      controlUpdateHandler = new Handler(Looper.getMainLooper());
    }

    // Stop any existing updates first
    stopControlUpdates();

    // Start the updates
    isControlUpdateRunning = true;
    controlUpdateHandler.post(controlUpdateRunnable);
    Timber.d("Started periodic control updates");
  }

  /**
   * Stop the periodic control updates
   */
  protected void stopControlUpdates() {
    isControlUpdateRunning = false;

    if (controlUpdateHandler != null) {
      controlUpdateHandler.removeCallbacks(controlUpdateRunnable);
    }

    // Reset controller state when stopping updates
    if (vehicle != null && vehicle.getGameController() != null) {
      vehicle.getGameController().resetControllerState();
    }
  }

  protected final void processControlCommand(String command) {
    processControllerKeyData(command);
    resetControlTimer();
  }

  protected final ActivityResultLauncher<String[]> requestPermissionLauncher =
          registerForActivityResult(
                  new ActivityResultContracts.RequestMultiplePermissions(),
                  result -> {
                    AtomicBoolean allGranted = new AtomicBoolean(false);
                    result.forEach((permission, granted) -> allGranted.set(allGranted.get() && granted));

                    if (!allGranted.get()) {
                      PermissionUtils.showControllerPermissionsToast(requireActivity());
                    }
                  });

  protected StatusManager getStatusManager() {
    return statusManager;
  }
}
