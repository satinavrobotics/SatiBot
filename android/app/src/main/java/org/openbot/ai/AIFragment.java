package org.openbot.ai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.openbot.R;
import org.openbot.common.Constants;
import org.openbot.databinding.FragmentAiBinding;
import org.openbot.env.BorderedText;
import org.openbot.env.Control;
import org.openbot.env.ImageUtils;
import org.openbot.robot.CameraFragment;
import org.openbot.robot.ServerCommunication;
import org.openbot.tflite.Autopilot;
import org.openbot.tflite.Detector;
import org.openbot.tflite.Model;
import org.openbot.tflite.Network;
import org.openbot.tracking.MultiBoxTracker;
import timber.log.Timber;

public class AIFragment extends CameraFragment implements ServerCommunication.ServerListener {

  private FragmentAiBinding binding;
  private Handler handler;
  private HandlerThread handlerThread;
  private ServerCommunication serverCommunication;

  private long lastProcessingTimeMs;
  private boolean computingNetwork = false;
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;

  private static final float TEXT_SIZE_DIP = 10;

  private Detector detector;
  private Autopilot autoPilot;

  private Matrix frameToCropTransform;
  private Bitmap croppedBitmap;
  private int sensorOrientation;
  private Bitmap cropCopyBitmap;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private Model model = Model.DETECTOR_V1_1_0_Q;
  private Network.Device device = Network.Device.CPU;
  private int numThreads = -1;

  private ArrayAdapter<CharSequence> modelAdapter;

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    // Inflate the layout for this fragment
    binding = FragmentAiBinding.inflate(inflater, container, false);

    return inflateFragment(binding, inflater, container);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    binding.controllerContainer.speedInfo.setText(getString(R.string.speedInfo, "---,---"));

    binding.controllerContainer.controlMode.setEnabled(false);
    binding.controllerContainer.driveMode.setEnabled(false);
    binding.controllerContainer.speedInfo.setEnabled(false);

    binding.deviceSpinner.setSelection(preferencesManager.getDevice());
    setNumThreads(preferencesManager.getNumThreads());

    binding.cameraToggle.setOnClickListener(v -> toggleCamera());

    List<CharSequence> models = Arrays.asList(getResources().getTextArray(R.array.models));
    modelAdapter =
        new ArrayAdapter<>(requireContext(), R.layout.spinner_item, new ArrayList<>(models));
    modelAdapter.addAll(getModelFiles());
    modelAdapter.setDropDownViewResource(android.R.layout.simple_list_item_checked);
    binding.modelSpinner.setAdapter(modelAdapter);

