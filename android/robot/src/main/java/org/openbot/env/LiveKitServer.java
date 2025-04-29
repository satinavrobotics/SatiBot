package org.openbot.env;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.gson.JsonObject;

import org.json.JSONException;
import org.json.JSONObject;
import org.openbot.utils.ConnectionUtils;

import java.util.concurrent.Executors;

import io.livekit.android.ConnectOptions;
import io.livekit.android.LiveKit;
import io.livekit.android.LiveKitOverrides;
import io.livekit.android.RoomOptions;
import io.livekit.android.room.Room;
import io.livekit.android.room.participant.LocalParticipant;
import io.livekit.android.room.track.CameraPosition;
import io.livekit.android.room.track.LocalVideoTrack;
import io.livekit.android.room.track.LocalVideoTrackOptions;
import io.livekit.android.room.track.Track;
import io.livekit.android.renderer.SurfaceViewRenderer;
import io.livekit.android.room.track.TrackPublication;
import io.livekit.android.room.track.VideoCaptureParameter;
import kotlin.Result;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Dispatchers;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

public class LiveKitServer  {
    private Context context;
    private Room room;
    private SurfaceViewRenderer videoRenderer;

    private static TokenManager tokenManager;

    private final ConnectOptions connectOptions = new ConnectOptions();

    private final String SERVER_URL = "https://controller.satinavrobotics.com/api/createToken";

    private boolean connected;

    private StatusManager statusManager;

    // Add this as a field in your LiveKitServer class
    private boolean flashlightOn = false;


    // Hardcoded connection parameters

