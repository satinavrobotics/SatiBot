package com.satinavrobotics.satibot.main;

import java.util.ArrayList;
import org.jetbrains.annotations.NotNull;
import com.satinavrobotics.satibot.R;

import com.satinavrobotics.satibot.main.model.Category;
import com.satinavrobotics.satibot.main.model.SubCategory;

public class FeatureList {
  // region Properties

  // Global
  public static final String ALL = "All";
  public static final String MANUAL = "Manual";
  public static final String AUTONOMOUS = "Autonomous";
  public static final String MANAGEMENT = "Management";
  public static final String EXPERIMENTAL = "Experimental";
  public static final String DEFAULT = "Default";
  public static final String PROJECTS = "Projects";
  public static final String CONTROLLER = "Controller";
  public static final String CONTROLLER_MAPPING = "Controller Mapping";
  public static final String ROBOT_INFO = "Robot Info";

  // Game
  public static final String GAME = "Game";
  public static final String REMOTE_CONTROL = "Remote Control";
  public static final String AR_MODE = "AR Mode";
  public static final String LOCAL_CONTROL = "Local Control";

  // Data Collection
  public static final String GAMEPAD_CONTROL = "GamePad Control";
  public static final String LOCAL_SAVE_ON_PHONE = "Local (save On Phone)";
  public static final String EDGE_LOCAL_NETWORK = "Edge (local Network)";
  public static final String CLOUD_FIREBASE = "Cloud (firebase)";
  public static final String CROWD_SOURCE = "Crowd-source (post/accept Data Collection Tasks)";

  // AI
  public static final String AI = "AI";
  public static final String PERSON_FOLLOWING = "Person Following";
  public static final String MODEL_MANAGEMENT = "Model Management";
  public static final String POINT_GOAL_NAVIGATION = "Point Goal Navigation";
  public static final String AUTONOMOUS_DRIVING = "Autonomous Driving";
  public static final String VISUAL_GOALS = "Visual Goals";
  public static final String SMART_VOICE = "Smart Voice (left/right/straight, Ar Core)";

  // Remote Access
  public static final String REMOTE_ACCESS = "Remote Access";
  public static final String WEB_INTERFACE = "Web Interface";
  public static final String ROS = "ROS";
  public static final String FLEET_MANAGEMENT = "Fleet Management";

  // Coding
  public static final String CODING = "Coding";
  public static final String BLOCK_BASED_PROGRAMMING = "Block-Based Programming";
  public static final String SCRIPTS = "Scripts";

  // Research
  public static final String RESEARCH = "Research";
  public static final String CLASSICAL_ROBOTICS_ALGORITHMS = "Classical Robotics Algorithms";
  public static final String BACKEND_FOR_LEARNING = "Backend For Learning";

  // Monitoring
  public static final String MONITORING = "Monitoring";
  public static final String SENSORS_FROM_CAR = "Sensors from Car";
  public static final String SENSORS_FROM_PHONE = "Sensors from Phone";
  public static final String MAP_VIEW = "Map View";

  // Maps
  public static final String MAPS = "Maps";
  public static final String MAP_MANAGEMENT = "Map Management";
  public static final String POINTCLOUD_MAPPING = "Pointcloud Mapping";
  public static final String DEPTH_MANAGEMENT = "Depth Management";
  public static final String DEPTH_NAVIGATION = "Depth Navigation";
  public static final String PD_TUNING = "PD Tuning";
  // Removed MAP_RESOLVE as it's now handled from MapManagementFragment
  // endregion

