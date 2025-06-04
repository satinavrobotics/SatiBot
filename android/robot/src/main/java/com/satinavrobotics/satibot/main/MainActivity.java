package com.satinavrobotics.satibot.main;

import static com.satinavrobotics.satibot.utils.Constants.DEVICE_ACTION_DATA_RECEIVED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.satinavrobotics.satibot.R;

import com.satinavrobotics.satibot.SatiBotApplication;
import com.satinavrobotics.satibot.livekit.LiveKitServer;
import com.satinavrobotics.satibot.utils.ConnectionUtils;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.PermissionUtils;
import com.satinavrobotics.satibot.vehicle.UsbConnection;
import com.satinavrobotics.satibot.vehicle.Vehicle;

import timber.log.Timber;

// For a library module, uncomment the following line
// import org.openbot.controller.ControllerActivity;

public class MainActivity extends AppCompatActivity {

  private MainViewModel viewModel;
  private BroadcastReceiver localBroadcastReceiver;
  private Vehicle vehicle;
  private LocalBroadcastManager localBroadcastManager;
  private BottomNavigationView bottomNavigationView;
  private NavController navController;
  private LiveKitServer liveKitServer;
  private LiveKitServer.ConnectionStateListener connectionStateListener;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    viewModel = new ViewModelProvider(this).get(MainViewModel.class);
    vehicle = SatiBotApplication.vehicle;
    bottomNavigationView = findViewById(R.id.bottomNavigationView);
    bottomNavigationView.setSelectedItemId(R.id.mainFragment);
    //    if (vehicle == null) {
    //      SharedPreferences sharedPreferences =
    // PreferenceManager.getDefaultSharedPreferences(this);
    //      int baudRate = Integer.parseInt(sharedPreferences.getString("baud_rate", "115200"));
    //      vehicle = new Vehicle(this, baudRate);
    //      vehicle.connectUsb();
    viewModel.setVehicle(vehicle);
    //    }

    // Initialize LiveKit server
    initializeLiveKitServer();

    localBroadcastReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action != null) {
              switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                  if (!vehicle.isUsbConnected()) {
                    vehicle.connectUsb();
                    viewModel.setUsbStatus(vehicle.isUsbConnected());
                  }
                  Timber.i("USB device attached");
                  break;

                  // Case activated when app is not set to open default when usb is connected
                case UsbConnection.ACTION_USB_PERMISSION:
                  synchronized (this) {
                    UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                      if (usbDevice != null) {
                        // call method to set up device communication
                        if (!vehicle.isUsbConnected()) {
                          vehicle.connectUsb();
                        }
                        viewModel.setUsbStatus(vehicle.isUsbConnected());
                        Timber.i("USB device attached");
                      }
                    }
                  }

                  break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                  vehicle.disconnectUsb();
                  viewModel.setUsbStatus(vehicle.isUsbConnected());
                  Timber.i("USB device detached");
                  break;
                case DEVICE_ACTION_DATA_RECEIVED:
                  viewModel.setDeviceData(intent.getStringExtra("data"));
                  break;
              }
            }
          }
        };
    IntentFilter localIntentFilter = new IntentFilter();
    localIntentFilter.addAction(DEVICE_ACTION_DATA_RECEIVED);
    localIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    localIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    localIntentFilter.addAction(UsbConnection.ACTION_USB_PERMISSION);

    localBroadcastManager = LocalBroadcastManager.getInstance(this);
    localBroadcastManager.registerReceiver(localBroadcastReceiver, localIntentFilter);

    registerReceiver(localBroadcastReceiver, localIntentFilter, Context.RECEIVER_NOT_EXPORTED);

    NavHostFragment navHostFragment =
        (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
    navController = navHostFragment.getNavController();
    AppBarConfiguration appBarConfiguration =
        new AppBarConfiguration.Builder(navController.getGraph()).build();
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    bottomNavigationView.setOnItemReselectedListener(
        item -> {
          // Do nothing when the selected item is already selected
        });

    // Custom handling for bottom navigation item selection
    bottomNavigationView.setOnItemSelectedListener(item -> {
        int itemId = item.getItemId();
        int currentDestId = navController.getCurrentDestination().getId();

        // If we're already at the destination, do nothing
        if (itemId == currentDestId) {
            return true;
        }

        // Always navigate directly to the destination ID, not using actions
        // This prevents the "cannot be found from the current destination" error
        navController.navigate(itemId);
        return true;
    });

    NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);
    // Don't use automatic setup for bottom nav - we're handling it manually
    // NavigationUI.setupWithNavController(bottomNavigationView, navController);

    navController.addOnDestinationChangedListener(
        (controller, destination, arguments) -> {
          if (destination.getId() == R.id.mainFragment
              || destination.getId() == R.id.settingsFragment
              || destination.getId() == R.id.usbFragment
              || destination.getId() == R.id.robotInfoFragment
              || destination.getId() == R.id.profileFragment) {
            toolbar.setVisibility(View.VISIBLE);
            bottomNavigationView.setVisibility(View.VISIBLE);
          } else {
            toolbar.setVisibility(View.GONE);
            bottomNavigationView.setVisibility(View.GONE);
          }

          // To update the toolbar icon according to the Fragment.
          Menu menu = toolbar.getMenu();
          if (menu != null) {
            if (vehicle.getConnectionType().equals("Bluetooth")) {
              menu.findItem(R.id.bluetoothFragment).setVisible(true);
            }
            menu.findItem(R.id.settingsFragment).setVisible(true);
            menu.findItem(R.id.liveKitInfoFragment).setVisible(true);
            updateLiveKitIcon(menu);
          }
        });

    //    if (savedInstanceState == null) {
    //      // Default to open this when app opens
    //      Intent intent = new Intent(this, DefaultActivity.class);
    //      startActivity(intent);
    //    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.menu_items, menu);
    if (vehicle.getConnectionType().equals("Bluetooth")) {
      menu.findItem(R.id.usbFragment).setVisible(false);
      menu.findItem(R.id.bluetoothFragment).setVisible(true);
    } else if (vehicle.getConnectionType().equals("USB")) {
      menu.findItem(R.id.usbFragment).setVisible(true);
      menu.findItem(R.id.bluetoothFragment).setVisible(false);
    }

    // Update LiveKit and Bluetooth icons
    updateLiveKitIcon(menu);
    updateBluetoothIcon(menu);

    // Force LiveKit connection state update now that menu is ready
    if (liveKitServer != null) {
      liveKitServer.forceConnectionStateUpdate();
    }

    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
    if (item.getItemId() == R.id.liveKitInfoFragment) {
      navController.navigate(R.id.liveKitInfoFragment);
      return true;
    }

    return NavigationUI.onNavDestinationSelected(item, navController)
        || super.onOptionsItemSelected(item);
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event) {
    if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        && event.getAction() == MotionEvent.ACTION_MOVE) {
      Bundle bundle = new Bundle();
      bundle.putParcelable(Constants.DATA, event);
      getSupportFragmentManager().setFragmentResult(Constants.GENERIC_MOTION_EVENT, bundle);
      return true;
    }
    return super.dispatchGenericMotionEvent(event);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    Bundle bundle = new Bundle();
    bundle.putParcelable(Constants.DATA_CONTINUOUS, event);
    getSupportFragmentManager().setFragmentResult(Constants.KEY_EVENT_CONTINUOUS, bundle);

    // Check that the event came from a game controller
    if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
      bundle.putParcelable(Constants.DATA, event);
      getSupportFragmentManager().setFragmentResult(Constants.KEY_EVENT, bundle);
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  public synchronized void onDestroy() {
    if (localBroadcastManager != null) {
      localBroadcastManager.unregisterReceiver(localBroadcastReceiver);
      localBroadcastManager = null;
    }

    unregisterReceiver(localBroadcastReceiver);
    if (localBroadcastReceiver != null) localBroadcastReceiver = null;
    if (!isChangingConfigurations()) vehicle.disconnectUsb();

    // Clean up LiveKit connection state listener
    if (liveKitServer != null && connectionStateListener != null) {
      liveKitServer.removeConnectionStateListener(connectionStateListener);
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    // Start periodic updates of LiveKit and Bluetooth icons
    startIconUpdates();

    // Notify LiveKit server about app resume
    if (liveKitServer != null) {
      liveKitServer.onAppResumed();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    // Notify LiveKit server about app pause
    if (liveKitServer != null) {
      liveKitServer.onAppPaused();
    }
  }

  private void initializeLiveKitServer() {
    try {
      liveKitServer = LiveKitServer.getInstance(this);

      // Create and store connection state listener for immediate UI updates
      connectionStateListener = (connected, roomState) -> {
        runOnUiThread(() -> {
          Toolbar toolbar = findViewById(R.id.toolbar);
          if (toolbar != null && toolbar.getMenu() != null) {
            updateLiveKitIconWithState(toolbar.getMenu(), connected);
          }
        });
      };
      liveKitServer.addConnectionStateListener(connectionStateListener);

      // Auto-connect if permissions are available and internet is connected
      if (PermissionUtils.hasControllerPermissions(this) &&
          ConnectionUtils.isInternetAvailable(this)) {
        liveKitServer.connect();
      } else if (!ConnectionUtils.isInternetAvailable(this)) {
        Timber.w("No internet connection available, skipping auto-connect to LiveKit");
      }
    } catch (Exception e) {
      Timber.e(e, "Failed to initialize LiveKit server");
    }
  }

  private void updateLiveKitIcon(Menu menu) {
    if (liveKitServer != null) {
      updateLiveKitIconWithState(menu, liveKitServer.isRoomConnected());
    }
  }

  private void updateLiveKitIconWithState(Menu menu, boolean connected) {
    MenuItem liveKitItem = menu.findItem(R.id.liveKitInfoFragment);
    if (liveKitItem != null) {
      liveKitItem.setIcon(connected ?
        R.drawable.ic_livekit_connected :
        R.drawable.ic_livekit_disconnected);
    }
  }

  private void updateBluetoothIcon(Menu menu) {
    MenuItem bluetoothItem = menu.findItem(R.id.bluetoothFragment);
    if (bluetoothItem != null && vehicle != null) {
      boolean isConnected = vehicle.bleConnected();
      bluetoothItem.setIcon(isConnected ?
        R.drawable.ic_bluetooth_connected_white :
        R.drawable.ic_bluetooth_disconnected);
    }
  }

  private void startIconUpdates() {
    // Update Bluetooth icon every 3 seconds (LiveKit icon is now updated via listener)
    Runnable updateRunnable = new Runnable() {
      @Override
      public void run() {
        if (!isDestroyed() && !isFinishing()) {
          Toolbar toolbar = findViewById(R.id.toolbar);
          if (toolbar != null) {
            updateBluetoothIcon(toolbar.getMenu());
          }
          // Schedule next update
          toolbar.postDelayed(this, 3000);
        }
      }
    };

    Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) {
      toolbar.post(updateRunnable);
    }
  }
}