    public LiveKitServer(Context context) throws Exception {
        this.context = context;
        try {
            tokenManager = TokenManager.getInstance(context);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //if (tokenManager.getToken() == null)
        pollForTokenAsync();
        room = LiveKit.INSTANCE.create(context, createRoomOptions(), createOverrides());
        statusManager = StatusManager.getInstance();
    }

    public void pollForTokenAsync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            OkHttpClient client = new OkHttpClient();

            try {
                // Create JSON body
                JSONObject jsonBody = new JSONObject();
                String firebaseEmail = FirebaseAuth.getInstance().getCurrentUser().getEmail();
                jsonBody.put("roomName", firebaseEmail);
                jsonBody.put("participantName", "Android");

                String credentials = Credentials.basic("satiadmin", "poganyindulo");

                // Build request body
                RequestBody body = RequestBody.create(
                        jsonBody.toString(),
                        MediaType.get("application/json; charset=utf-8")
                );

                // Build request
                Request request = new Request.Builder()
                        .url(SERVER_URL)
                        .header("Authorization", credentials)
                        .post(body)
                        .build();

                Timber.d("Request URL: " + SERVER_URL);

                // Execute request
                Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();

                    // Parse the token from the response
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    String token = jsonResponse.optString("token", "not found");
                    String server_url = jsonResponse.optString("server_url", "not found");
                    String expiration_time = jsonResponse.optString("expiration_time", "not found");

                    Timber.d("Token: " + token);
                    Timber.d("Server URL: " + server_url);
                    Timber.d("Expiration time: " + expiration_time);

                    if (!token.equals("not found")) {
                        System.out.println("Token received: " + token);
                        try {
                            int expiration_time_int = !expiration_time.equals("not found") ? Integer.parseInt(expiration_time) : 600_000;
                            tokenManager.saveToken(token, expiration_time_int); // 10 minutes
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    if (!server_url.equals("not found")) {
                        try {
                            tokenManager.saveServerAddress(server_url);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (Exception e) {
                Timber.e(e, "Error getting token");
                e.printStackTrace();
            }
        });
    }

    //Timber.d("Sending message to room: %s", info.toString());

    public void setView(SurfaceViewRenderer view) {
        this.videoRenderer = view;
        room.initVideoRenderer(view);
    }

    public LocalVideoTrackOptions createVideoTrackOptions() {
        int width = 640;
        int height = 320;
        int max_fps = 30;
        return new LocalVideoTrackOptions(
                false, null, CameraPosition.BACK,
                new VideoCaptureParameter(width, height, max_fps)
        );
    }

    public RoomOptions createRoomOptions() {
        return new RoomOptions(true, true, null,
                null, createVideoTrackOptions(), null, null, null, null);
    }

    public LiveKitOverrides createOverrides() {
        LiveKitOverrides overrides = new LiveKitOverrides();
        return overrides;
    }

    /**
     * Connects to the LiveKit room using hardcoded parameters.
     */
    public void connect() {
        // Run the connection on a separate thread to avoid blocking the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Timber.d("ROOM CONNECTION INITIALIZED");
                    if (room.getState() == Room.State.DISCONNECTED) {
                        String ROOM_URL = tokenManager.getServerAddress();
                        String ROOM_TOKEN = tokenManager.getToken();
                        Timber.d(room.getState().toString());
                        room.connect(ROOM_URL, ROOM_TOKEN, connectOptions, new OnRoomConnected());
                    } else {
                        LocalParticipant localParticipant = room.getLocalParticipant();
                        localParticipant.setCameraEnabled(true, new OnCameraConnected());
                        localParticipant.setMicrophoneEnabled(true, new OnMicConnected());
                    }
                    setConnected(true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * Attaches the local video track to the configured renderer.
     *
     * @param videoTrack the local video track to attach.
     */
    private void attachLocalVideo(LocalVideoTrack videoTrack) {
        if (videoRenderer != null) {
            videoTrack.addRenderer(videoRenderer);
        }
    }

    /**
     * Disconnects from the LiveKit room.
     */
    public void disconnect() {
        //room.disconnect();
        LocalParticipant localParticipant = room.getLocalParticipant();
        try {
            localParticipant.setCameraEnabled(false, new BaseContinuation());
            localParticipant.setMicrophoneEnabled(false, new BaseContinuation());
        } catch (Exception e) {
            e.printStackTrace();
        }

        setConnected(false);
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public String getCameraIdsJson() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        JSONObject cameraJson = new JSONObject();

        try {
            String mainId = null, wideId = null, telephotoId = null, frontId = null;

            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float focalLength = (focalLengths != null && focalLengths.length > 0) ? focalLengths[0] : 0f;

                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontId = cameraId;
                } else {
                    Timber.d("Focal length: %f", focalLength);
                    if (focalLength < 2.5) {
                        wideId = cameraId;
                    } else if (focalLength >= 2.5 && focalLength < 6.0) {
                        mainId = cameraId;
                    } else {
                        telephotoId = cameraId;
                    }
                }
            }

            // Put values into JSON
            cameraJson.put("main", mainId);
            cameraJson.put("wide", wideId);
            cameraJson.put("telephoto", telephotoId);
            cameraJson.put("front", frontId);
            Timber.d("Camera IDs: %s", cameraJson.toString());

        } catch (CameraAccessException e) {
            Timber.e("Error accessing camera");
        } catch (JSONException e) {
            Timber.e("Error creating JSON");
        }

        return cameraJson.toString();
    }

    public String switchFlashlight() {
        // First, ensure the device has a flashlight
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Timber.e("Device does not support flashlight");
            return createFlashlightJsonResponse("error", "Device does not support flashlight");
        }

        // Get the CameraManager from the context
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = getRearCameraId(cameraManager);

            if (cameraId != null) {
                // Toggle the flashlight state
                cameraManager.setTorchMode(cameraId, flashlightOn);
                flashlightOn = !flashlightOn;
                String response = flashlightOn ? "on" : "off";
                Timber.d("Flashlight toggled %s", response);
                return createFlashlightJsonResponse("success", "Flashlight toggled " + response);
            } else {
                Timber.e("No camera found to toggle flashlight");
                return createFlashlightJsonResponse("error", "No camera found to toggle flashlight");
            }
        } catch (CameraAccessException e) {
            Timber.e(e, "Error accessing camera for flashlight toggle");
            return createFlashlightJsonResponse("error", "Error accessing camera for flashlight toggle");
        }
    }

    private String getRearCameraId(CameraManager cameraManager) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private String createFlashlightJsonResponse(String status, String message) {
        JsonObject json = new JsonObject();
        json.addProperty(status, message);
        json.addProperty("state", flashlightOn);
        return json.toString();
    }




    public class BaseContinuation implements Continuation<Unit> {
        @Override
        public CoroutineContext getContext() {
            return Dispatchers.getDefault(); // or another appropriate context
        }
        @Override
        public void resumeWith(@NonNull Object result) {
        }
    }

    public class OnRoomConnected extends BaseContinuation {
        @Override
        public void resumeWith(@NonNull Object result) {
            if (result instanceof Result.Failure) {
                Throwable error = ((Result.Failure) result).exception;
                Timber.e(error, "Failed to connect to room");
                return;
            }
            Timber.d("Connected to room successfully");
            LocalParticipant localParticipant = room.getLocalParticipant();
            try {
                localParticipant.setCameraEnabled(true, new OnCameraConnected());
                localParticipant.setMicrophoneEnabled(true, new OnMicConnected());
            } catch (Exception e) {
                e.printStackTrace();
            }


            localParticipant.registerRpcMethod(
                    "switch-camera",  (data, error) -> {
                        // Process the received data
                        Timber.d("Camera switch request received: %s", data.getPayload());
                        try {
                            LocalVideoTrack localTrack = (LocalVideoTrack) localParticipant.getTrackPublication(Track.Source.CAMERA).getTrack();
                            Timber.d("Switching camera2");
                            localTrack.switchCamera(data.getPayload(), null);
                            Timber.d("Switched camera successfully");
                            return "{ 'status': 'success' }";
                        } catch (Error e) {
                            Timber.e(e, "Failed to switch camera");
                            return "{ 'error': 'Failed to switch camera' }";
                        }
                    },
                    new BaseContinuation()
            );

            // cannot access camera, stream already owns it
            //localParticipant.registerRpcMethod(
            //        "switch-flashlight",  (data, error) -> {
            //            // Process the received data
            //            Timber.d("Flashlight switch request received: %s", data.getPayload());
            //            return switchFlashlight();
            //        },
            //        new BaseContinuation()
            //);

            // write camera metadata into attributes
            // does not work for some reason
            //HashMap<String, String> attributes = new HashMap<>();
            //attributes.put("CAMERA_METADATA", getCameraIdsJson());
            //room.getLocalParticipant().updateMetadata(attributes);
            //Timber.d("Updated attributes successfully");

            localParticipant.registerRpcMethod(
                    "client-connected",  (data, error) -> {
                        ControllerToBotEventBus.emitEvent(data.getPayload());
                        return getCameraIdsJson();
                    },
                    new BaseContinuation()
            );

            localParticipant.registerRpcMethod(
                    "drive-cmd",  (data, error) -> {
                        ControllerToBotEventBus.emitEvent(data.getPayload());
                        return "0";
                    },
                    new BaseContinuation()
            );

            localParticipant.registerRpcMethod(
                    "waypoint-cmd",  (data, error) -> {
                        JSONObject event;
                        try {
                            event = new JSONObject(data.getPayload());
                            event.put("command", "WAYPOINTS");
                            ControllerToBotEventBus.emitEvent(event.toString());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        return "0";
                    },
                    new BaseContinuation()
            );


            localParticipant.registerRpcMethod(
                    "cmd",  (data, error) -> {
                        ControllerToBotEventBus.emitEvent(data.getPayload());
                        return "0";
                    },
                    new BaseContinuation()
            );

            localParticipant.registerRpcMethod(
                    "status", (data, error) -> statusManager.getStatus().toString(),
                    new BaseContinuation());
        }
    }

    private class OnCameraConnected extends BaseContinuation {

        @Override
        public void resumeWith(@NonNull Object result) {
            if (result instanceof Result.Failure) {
                Throwable error = ((Result.Failure) result).exception;
                Timber.e(error, "Failed to enable camera");
                return;
            }
            Timber.d("Camera enabled successfully");

            // Retrieve the track publication after enabling the camera
            // Attach the local video track to the renderer.

            TrackPublication publication = room.getLocalParticipant().getTrackPublication(Track.Source.CAMERA);
            Timber.d("Local video track publication: %s", publication);
            if (publication != null) {
                Timber.d("Local video track attached");
                // The getTrackPublication() returns a publication from which you can retrieve the track.
                // Casting is required if you know the type.
                LocalVideoTrack localTrack = (LocalVideoTrack) publication.getTrack();
                if (localTrack != null) {
                    attachLocalVideo(localTrack);
                }
            }
        }
    }

    public class OnMicConnected extends BaseContinuation {
        @Override
        public void resumeWith(@NonNull Object result) {
            if (result instanceof Result.Failure) {
                Throwable error = ((Result.Failure) result).exception;
                Timber.e(error, "Failed to enable microphone");
                return;
            }
            Timber.d("Microphone enabled successfully");
        }
    }


}
