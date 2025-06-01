package com.satinavrobotics.satibot.arcore;

import com.google.ar.core.Pose;
import com.google.ar.core.TrackingFailureReason;

import livekit.org.webrtc.VideoFrame;

public interface ArCoreListener {

  void onArCoreUpdate(
      Pose currenPose,
      ImageFrame frame,
      CameraIntrinsics cameraIntrinsics,
      long timestamp);

  void onRenderedFrame(VideoFrame.I420Buffer frame, long timestamp);

  void onArCoreTrackingFailure(long timestamp, TrackingFailureReason trackingFailureReason);

  void onArCoreSessionPaused(long timestamp);

}
