package com.satinavrobotics.satibot.env;

import android.content.Context;
import android.content.SharedPreferences;



import com.satinavrobotics.satibot.utils.Enums;

public class SharedPreferencesManager {

  private static final String PREFERENCES_NAME = "openbot_settings";
  private static final int DEFAULT_BAUD_RATE = 115200;
  private static final String BAUD_RATE = "BAUD_RATE";
  private static final int DEFAULT_LOG_MODE = Enums.LogMode.CROP_IMG.ordinal();
  private static final String LOG_MODE = "LOG_MODE";
  private static final int DEFAULT_CONTROL_MODE = Enums.ControlMode.GAMEPAD.getValue();
  private static final String CONTROL_MODE = "CONTROL_MODE";
  private static final int DEFAULT_SPEED_MODE = Enums.SpeedMode.NORMAL.getValue();
  private static final String SPEED_MODE = "SPEED_MODE";
  private static final int DEFAULT_SPEED_MULTIPLIER = 192;
  private static final String SPEED_MULTIPLIER = "SPEED_MULTIPLIER";
  private static final int DEFAULT_ANGULAR_MULTIPLIER = 192;
  private static final String ANGULAR_MULTIPLIER = "ANGULAR_MULTIPLIER";
  private static final String DRIVE_MODE = "DRIVE_MODE";

  private static final String DEFAULT_MODEL = "DEFAULT_MODEL_NAME";
  private static final String OBJECT_NAV_MODEL = "OBJECT_NAV_MODEL_NAME";
  private static final String AUTOPILOT_MODEL = "AUTOPILOT_MODEL_NAME";
  private static final String SERVER_NAME = "SERVER_NAME";

  private static final String OBJECT_TYPE = "OBJECT_TYPE";
  private static final String DEFAULT_OBJECT_TYPE = "person";
  // object tracker switch for speed adjusted by estimated object distance
  private static final String OBJECT_NAV_DYNAMIC_SPEED = "OBJECT_NAV_DYNAMICSPEED";
  private static final String DEVICE = "DEVICE";
  private static final int DEFAULT_NUM_THREAD = 4;
  private static final String NUM_THREAD = "NUM_THREAD";
  private static final String CAMERA_SWITCH = "CAMERA_SWITCH";
  private static final String SHEET_EXPANDED = "SHEET_EXPANDED";
  private static final String DELAY = "DELAY";
  private static final String CURRENT_MAP_ID = "CURRENT_MAP_ID";

  // Depth source preference
  private static final String DEPTH_SOURCE = "DEPTH_SOURCE";
  private static final int DEFAULT_DEPTH_SOURCE = 0; // ARCORE by default

  // Robot parameters preferences
  private static final String ROBOT_WIDTH_METERS = "ROBOT_WIDTH_METERS";
  private static final float DEFAULT_ROBOT_WIDTH_METERS = 0.4f;

  private static final String VERTICAL_CLOSER_THRESHOLD = "VERTICAL_CLOSER_THRESHOLD";
  private static final float DEFAULT_VERTICAL_CLOSER_THRESHOLD = 20.0f;

  private static final String VERTICAL_FARTHER_THRESHOLD = "VERTICAL_FARTHER_THRESHOLD";
  private static final float DEFAULT_VERTICAL_FARTHER_THRESHOLD = 100.0f;

  private static final String MAX_SAFE_DISTANCE = "MAX_SAFE_DISTANCE";
  private static final float DEFAULT_MAX_SAFE_DISTANCE = 5000.0f;

  private static final String CONSECUTIVE_THRESHOLD = "CONSECUTIVE_THRESHOLD";
  private static final int DEFAULT_CONSECUTIVE_THRESHOLD = 3;

  private static final String DOWNSAMPLE_FACTOR = "DOWNSAMPLE_FACTOR";
  private static final int DEFAULT_DOWNSAMPLE_FACTOR = 8;