    binding.modelSpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String selected = parent.getItemAtPosition(position).toString();
            setModel(Model.fromId(selected.toUpperCase()));
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
    binding.deviceSpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            String selected = parent.getItemAtPosition(position).toString();
            setDevice(Network.Device.valueOf(selected.toUpperCase()));
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });

    binding.plus.setOnClickListener(
        v -> {
          String threads = binding.threads.getText().toString().trim();
          int numThreads = Integer.parseInt(threads);
          if (numThreads >= 9) return;
          setNumThreads(++numThreads);
          binding.threads.setText(String.valueOf(numThreads));
        });
    binding.minus.setOnClickListener(
        v -> {
          String threads = binding.threads.getText().toString().trim();
          int numThreads = Integer.parseInt(threads);
          if (numThreads == 1) return;
          setNumThreads(--numThreads);
          binding.threads.setText(String.valueOf(numThreads));
        });
    BottomSheetBehavior.from(binding.loggerBottomSheet)
        .setState(BottomSheetBehavior.STATE_EXPANDED);
  }

  private void updateCropImageInfo() {
    //    Timber.i("%s x %s",getPreviewSize().getWidth(), getPreviewSize().getHeight());
    //    Timber.i("%s x %s",getMaxAnalyseImageSize().getWidth(),
    // getMaxAnalyseImageSize().getHeight());
    frameToCropTransform = null;

    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());

    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    BorderedText borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(requireContext());

    Timber.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    recreateNetwork(getModel(), getDevice(), getNumThreads());
    if (detector == null && autoPilot == null) {
      Timber.e("No network on preview!");
      return;
    }

    binding.trackingOverlay.addCallback(
        canvas -> {
          tracker.draw(canvas);
          //          tracker.drawDebug(canvas);
        });
    tracker.setFrameConfiguration(
        getMaxAnalyseImageSize().getWidth(),
        getMaxAnalyseImageSize().getHeight(),
        sensorOrientation);
  }

  protected void onInferenceConfigurationChanged() {
    computingNetwork = false;
    if (croppedBitmap == null) {
      // Defer creation until we're getting camera frames.
      return;
    }
    final Network.Device device = getDevice();
    final Model model = getModel();
    final int numThreads = getNumThreads();
    runInBackground(() -> recreateNetwork(model, device, numThreads));
  }

  private void recreateNetwork(Model model, Network.Device device, int numThreads) {
    tracker.clearTrackedObjects();
    if (detector != null) {
      Timber.d("Closing detector.");
      detector.close();
      detector = null;
    }
    if (autoPilot != null) {
      Timber.d("Closing autoPilot.");
      autoPilot.close();
      autoPilot = null;
    }

    try {
      if (model == Model.DETECTOR_V1_1_0_Q || model == Model.DETECTOR_V3_S_Q) {
        Timber.d(
            "Creating detector (model=%s, device=%s, numThreads=%d)", model, device, numThreads);
        detector = Detector.create(requireActivity(), model, device, numThreads);
        croppedBitmap =
            Bitmap.createBitmap(
                detector.getImageSizeX(), detector.getImageSizeY(), Bitmap.Config.ARGB_8888);
        frameToCropTransform =
            ImageUtils.getTransformationMatrix(
                getMaxAnalyseImageSize().getWidth(),
                getMaxAnalyseImageSize().getHeight(),
                croppedBitmap.getWidth(),
                croppedBitmap.getHeight(),
                sensorOrientation,
                detector.getCropRect(),
                detector.getMaintainAspect());
      } else {
        Timber.d(
            "Creating autopilot (model=%s, device=%s, numThreads=%d)", model, device, numThreads);
        autoPilot = Autopilot.create(requireActivity(), model, device, numThreads);
        croppedBitmap =
            Bitmap.createBitmap(
                autoPilot.getImageSizeX(), autoPilot.getImageSizeY(), Bitmap.Config.ARGB_8888);
        frameToCropTransform =
            ImageUtils.getTransformationMatrix(
                getMaxAnalyseImageSize().getWidth(),
                getMaxAnalyseImageSize().getHeight(),
                croppedBitmap.getWidth(),
                croppedBitmap.getHeight(),
                sensorOrientation,
                autoPilot.getCropRect(),
                autoPilot.getMaintainAspect());
      }

      cropToFrameTransform = new Matrix();
      frameToCropTransform.invert(cropToFrameTransform);

    } catch (IllegalArgumentException | IOException e) {
      String msg = "Failed to create network.";
      Timber.e(e, msg);
      Toast.makeText(requireContext().getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public synchronized void onResume() {
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    serverCommunication = new ServerCommunication(requireContext(), this);
    serverCommunication.start();
  }

  @Override
  public synchronized void onPause() {

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
      serverCommunication.stop();
    } catch (final InterruptedException e) {
    }

    super.onPause();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  protected void processUSBData(String data) {}

  @Override
  protected void processControllerKeyData(String commandType) {
    switch (commandType) {
      case Constants.CMD_DRIVE:
        //        handleDriveCommand();
        break;

      case Constants.CMD_LOGS:
        break;
        //      case "Constants.CMD_NOISE":
        //        handleNoise();
        //        break;
      case Constants.CMD_INDICATOR_LEFT:
      case Constants.CMD_INDICATOR_RIGHT:

      case Constants.CMD_NETWORK:
        setNetworkEnabled(!binding.autoSwitch.isChecked());
        break;
    }
  }

  private void setNetworkEnabled(boolean b) {
    runInBackground(
        () -> {
          try {
            TimeUnit.MILLISECONDS.sleep(lastProcessingTimeMs);
            vehicle.setControl(0, 0);
            requireActivity().runOnUiThread(() -> binding.inferenceInfo.setText(R.string.time_ms));
          } catch (InterruptedException e) {
            Timber.e(e, "Got interrupted.");
          }
        });
  }

  private long frameNum = 0;

  @Override
  protected void processFrame(Bitmap bitmap, ImageProxy image) {
    if (tracker == null) updateCropImageInfo();

    ++frameNum;
    if (binding != null && binding.autoSwitch.isChecked()) {
      // If network is busy, return.
      if (computingNetwork) {
        return;
      }

      computingNetwork = true;
      Timber.i("Putting image " + frameNum + " for detection in bg thread.");

      runInBackground(
          () -> {
            final Canvas canvas = new Canvas(croppedBitmap);
            canvas.drawBitmap(bitmap, frameToCropTransform, null);

            if (detector != null) {
              Timber.i("Running detection on image %s", frameNum);
              final long startTime = SystemClock.elapsedRealtime();
              final List<Detector.Recognition> results = detector.recognizeImage(croppedBitmap);
              lastProcessingTimeMs = SystemClock.elapsedRealtime() - startTime;

              if (!results.isEmpty())
                Timber.i(
                    "Object: "
                        + results.get(0).getLocation().centerX()
                        + ", "
                        + results.get(0).getLocation().centerY()
                        + ", "
                        + results.get(0).getLocation().height()
                        + ", "
                        + results.get(0).getLocation().width());

              cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
              final Canvas canvas1 = new Canvas(cropCopyBitmap);
              final Paint paint = new Paint();
              paint.setColor(Color.RED);
              paint.setStyle(Paint.Style.STROKE);
              paint.setStrokeWidth(2.0f);

              float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;

              final List<Detector.Recognition> mappedRecognitions = new LinkedList<>();

              for (final Detector.Recognition result : results) {
                final RectF location = result.getLocation();
                if (location != null && result.getConfidence() >= minimumConfidence) {
                  canvas1.drawRect(location, paint);
                  cropToFrameTransform.mapRect(location);
                  result.setLocation(location);
                  mappedRecognitions.add(result);
                }
              }

              tracker.trackResults(mappedRecognitions, frameNum);
              handleDriveCommand(tracker.updateTarget());
              binding.trackingOverlay.postInvalidate();
            } else if (autoPilot != null) {
              Timber.i("Running autopilot on image %s", frameNum);
              final long startTime = SystemClock.elapsedRealtime();
              handleDriveCommand(autoPilot.recognizeImage(croppedBitmap, vehicle.getIndicator()));
              lastProcessingTimeMs = SystemClock.elapsedRealtime() - startTime;
            }

            computingNetwork = false;
          });
      requireActivity()
          .runOnUiThread(
              () ->
                  binding.inferenceInfo.setText(
                      String.format(Locale.US, "%d ms", lastProcessingTimeMs)));
    }
  }

  protected void handleDriveCommand(Control control) {
    vehicle.setControl(control);
    float left = vehicle.getLeftSpeed();
    float right = vehicle.getRightSpeed();
    binding.controllerContainer.controlInfo.setText(
        String.format(Locale.US, "%.0f,%.0f", left, right));
  }

  @Override
  public void onConnectionEstablished(String ipAddress) {}

  @Override
  public void onAddModel(String model) {
    if (modelAdapter != null && modelAdapter.getPosition(model) == -1) {
      modelAdapter.add(model);
    } else {
      if (model.equals(binding.modelSpinner.getSelectedItem())) {
        setModel(new Model(model));
      }
    }
    Toast.makeText(
            requireContext().getApplicationContext(), "Model added: " + model, Toast.LENGTH_SHORT)
        .show();
  }

  @Override
  public void onRemoveModel(String model) {
    if (modelAdapter != null && modelAdapter.getPosition(model) != -1) {
      modelAdapter.remove(model);
    }
    Toast.makeText(
            requireContext().getApplicationContext(), "Model removed: " + model, Toast.LENGTH_SHORT)
        .show();
  }

  protected Model getModel() {
    return model;
  }

  private void setModel(Model model) {
    if (this.model != model) {
      Timber.d("Updating  model: %s", model);
      this.model = model;
      preferencesManager.setModel(model.toString());
      onInferenceConfigurationChanged();
    }
  }

  protected Network.Device getDevice() {
    return device;
  }

  private void setDevice(Network.Device device) {
    if (this.device != device) {
      Timber.d("Updating  device: %s", device);
      this.device = device;
      final boolean threadsEnabled = device == Network.Device.CPU;
      binding.plus.setEnabled(threadsEnabled);
      binding.minus.setEnabled(threadsEnabled);
      binding.threads.setText(threadsEnabled ? String.valueOf(numThreads) : "N/A");
      if (threadsEnabled) binding.threads.setTextColor(Color.BLACK);
      else binding.threads.setTextColor(Color.GRAY);
      preferencesManager.setDevice(device.ordinal());
      onInferenceConfigurationChanged();
    }
  }

  protected int getNumThreads() {
    return numThreads;
  }

  private void setNumThreads(int numThreads) {
    if (this.numThreads != numThreads) {
      Timber.d("Updating  numThreads: %s", numThreads);
      this.numThreads = numThreads;
      preferencesManager.setNumThreads(numThreads);
      onInferenceConfigurationChanged();
    }
  }

  private String[] getModelFiles() {
    return requireActivity().getFilesDir().list((dir1, name) -> name.endsWith(".tflite"));
  }
}
