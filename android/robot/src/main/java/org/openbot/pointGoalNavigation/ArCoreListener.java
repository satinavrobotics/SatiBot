package org.openbot.pointGoalNavigation;

import android.view.ViewDebug;

import com.google.ar.core.TrackingFailureReason;

import java.nio.ByteBuffer;

import livekit.org.webrtc.VideoFrame;

public interface ArCoreListener {

  void onArCoreUpdate(
      NavigationPoses navigationPoses,
      ImageFrame frame,
      CameraIntrinsics cameraIntrinsics,
      long timestamp);

  void onRenderedFrame(VideoFrame.I420Buffer frame, long timestamp);

  void onArCoreTrackingFailure(long timestamp, TrackingFailureReason trackingFailureReason);

  void onArCoreSessionPaused(long timestamp);

}