  @NotNull
  public static ArrayList<Category> getCategories() {
    ArrayList<Category> categories = new ArrayList<>();

    ArrayList<SubCategory> subCategories;

    // First row - Manual
    subCategories = new ArrayList<>();
    subCategories.add(new SubCategory(LOCAL_CONTROL, R.drawable.ic_game, "#FFFF6D00"));
    subCategories.add(new SubCategory(GAMEPAD_CONTROL, R.drawable.ic_controller, "#7268A6"));
    subCategories.add(new SubCategory(REMOTE_CONTROL, R.drawable.ic_wifi, "#93C47D"));
    categories.add(new Category(MANUAL, subCategories));

    // Second row - Autonomous
    subCategories = new ArrayList<>();
    subCategories.add(new SubCategory(DEPTH_NAVIGATION, R.drawable.ic_depth_navigation, "#0071C5"));
    categories.add(new Category(AUTONOMOUS, subCategories));

    // Third row - Management
    subCategories = new ArrayList<>();
    subCategories.add(new SubCategory(MAP_MANAGEMENT, R.drawable.ic_map, "#4CAF50"));
    subCategories.add(new SubCategory(PD_TUNING, R.drawable.ic_controller, "#FF9800"));
    subCategories.add(new SubCategory(DEPTH_MANAGEMENT, R.drawable.ic_depth_management, "#673AB7"));
    subCategories.add(new SubCategory(MODEL_MANAGEMENT, R.drawable.ic_list_bulleted_48, "#BC7680"));
    categories.add(new Category(MANAGEMENT, subCategories));

    // Fourth row - Experimental
    subCategories = new ArrayList<>();
    subCategories.add(new SubCategory(POINTCLOUD_MAPPING, R.drawable.ic_pointcloud_mapping, "#3F51B5"));
    categories.add(new Category(EXPERIMENTAL, subCategories));

    /*
        subCategories = new ArrayList<>();
        subCategories.add(new SubCategory(SMART_VOICE, R.drawable.ic_voice_over));
        subCategories.add(new SubCategory(VISUAL_GOALS, R.drawable.openbot_icon));
        categories.add(new Category(AI, subCategories));

        subCategories = new ArrayList<>();
        subCategories.add(new SubCategory(CONTROLLER, R.drawable.ic_controller));
        subCategories.add(new SubCategory(FREE_ROAM, R.drawable.ic_game, "#FFFF6D00"));
        subCategories.add(new SubCategory(AR_MODE, R.drawable.ic_game, "#B3FF6D00"));
        categories.add(new Category(GAME, subCategories));

        subCategories = new ArrayList<>();
        subCategories.add(new SubCategory(LOCAL_SAVE_ON_PHONE, R.drawable.ic_storage, "#93C47D"));
        subCategories.add(new SubCategory(EDGE_LOCAL_NETWORK, R.drawable.ic_network));
        subCategories.add(new SubCategory(CLOUD_FIREBASE, R.drawable.ic_cloud_upload));
        subCategories.add(new SubCategory(CROWD_SOURCE, R.drawable.openbot_icon));
        categories.add(new Category(DATA_COLLECTION, subCategories));

        subCategories = new ArrayList<>();
        subCategories.add(new SubCategory(WEB_INTERFACE, R.drawable.openbot_icon));
        subCategories.add(new SubCategory(ROS, R.drawable.openbot_icon));
        subCategories.add(new SubCategory(FLEET_MANAGEMENT, R.drawable.openbot_icon));
        categories.add(new Category(REMOTE_ACCESS, subCategories));

        subCategories = new ArrayList<>();
        subCategories.add(new SubCategory(BLOCK_BASED_PROGRAMMING, R.drawable.ic_code));
        subCategories.add(new SubCategory(SCRIPTS, R.drawable.ic_code));
        categories.add(new Category(CODING, subCategories));

        subCategories = new ArrayList<>();
        subCategories.add(
            new SubCategory(CLASSICAL_ROBOTICS_ALGORITHMS, R.drawable.openbot_icon));
        subCategories.add(new SubCategory(BACKEND_FOR_LEARNING, R.drawable.openbot_icon));
        categories.add(new Category(RESEARCH, subCategories));

        subCategories = new ArrayList<>();
        subCategories.add(new SubCategory(SENSORS_FROM_CAR, R.drawable.ic_electric_car));
        subCategories.add(new SubCategory(SENSORS_FROM_PHONE, R.drawable.ic_phonelink));
        subCategories.add(new SubCategory(MAP_VIEW, R.drawable.ic_map));
        categories.add(new Category(MONITORING, subCategories));
    */

    return categories;
  }
}