  private static final String DEPTH_GRADIENT_THRESHOLD = "DEPTH_GRADIENT_THRESHOLD";
  private static final float DEFAULT_DEPTH_GRADIENT_THRESHOLD = 200.0f;

  private static final String NAVIGABILITY_THRESHOLD = "NAVIGABILITY_THRESHOLD";
  private static final int DEFAULT_NAVIGABILITY_THRESHOLD = 3;

  private static final String CONFIDENCE_THRESHOLD = "CONFIDENCE_THRESHOLD";
  private static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.5f;

  private static final String TOO_CLOSE_THRESHOLD = "TOO_CLOSE_THRESHOLD";
  private static final float DEFAULT_TOO_CLOSE_THRESHOLD = 1000.0f; // 100cm in millimeters

  // Logger fragment preferences
  private static final String LOGGER_RESOLUTION = "LOGGER_RESOLUTION";
  private static final int DEFAULT_LOGGER_RESOLUTION = 0; // First item in resolution_values array

  private static final String LOGGER_FPS = "LOGGER_FPS";
  private static final int DEFAULT_LOGGER_FPS = 2; // 15 FPS (third item in fps_values array)

  private static final String LOGGER_SAVE_DESTINATION = "LOGGER_SAVE_DESTINATION";
  private static final int DEFAULT_LOGGER_SAVE_DESTINATION = 0; // Local Storage

  private static final String LOGGER_IMAGE_SOURCE = "LOGGER_IMAGE_SOURCE";
  private static final int DEFAULT_LOGGER_IMAGE_SOURCE = 0; // ARCore

  private final SharedPreferences preferences;

  public SharedPreferencesManager(Context context) {
    preferences =
        context
            .getApplicationContext()
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
  }

  public int getBaudrate() {
    return preferences.getInt(BAUD_RATE, DEFAULT_BAUD_RATE);
  }

  public int getLogMode() {
    return preferences.getInt(LOG_MODE, DEFAULT_LOG_MODE);
  }

  public int getControlMode() {
    return preferences.getInt(CONTROL_MODE, DEFAULT_CONTROL_MODE);
  }

  public int getSpeedMode() {
    return preferences.getInt(SPEED_MODE, DEFAULT_SPEED_MODE);
  }

  public int getSpeedMultiplier() {
    return preferences.getInt(SPEED_MULTIPLIER, DEFAULT_SPEED_MULTIPLIER);
  }

  public int getAngularMultiplier() {
    return preferences.getInt(ANGULAR_MULTIPLIER, DEFAULT_ANGULAR_MULTIPLIER);
  }

  public int getNumThreads() {
    return preferences.getInt(NUM_THREAD, DEFAULT_NUM_THREAD);
  }

  /**
   * Get selected camera lens facing
   *
   * @return true for LENS_FACING_FRONT, false for LENS_FACING_BACK
   */
  public boolean getCameraSwitch() {
    return preferences.getBoolean(CAMERA_SWITCH, false);
  }

  public boolean getSheetExpanded() {
    return preferences.getBoolean(SHEET_EXPANDED, false);
  }

  public void setBaudrate(int baudRate) {
    preferences.edit().putInt(BAUD_RATE, baudRate).apply();
  }

  public void setDefaultModel(String model) {
    preferences.edit().putString(DEFAULT_MODEL, model).apply();
  }

  public String getDefaultModel() {
    return preferences.getString(DEFAULT_MODEL, "");
  }

  public void setObjectNavModel(String model) {
    preferences.edit().putString(OBJECT_NAV_MODEL, model).apply();
  }

  public String getObjectNavModel() {
    return preferences.getString(OBJECT_NAV_MODEL, "");
  }

  public void setAutopilotModel(String model) {
    preferences.edit().putString(AUTOPILOT_MODEL, model).apply();
  }

  public String getAutopilotModel() {
    return preferences.getString(AUTOPILOT_MODEL, "");
  }

  public void setServer(String server) {
    preferences.edit().putString(SERVER_NAME, server).apply();
  }

