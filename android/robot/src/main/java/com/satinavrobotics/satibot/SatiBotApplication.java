package com.satinavrobotics.satibot;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.satinavrobotics.satibot.vehicle.Vehicle;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jetbrains.annotations.NotNull;

import timber.log.Timber;

public class SatiBotApplication extends Application {

  static Context context;
  public static Vehicle vehicle;

  public static Context getContext() {
    return context;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    context = getApplicationContext();

    // Initialize Timber first for logging
    initializeTimber();

    // Log the application start
    Log.i("", "SatiBotApplication onCreate started");

    try {
      // Initialize Google Play Services
      initializeGooglePlayServices();

      SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
      int baudRate = Integer.parseInt(sharedPreferences.getString("baud_rate", "115200"));
      vehicle = new Vehicle(this, baudRate);
      vehicle.initBle();
      vehicle.connectUsb();
      vehicle.initBle();

      Log.i("", "SatiBotApplication onCreate completed successfully");
    } catch (Exception e) {
      Log.e("", "Error in SatiBotApplication onCreate: " + e);
    }
  }

  private void initializeTimber() {
    // Always plant a debug tree for detailed logging
    Timber.plant(
        new Timber.DebugTree() {
          @NonNull
          @Override
          protected String createStackElementTag(@NotNull StackTraceElement element) {
            return super.createStackElementTag(element) + ":" + element.getLineNumber();
          }
        });

    // Log that Timber has been initialized
    Log.i("SatiBotApplication", "Timber has been initialized");
    Timber.i("Timber has been initialized with DebugTree");
  }

  private void initializeGooglePlayServices() {
    try {
      // Print app signature for debugging
      printAppSignatures();

      // Initialize Firebase first
      if (FirebaseApp.getApps(this).isEmpty()) {
        FirebaseApp.initializeApp(this);
        Log.d("", "Firebase initialized successfully");
      } else {
        Log.d("", "Firebase was already initialized");
      }

      // Update the security provider to protect against SSL exploits
      try {
        ProviderInstaller.installIfNeeded(this);
      } catch (Exception e) {
        Log.e("", "Error installing security provider: " + e);
        // Continue anyway, as this is not critical
      }

      // Check Google Play Services availability
      GoogleApiAvailability googleAPI = GoogleApiAvailability.getInstance();
      int result = googleAPI.isGooglePlayServicesAvailable(this);
      if (result != com.google.android.gms.common.ConnectionResult.SUCCESS) {
        Log.e("","Google Play Services not available: " + result);
        // Don't show dialog here as we're in the Application class
      } else {
        Log.d("", "Google Play Services availability check passed");
      }
    } catch (Exception e) {
      // Catch all exceptions to prevent app crash during initialization
      Log.e("", "Error during Google Play Services initialization: " + e);
    }
  }

  /**
   * Prints the app's SHA-1 and SHA-256 signatures for debugging purposes
   */
  private void printAppSignatures() {
    try {
      PackageInfo packageInfo = getPackageManager().getPackageInfo(
          getPackageName(),
          PackageManager.GET_SIGNATURES);

      for (Signature signature : packageInfo.signatures) {
        // Get SHA-1
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(signature.toByteArray());
        String sha1Hash = bytesToHex(sha1.digest());
        Log.i("", "SHA-1: " + sha1Hash);

        // Get SHA-256
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(signature.toByteArray());
        String sha256Hash = bytesToHex(sha256.digest());
        Log.i("", "SHA-256: " + sha256Hash);
      }
    } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
      Log.e("", "Error getting app signatures: " + e);
    }
  }

  /**
   * Converts a byte array to a hex string
   */
  private String bytesToHex(byte[] bytes) {
    final char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
    char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }

  @Override
  public void onTerminate() {
    super.onTerminate();
  }
}
