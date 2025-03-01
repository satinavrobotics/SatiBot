package org.openbot.env;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.provider.MediaStore;
import android.view.SurfaceView;
import android.view.TextureView;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;

public interface IVideoServer {
  void setResolution(int w, int h);

  void setConnected(boolean connected);

  void init(Context context, VideoCapturer video);

  boolean isRunning();

  void startClient();

  void sendServerUrl();

  void sendVideoStoppedStatus();

  void setView(SurfaceView view);

  void setView(TextureView view);

  void setView(SurfaceViewRenderer view);

  void setView(com.pedro.rtplibrary.view.OpenGlView view);

  void setCanStart(boolean canStart);

}
