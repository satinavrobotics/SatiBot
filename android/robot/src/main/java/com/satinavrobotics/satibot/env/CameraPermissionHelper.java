package com.satinavrobotics.satibot.env;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Helper to ask camera permission. */
public class CameraPermissionHelper {
  private static final int CAMERA_PERMISSION_CODE = 0;
  private static final String CAMERA_PERMISSION = Manifest.permission.CAMERA;

  /** Check to see if we have the necessary permissions for this app. */
  public static boolean hasCameraPermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity, CAMERA_PERMISSION)
        == PackageManager.PERMISSION_GRANTED;
  }

  /** Check to see if we should show the rationale for this permission. */
  public static boolean shouldShowRequestPermissionRationale(Activity activity) {
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, CAMERA_PERMISSION);
  }

  /** Request the camera permission. */
  public static void requestCameraPermission(Activity activity) {
    ActivityCompat.requestPermissions(
        activity, new String[] {CAMERA_PERMISSION}, CAMERA_PERMISSION_CODE);
  }

  /** Launch Application Setting to grant permission. */
  public static void launchPermissionSettings(Activity activity) {
    Intent intent = new Intent();
    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
    activity.startActivity(intent);
  }
}
