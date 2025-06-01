package com.satinavrobotics.satibot.depth;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.depth.depth_sources.DepthImageGenerator;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;

import java.util.Arrays;

public class NavMapOverlay {
    private final DepthImageGenerator depthImageGenerator;
    private final DepthProcessor depthProcessor;
    private LinearLayout rowsContainer;
    private View robotBoundsOverlay;
    private View boundsContainer;
    private View leftLine;
    private View rightLine;
    private final int numRows = 12;
    private final int rowHeight = 16;
    private float topPercentage = 0.7f;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Track initialization state
    private boolean initialized = false;

    // Array to store the navigability status of each row
    private final boolean[] rowNavigability = new boolean[numRows];

    // Listener for navigability updates
    private NavigabilityListener navigabilityListener;

    public NavMapOverlay(DepthImageGenerator depthImageGenerator, DepthProcessor depthProcessor) {
        this.depthImageGenerator = depthImageGenerator;
        this.depthProcessor = depthProcessor;

        // Initialize row navigability array with all false values
        Arrays.fill(rowNavigability, false);
    }

    /**
     * Interface for receiving navigability updates
     */
    public interface NavigabilityListener {
        /**
         * Called when navigability data is updated
         * @param navigabilityData Array of boolean values indicating if each row is navigable
         */
        void onNavigabilityUpdated(boolean[] navigabilityData);
    }

    /**
     * Set a listener for navigability updates
     * @param listener The listener to receive updates
     */
    public void setNavigabilityListener(NavigabilityListener listener) {
        this.navigabilityListener = listener;
    }

    /**
     * Get the current navigability status for all rows
     * @return Array of boolean values indicating if each row is navigable
     */
    public boolean[] getRowNavigability() {
        // Return a copy to prevent external modification
        boolean[] copy = new boolean[numRows];
        System.arraycopy(rowNavigability, 0, copy, 0, numRows);
        return copy;
    }

    /**
     * Get the number of rows in the navigation map
     * @return Number of rows
     */
    public int getNumRows() {
        return numRows;
    }
    /**
     * Check if the NavMapOverlay has been initialized
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Initialize the NavMapOverlay with the root view
     * @param rootView The root view containing the navigation map elements
     */
    public void initialize(View rootView) {
        rowsContainer = rootView.findViewById(R.id.nav_map_rows_container);
        if (rowsContainer == null) {
            return;
        }

        robotBoundsOverlay = rootView.getRootView().findViewById(R.id.robotBoundsOverlay);
        if (robotBoundsOverlay != null) {
            boundsContainer = robotBoundsOverlay.findViewById(R.id.robot_bounds_container);
            leftLine = robotBoundsOverlay.findViewById(R.id.robot_left_line);
            rightLine = robotBoundsOverlay.findViewById(R.id.robot_right_line);
        }

        FrameLayout.LayoutParams containerParams = (FrameLayout.LayoutParams) rowsContainer.getLayoutParams();
        containerParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        containerParams.bottomMargin = 0;
        rowsContainer.setLayoutParams(containerParams);

        createNavigationRows();

        // Mark as initialized
        initialized = true;
    }

    private void createNavigationRows() {
        if (rowsContainer == null) {
            return;
        }

        rowsContainer.removeAllViews();

        Context context = rowsContainer.getContext();
        for (int i = 0; i < numRows; i++) {
            View rowView = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
            params.bottomMargin = 1;

            rowView.setLayoutParams(params);
            rowView.setBackgroundColor(Color.argb(200, 128, 128, 128));
            rowView.setVisibility(View.VISIBLE);

            rowsContainer.addView(rowView, 0);
        }

        rowsContainer.setVisibility(View.VISIBLE);
    }