  public String getServer() {
    return preferences.getString(SERVER_NAME, "");
  }

  public void setObjectType(String model) {
    preferences.edit().putString(OBJECT_TYPE, model).apply();
  }

  public String getObjectType() {
    return preferences.getString(OBJECT_TYPE, DEFAULT_OBJECT_TYPE);
  }

  public void setDevice(int device) {
    preferences.edit().putInt(DEVICE, device).apply();
  }

  public void setDriveMode(int mode) {
    preferences.edit().putInt(DRIVE_MODE, mode).apply();
  }

  public void setDynamicSpeed(boolean isEnabled) {
    preferences.edit().putBoolean(OBJECT_NAV_DYNAMIC_SPEED, isEnabled).apply();
  }

  public boolean getDynamicSpeed() {
    return preferences.getBoolean(OBJECT_NAV_DYNAMIC_SPEED, false);
  }

  public void setLogMode(int mode) {
    preferences.edit().putInt(LOG_MODE, mode).apply();
  }

  public void setControlMode(int mode) {
    preferences.edit().putInt(CONTROL_MODE, mode).apply();
  }

  public void setSpeedMode(int mode) {
    preferences.edit().putInt(SPEED_MODE, mode).apply();
  }

  public void setSpeedMultiplier(int multiplier) {
    preferences.edit().putInt(SPEED_MULTIPLIER, multiplier).apply();
  }

  public void setAngularMultiplier(int multiplier) {
    preferences.edit().putInt(ANGULAR_MULTIPLIER, multiplier).apply();
  }

  public void setNumThreads(int numThreads) {
    preferences.edit().putInt(NUM_THREAD, numThreads).apply();
  }

  public void setCameraSwitch(boolean isChecked) {
    preferences.edit().putBoolean(CAMERA_SWITCH, isChecked).apply();
  }

  public void setSheetExpanded(boolean expanded) {
    preferences.edit().putBoolean(SHEET_EXPANDED, expanded).apply();
  }

  public void setSensorStatus(boolean status, String sensor) {
    preferences.edit().putBoolean(sensor, status).apply();
  }

  public boolean getSensorStatus(String sensor) {
    return preferences.getBoolean(sensor, false);
  }

  public void setDelay(int delay) {
    preferences.edit().putInt(DELAY, delay).apply();
  }

  public int getDelay() {
    return preferences.getInt(DELAY, 200);
  }



  /**
   * Set the current selected map ID
   *
   * @param mapId The ID of the selected map
   */
  public void setCurrentMapId(String mapId) {
    preferences.edit().putString(CURRENT_MAP_ID, mapId).apply();
  }

  /**
   * Get the current selected map ID
   *
   * @return The ID of the selected map, or null if no map is selected
   */
  public String getCurrentMapId() {
    return preferences.getString(CURRENT_MAP_ID, null);
  }

  /**
   * Get the selected depth source
   *
   * @return The depth source index (0 for ARCore, 1 for TFLite, 2 for ONNX)
   */
  public int getDepthSource() {
    return preferences.getInt(DEPTH_SOURCE, DEFAULT_DEPTH_SOURCE);
  }

  /**
   * Set the selected depth source
   *
   * @param depthSource The depth source index (0 for ARCore, 1 for TFLite, 2 for ONNX)
   */
  public void setDepthSource(int depthSource) {
    preferences.edit().putInt(DEPTH_SOURCE, depthSource).apply();
  }

  /**
   * Get the robot width in meters
   *
   * @return The robot width in meters
   */
  public float getRobotWidthMeters() {
    return preferences.getFloat(ROBOT_WIDTH_METERS, DEFAULT_ROBOT_WIDTH_METERS);
  }

  /**
   * Set the robot width in meters
   *
   * @param widthMeters The robot width in meters
   */
  public void setRobotWidthMeters(float widthMeters) {
    preferences.edit().putFloat(ROBOT_WIDTH_METERS, widthMeters).apply();
  }

