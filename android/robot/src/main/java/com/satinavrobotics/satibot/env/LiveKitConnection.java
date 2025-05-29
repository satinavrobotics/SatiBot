package com.satinavrobotics.satibot.env;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.satinavrobotics.satibot.R;
import timber.log.Timber;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class PhoneController {
  private static final String TAG = "PhoneController";
  private static PhoneController _phoneController;
  private View view = null;
  private LiveKitServer liveKitServer;



  public static PhoneController getInstance(Context context) {
    if (_phoneController == null) { // Check for the first time

      synchronized (PhoneController.class) { // Check for the second time.
        // if there is no instance available... create new one
        if (_phoneController == null) _phoneController = new PhoneController();
        _phoneController.init(context);
      }
    }

    return _phoneController;
  }

  @SuppressLint("SuspiciousIndentation")
  private void init(Context context) {
    ControllerConfig config = ControllerConfig.getInstance();
    config.init(context);
    String videoServerType = config.getVideoServerType();

    Timber.d("Video server type: %s", videoServerType);

    if ("LiveKit".equals(videoServerType))
      try {
        liveKitServer = new LiveKitServer(context);
        view = new io.livekit.android.renderer.SurfaceViewRenderer(context);
        addVideoView(view, context);
      } catch (Exception e) {
        e.printStackTrace();
      }
  }

  private void addVideoView(View videoView, Context context) {
    ViewGroup viewGroup = (ViewGroup) ((Activity) context).getWindow().getDecorView();

    ViewGroup.LayoutParams layoutParams =
        new ViewGroup.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
    videoView.setLayoutParams(layoutParams);
    videoView.setId(R.id.video_window);
    videoView.setAlpha(0f);
    viewGroup.addView(videoView, 0); // send to back

    if (videoView instanceof io.livekit.android.renderer.SurfaceViewRenderer) {
      liveKitServer.setView((io.livekit.android.renderer.SurfaceViewRenderer) videoView);
    }
  }

  public void connectLiveKitServer() {
    if (!liveKitServer.isConnected())
      liveKitServer.connect();
  }

  public void disconnectLiveKitServer() {
    Timber.d("Disconnecting from LiveKit server");
    if (liveKitServer.isConnected())
      liveKitServer.disconnect();
  }

  private PhoneController() {
    if (_phoneController != null) {
      throw new RuntimeException(
          "Use getInstance() method to get the single instance of this class.");
    }
  }
}