    /**
     * Update the navigation map with the latest depth data
     */
    public void update() {
        // Skip if not initialized or missing components
        if (!initialized || depthImageGenerator == null || depthProcessor == null || rowsContainer == null) {
            return;
        }

        try {
            // Get depth dimensions
            int depthWidth = depthImageGenerator.getWidth();
            int depthHeight = depthImageGenerator.getHeight();

            if (depthWidth <= 0 || depthHeight <= 0) {
                return;
            }

            // Get gradient information
            final boolean[][] closerNextInfo = depthProcessor.getCloserNextPixelInfo();
            if (closerNextInfo == null) {
                return;
            }

            final boolean[][] horizontalGradientInfo = depthProcessor.getHorizontalGradientInfo();
            if (horizontalGradientInfo == null) {
                return;
            }

            // Validate dimensions
            final int closerNextHeight = closerNextInfo.length;
            final int closerNextWidth = closerNextHeight > 0 ? closerNextInfo[0].length : 0;

            if (closerNextWidth == 0) {
                return;
            }

            if (horizontalGradientInfo.length != closerNextHeight || horizontalGradientInfo[0].length != closerNextWidth) {
                return;
            }

            float[] boundsRelative = RobotParametersManager.getInstance().calculateRobotBoundsRelative();
            final float leftXRatio = boundsRelative[0];
            final float rightXRatio = boundsRelative[1];

            final int closerNextLeftX = Math.max(0, Math.round(leftXRatio * closerNextWidth));
            final int closerNextRightX = Math.min(closerNextWidth - 1, Math.round(rightXRatio * closerNextWidth));

            final int bottomY = closerNextHeight - 1;
            final int topY = (int)(closerNextHeight * (1 - topPercentage));

            final int rowHeight = (bottomY - topY) / numRows;

            final int navigabilityThreshold = RobotParametersManager.getInstance().getNavigabilityThreshold();
            final float freeThreshold = (100 - navigabilityThreshold) / 100.0f;

            Runnable updateRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int row = 0; row < numRows; row++) {
                            View rowView = rowsContainer.getChildAt(row);
                            if (rowView == null) continue;

                            int rowTopY = topY + row * rowHeight;
                            int rowBottomY = Math.min(rowTopY + rowHeight, closerNextHeight - 1);

                            int obstacleCount = 0;
                            int totalPixels = 0;

                            for (int x = closerNextLeftX; x <= closerNextRightX; x++) {
                                for (int y = rowTopY; y <= rowBottomY; y++) {
                                    if (y < closerNextInfo.length && x < closerNextInfo[y].length) {
                                        totalPixels++;
                                        if (closerNextInfo[y][x] || horizontalGradientInfo[y][x]) {
                                            obstacleCount++;
                                        }
                                    }
                                }
                            }

                            float freePixelRatio = totalPixels > 0 ? 1.0f - ((float)obstacleCount / totalPixels) : 0;
                            boolean isNavigable = freePixelRatio >= freeThreshold;

                            // Store the navigability status for this row
                            rowNavigability[row] = isNavigable;

                            int color;
                            if (isNavigable) {
                                float dangerRatio = Math.max(0, Math.min(1, 1.0f - (freePixelRatio / freeThreshold)));
                                float red = Math.min(0.8f, dangerRatio * 0.8f) * 255;
                                float green = 0.8f * 255;
                                color = Color.argb(204, (int)red, (int)green, 0);
                            } else {
                                float dangerRatio = Math.max(0, Math.min(1, 1.0f - (freePixelRatio / freeThreshold)));
                                float excess = Math.min(1.0f, (dangerRatio - 1.0f) * 2.0f);
                                float red = (0.8f + (excess * 0.2f)) * 255;
                                float green = Math.max(0.0f, 0.4f * (1.0f - excess)) * 255;
                                color = Color.argb(204, (int)red, (int)green, 0);
                            }

                            rowView.setBackgroundColor(color);
                        }

                        // Notify listener about navigability update
                        if (navigabilityListener != null) {
                            navigabilityListener.onNavigabilityUpdated(getRowNavigability());
                        }
                    } catch (Exception e) {
                        // Silently handle exceptions
                    }
                }
            };

            mainHandler.post(updateRunnable);

        } catch (Exception e) {
            // Silently handle exceptions
        }
    }

    public void setRobotWidthMeters(float widthMeters) {
        if (Float.isNaN(widthMeters) || Float.isInfinite(widthMeters)) {
            widthMeters = 0.4f;
        }
        RobotParametersManager.getInstance().setRobotWidthMeters(widthMeters);
    }

    public void setNavigabilityThreshold(int threshold) {
        RobotParametersManager.getInstance().setNavigabilityThreshold(threshold);
    }

    public void updateRobotBounds(CameraIntrinsics cameraIntrinsics, boolean isVisible) {
        if (cameraIntrinsics != null) {
            RobotParametersManager.getInstance().setCameraIntrinsics(cameraIntrinsics);
        }

        float[] boundsRelative = RobotParametersManager.getInstance().calculateRobotBoundsRelative();
        float leftXRatio = boundsRelative[0];
        float rightXRatio = boundsRelative[1];

        updateContainerPosition(leftXRatio, rightXRatio, isVisible);
        updateRobotBoundsOverlay(leftXRatio, rightXRatio, isVisible);
    }

    private void updateRobotBoundsOverlay(final float leftXRatio, final float rightXRatio, final boolean isVisible) {
        if (robotBoundsOverlay == null || boundsContainer == null || leftLine == null || rightLine == null) {
            return;
        }

        Runnable updateRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Always make the robot bounds overlay visible for AutonomousNavigationFragment
                    robotBoundsOverlay.setVisibility(View.VISIBLE);

                    // We don't return early even if isVisible is false
                    // This ensures the bounds are always updated and shown

                    int screenWidth = robotBoundsOverlay.getWidth();
                    if (screenWidth <= 0) {
                        return;
                    }

                    int leftX = Math.round(leftXRatio * screenWidth);
                    int rightX = Math.round(rightXRatio * screenWidth);

                    ViewGroup.MarginLayoutParams leftParams = (ViewGroup.MarginLayoutParams) leftLine.getLayoutParams();
                    if (leftParams.width != 15 || leftParams.height != 200) {
                        leftParams.leftMargin = 0;
                        leftParams.width = 15;
                        leftParams.height = 200;
                        leftLine.setLayoutParams(leftParams);
                    }

                    ViewGroup.MarginLayoutParams rightParams = (ViewGroup.MarginLayoutParams) rightLine.getLayoutParams();
                    if (rightParams.width != 15 || rightParams.height != 200) {
                        rightParams.rightMargin = 0;
                        rightParams.width = 15;
                        rightParams.height = 200;
                        rightLine.setLayoutParams(rightParams);
                    }

                    ViewGroup.MarginLayoutParams containerParams = (ViewGroup.MarginLayoutParams) boundsContainer.getLayoutParams();
                    if (containerParams.leftMargin != leftX || containerParams.rightMargin != screenWidth - rightX) {
                        containerParams.leftMargin = leftX;
                        containerParams.rightMargin = screenWidth - rightX;
                        boundsContainer.setLayoutParams(containerParams);
                    }
                } catch (Exception e) {
                    // Silently handle exceptions
                }
            }
        };

        mainHandler.post(updateRunnable);
    }

    private void updateContainerPosition(final float leftXRatio, final float rightXRatio, final boolean isVisible) {
        if (rowsContainer == null) {
            return;
        }

        Runnable updateRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Always make the navigation rows container visible for AutonomousNavigationFragment
                    rowsContainer.setVisibility(View.VISIBLE);

                    // We don't return early even if isVisible is false
                    // This ensures the navigation map is always updated and shown

                    int screenWidth = robotBoundsOverlay != null ?
                        robotBoundsOverlay.getWidth() : rowsContainer.getRootView().getWidth();
                    if (screenWidth <= 0) {
                        return;
                    }

                    int leftX = Math.round(leftXRatio * screenWidth);
                    int rightX = Math.round(rightXRatio * screenWidth);
                    int robotWidth = rightX - leftX;

                    rowsContainer.setBackgroundColor(Color.TRANSPARENT);

                    FrameLayout.LayoutParams containerParams = (FrameLayout.LayoutParams) rowsContainer.getLayoutParams();
                    containerParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    containerParams.bottomMargin = 0;
                    containerParams.width = robotWidth;
                    rowsContainer.setLayoutParams(containerParams);

                    for (int i = 0; i < rowsContainer.getChildCount(); i++) {
                        View rowView = rowsContainer.getChildAt(i);
                        if (rowView != null) {
                            ViewGroup.LayoutParams rowParams = rowView.getLayoutParams();
                            rowParams.width = robotWidth;
                            rowView.setLayoutParams(rowParams);

                            if (rowView.getBackground() == null) {
                                rowView.setBackgroundColor(Color.argb(200, 128, 128, 128));
                            }
                            rowView.setVisibility(View.VISIBLE);
                        }
                    }
                } catch (Exception e) {
                    // Silently handle exceptions
                }
            }
        };

        mainHandler.post(updateRunnable);
    }

    public void updateContainerPosition(float leftXRatio, float rightXRatio) {
        updateContainerPosition(leftXRatio, rightXRatio, true);
    }
}