  /**
   * Get the vertical closer threshold in millimeters
   *
   * @return The vertical closer threshold in millimeters
   */
  public float getVerticalCloserThreshold() {
    return preferences.getFloat(VERTICAL_CLOSER_THRESHOLD, DEFAULT_VERTICAL_CLOSER_THRESHOLD);
  }

  /**
   * Set the vertical closer threshold in millimeters
   *
   * @param thresholdMm The vertical closer threshold in millimeters
   */
  public void setVerticalCloserThreshold(float thresholdMm) {
    preferences.edit().putFloat(VERTICAL_CLOSER_THRESHOLD, thresholdMm).apply();
  }

  /**
   * Get the vertical farther threshold in millimeters
   *
   * @return The vertical farther threshold in millimeters
   */
  public float getVerticalFartherThreshold() {
    return preferences.getFloat(VERTICAL_FARTHER_THRESHOLD, DEFAULT_VERTICAL_FARTHER_THRESHOLD);
  }

  /**
   * Set the vertical farther threshold in millimeters
   *
   * @param thresholdMm The vertical farther threshold in millimeters
   */
  public void setVerticalFartherThreshold(float thresholdMm) {
    preferences.edit().putFloat(VERTICAL_FARTHER_THRESHOLD, thresholdMm).apply();
  }

  /**
   * Get the maximum safe distance in millimeters
   *
   * @return The maximum safe distance in millimeters
   */
  public float getMaxSafeDistance() {
    return preferences.getFloat(MAX_SAFE_DISTANCE, DEFAULT_MAX_SAFE_DISTANCE);
  }

  /**
   * Set the maximum safe distance in millimeters
   *
   * @param distanceMm The maximum safe distance in millimeters
   */
  public void setMaxSafeDistance(float distanceMm) {
    preferences.edit().putFloat(MAX_SAFE_DISTANCE, distanceMm).apply();
  }

  /**
   * Get the consecutive threshold in pixels
   *
   * @return The consecutive threshold in pixels
   */
  public int getConsecutiveThreshold() {
    return preferences.getInt(CONSECUTIVE_THRESHOLD, DEFAULT_CONSECUTIVE_THRESHOLD);
  }

  /**
   * Set the consecutive threshold in pixels
   *
   * @param threshold The consecutive threshold in pixels
   */
  public void setConsecutiveThreshold(int threshold) {
    preferences.edit().putInt(CONSECUTIVE_THRESHOLD, threshold).apply();
  }

  /**
   * Get the downsample factor
   *
   * @return The downsample factor
   */
  public int getDownsampleFactor() {
    return preferences.getInt(DOWNSAMPLE_FACTOR, DEFAULT_DOWNSAMPLE_FACTOR);
  }

  /**
   * Set the downsample factor
   *
   * @param factor The downsample factor
   */
  public void setDownsampleFactor(int factor) {
    preferences.edit().putInt(DOWNSAMPLE_FACTOR, factor).apply();
  }

  /**
   * Get the depth gradient threshold in millimeters
   *
   * @return The depth gradient threshold in millimeters
   */
  public float getDepthGradientThreshold() {
    return preferences.getFloat(DEPTH_GRADIENT_THRESHOLD, DEFAULT_DEPTH_GRADIENT_THRESHOLD);
  }

  /**
   * Set the depth gradient threshold in millimeters
   *
   * @param thresholdMm The depth gradient threshold in millimeters
   */
  public void setDepthGradientThreshold(float thresholdMm) {
    preferences.edit().putFloat(DEPTH_GRADIENT_THRESHOLD, thresholdMm).apply();
  }

  /**
   * Get the navigability threshold (percentage of obstacles)
   *
   * @return The navigability threshold (percentage of obstacles)
   */
  public int getNavigabilityThreshold() {
    return preferences.getInt(NAVIGABILITY_THRESHOLD, DEFAULT_NAVIGABILITY_THRESHOLD);
  }

  /**
   * Set the navigability threshold (percentage of obstacles)
   *
   * @param threshold The navigability threshold (percentage of obstacles)
   */
  public void setNavigabilityThreshold(int threshold) {
    preferences.edit().putInt(NAVIGABILITY_THRESHOLD, threshold).apply();
  }

  /**
   * Get the confidence threshold for depth processing
   *
   * @return The confidence threshold (0.0-1.0)
   */
  public float getConfidenceThreshold() {
    return preferences.getFloat(CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD);
  }

  /**
   * Set the confidence threshold for depth processing
   *
   * @param threshold The confidence threshold (0.0-1.0)
   */
  public void setConfidenceThreshold(float threshold) {
    preferences.edit().putFloat(CONFIDENCE_THRESHOLD, threshold).apply();
  }

  /**
   * Get the too close threshold in millimeters
   *
   * @return The too close threshold in millimeters
   */
  public float getTooCloseThreshold() {
    return preferences.getFloat(TOO_CLOSE_THRESHOLD, DEFAULT_TOO_CLOSE_THRESHOLD);
  }

  /**
   * Set the too close threshold in millimeters
   *
   * @param thresholdMm The too close threshold in millimeters
   */
  public void setTooCloseThreshold(float thresholdMm) {
    preferences.edit().putFloat(TOO_CLOSE_THRESHOLD, thresholdMm).apply();
  }

  /**
   * Get the selected resolution index for the logger
   *
   * @return The resolution index (0-3 corresponding to the resolution_values array)
   */
  public int getLoggerResolution() {
    return preferences.getInt(LOGGER_RESOLUTION, DEFAULT_LOGGER_RESOLUTION);
  }

  /**
   * Set the selected resolution index for the logger
   *
   * @param resolutionIndex The resolution index (0-3 corresponding to the resolution_values array)
   */
  public void setLoggerResolution(int resolutionIndex) {
    preferences.edit().putInt(LOGGER_RESOLUTION, resolutionIndex).apply();
  }

  /**
   * Get the selected FPS index for the logger
   *
   * @return The FPS index (0-3 corresponding to the fps_values array)
   */
  public int getLoggerFPS() {
    return preferences.getInt(LOGGER_FPS, DEFAULT_LOGGER_FPS);
  }

  /**
   * Set the selected FPS index for the logger
   *
   * @param fpsIndex The FPS index (0-3 corresponding to the fps_values array)
   */
  public void setLoggerFPS(int fpsIndex) {
    preferences.edit().putInt(LOGGER_FPS, fpsIndex).apply();
  }

  /**
   * Get the selected save destination for the logger
   *
   * @return The save destination index (0 for Local Storage, 1 for Google Drive)
   */
  public int getLoggerSaveDestination() {
    return preferences.getInt(LOGGER_SAVE_DESTINATION, DEFAULT_LOGGER_SAVE_DESTINATION);
  }

  /**
   * Set the selected save destination for the logger
   *
   * @param saveDestinationIndex The save destination index (0 for Local Storage, 1 for Google Drive)
   */
  public void setLoggerSaveDestination(int saveDestinationIndex) {
    preferences.edit().putInt(LOGGER_SAVE_DESTINATION, saveDestinationIndex).apply();
  }

  /**
   * Get the selected image source for the logger
   *
   * @return The image source index (0 for ARCore, 1 for Camera, 2 for External Camera)
   */
  public int getLoggerImageSource() {
    return preferences.getInt(LOGGER_IMAGE_SOURCE, DEFAULT_LOGGER_IMAGE_SOURCE);
  }

  /**
   * Set the selected image source for the logger
   *
   * @param imageSourceIndex The image source index (0 for ARCore, 1 for Camera, 2 for External Camera)
   */
  public void setLoggerImageSource(int imageSourceIndex) {
    preferences.edit().putInt(LOGGER_IMAGE_SOURCE, imageSourceIndex).apply();
  }
}
