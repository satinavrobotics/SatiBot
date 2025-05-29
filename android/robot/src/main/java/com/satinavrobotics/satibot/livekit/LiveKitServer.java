package com.satinavrobotics.satibot.env;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.gson.JsonObject;
import com.satinavrobotics.satibot.utils.ConnectionUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.concurrent.Executors;

import io.livekit.android.ConnectOptions;
import io.livekit.android.LiveKit;
import io.livekit.android.LiveKitOverrides;
import io.livekit.android.RoomOptions;
import io.livekit.android.renderer.SurfaceViewRenderer;
import io.livekit.android.room.Room;
import io.livekit.android.room.participant.ConnectionQuality;
import io.livekit.android.room.participant.LocalParticipant;
import io.livekit.android.room.track.CameraPosition;
import io.livekit.android.room.track.LocalVideoTrack;
import io.livekit.android.room.track.LocalVideoTrackOptions;
import io.livekit.android.room.track.Track;
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
    private static LiveKitServer instance;
    private final Context context;
    private final Room room;
    private SurfaceViewRenderer videoRenderer;

    private static TokenManager tokenManager;

    private final ConnectOptions connectOptions = new ConnectOptions();

    private final String SERVER_URL = "https://controller.satinavrobotics.com/api/createToken";

    private boolean connected;

    private final StatusManager statusManager;


    // Singleton pattern for LiveKitServer
    public static synchronized LiveKitServer getInstance(Context context) {
        try {
            if (instance == null) {
                instance = new LiveKitServer(context.getApplicationContext()); // Use application context to avoid memory leaks
            }
            return instance;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

    private LiveKitServer(Context context) throws Exception {
        this.context = context;
        try {
            tokenManager = TokenManager.getInstance(context);

            if (tokenManager.getToken() == null) {
                tokenManager.clearToken();
                pollForTokenAsync();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        room = LiveKit.INSTANCE.create(context, createRoomOptions(), createOverrides());
        statusManager = StatusManager.getInstance();
    }

    public void pollForTokenAsync() {
        // Check internet connectivity before attempting to poll for token
        if (!ConnectionUtils.isInternetAvailable(context)) {
            Timber.w("No internet connection available, skipping token polling");
            return;
        }

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
                    String ttl = jsonResponse.optString("ttl", "not found");

                    Timber.d("Token: " + token);
                    Timber.d("Server URL: " + server_url);
                    Timber.d("TTL: " + ttl);

                    if (!token.equals("not found")) {
                        System.out.println("Token received: " + token);
                        try {
                            int ttl_seconds = !ttl.equals("not found") ? Integer.parseInt(ttl) : 600_000;
                            long expiration_time = System.currentTimeMillis() + (ttl_seconds - 60) * 1000L;
                            tokenManager.saveToken(token, expiration_time); // 10 minutes
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

    public void setView(SurfaceViewRenderer view) {
        this.videoRenderer = view;
        room.initVideoRenderer(view);
    }

    public void clearView() {
        this.videoRenderer = null;
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
        return new LiveKitOverrides();
    }

    public boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Connects to the LiveKit room using hardcoded parameters.
     */
    public void connect() {
        // Check internet connectivity before attempting to connect
        if (!ConnectionUtils.isInternetAvailable(context)) {
            Timber.w("No internet connection available, cannot connect to LiveKit");
            return;
        }

        // Run the connection on a separate thread to avoid blocking the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Timber.d("ROOM CONNECTION INITIALIZED");
                    if (!hasRequiredPermissions()) {
                        Timber.e("Missing required permissions for camera and/or microphone");
                        return;
                    }

                    if (room.getState() == Room.State.DISCONNECTED) {
                        String ROOM_URL = tokenManager.getServerAddress();
                        String ROOM_TOKEN = tokenManager.getToken();
                        Timber.d(room.getState().toString());
                        room.connect(ROOM_URL, ROOM_TOKEN, connectOptions, new OnRoomConnected());
                    }
                    // Note: setConnected(true) is now called in OnRoomConnected callback
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * Starts streaming video and microphone if the room is connected.
     * Also registers all RPC methods for command handling and initializes manual controls.
     */
    public void startStreaming() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (room.getState() != Room.State.CONNECTED) {
                        Timber.e("Cannot start streaming: room is not connected");
                        return;
                    }

                    if (!hasRequiredPermissions()) {
                        Timber.e("Missing required permissions for camera and/or microphone");
                        return;
                    }

                    Timber.d("Starting streaming...");
                    LocalParticipant localParticipant = room.getLocalParticipant();

                    // Enable camera and microphone
                    localParticipant.setCameraEnabled(true, new OnCameraConnected());
                    localParticipant.setMicrophoneEnabled(true, new OnMicConnected());

                    // Register all RPC methods
                    registerRpcMethods(localParticipant);

                    // Initialize manual controls when streaming starts
                    initializeManualControls();

                    // Ensure video track is attached to renderer after camera is enabled
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            TrackPublication publication = localParticipant.getTrackPublication(Track.Source.CAMERA);
                            if (publication != null) {
                                LocalVideoTrack localTrack = (LocalVideoTrack) publication.getTrack();
                                if (localTrack != null && videoRenderer != null) {
                                    localTrack.addRenderer(videoRenderer);
                                }
                            }
                        } catch (Exception ignored) {}
                    }, 500);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * Registers all RPC methods for command handling.
     *
     * @param localParticipant the local participant to register methods on.
     */
    private void registerRpcMethods(LocalParticipant localParticipant) {
        registerManualControlRpcMethods(localParticipant);
        registerAutonomousControlRpcMethods(localParticipant);
    }

    /**
     * Public method to register only manual control RPC methods.
     * Useful for scenarios where only manual control is needed.
     *
     * @param localParticipant the local participant to register methods on.
     */
    public void registerManualControlRpcMethodsPublic(LocalParticipant localParticipant) {
        registerManualControlRpcMethods(localParticipant);
    }

    /**
     * Public method to register only autonomous control RPC methods.
     * Useful for scenarios where only autonomous control is needed.
     *
     * @param localParticipant the local participant to register methods on.
     */
    public void registerAutonomousControlRpcMethodsPublic(LocalParticipant localParticipant) {
        registerAutonomousControlRpcMethods(localParticipant);
    }

    /**
     * Public method to register only autonomous control RPC methods using the current room's LocalParticipant.
     * This is a convenience method for autonomous navigation fragments.
     *
     * @return true if registration was successful, false otherwise.
     */
    public boolean registerAutonomousControlRpcMethods() {
        if (room != null && room.getState() == Room.State.CONNECTED) {
            try {
                LocalParticipant localParticipant = room.getLocalParticipant();
                if (localParticipant != null) {
                    registerAutonomousControlRpcMethods(localParticipant);
                    Timber.d("Successfully registered autonomous control RPC methods");
                    return true;
                } else {
                    Timber.w("Local participant is null, cannot register autonomous RPC methods");
                    return false;
                }
            } catch (Exception e) {
                Timber.e(e, "Failed to register autonomous RPC methods: %s", e.getMessage());
                return false;
            }
        } else {
            Timber.w("Room is not connected, cannot register autonomous RPC methods");
            return false;
        }
    }

    /**
     * Registers RPC methods for manual control operations.
     * Manual control methods: switch-camera, client-connected, drive-cmd, cmd, status
     *
     * @param localParticipant the local participant to register methods on.
     */
    private void registerManualControlRpcMethods(LocalParticipant localParticipant) {
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

    /**
     * Registers RPC methods for autonomous control operations.
     * Autonomous control methods: waypoint-cmd, status
     *
     * @param localParticipant the local participant to register methods on.
     */
    private void registerAutonomousControlRpcMethods(LocalParticipant localParticipant) {
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

        // Note: status method is also available for autonomous control
        localParticipant.registerRpcMethod(
                "status", (data, error) -> statusManager.getStatus().toString(),
                new BaseContinuation());
    }

    /**
     * Unregisters manual control RPC methods.
     * Manual control methods: switch-camera, client-connected, drive-cmd, cmd, status
     *
     * @param localParticipant the local participant to unregister methods from.
     */
    private void unregisterManualControlRpcMethods(LocalParticipant localParticipant) {
        try {
            localParticipant.unregisterRpcMethod("switch-camera");
            localParticipant.unregisterRpcMethod("client-connected");
            localParticipant.unregisterRpcMethod("drive-cmd");
            localParticipant.unregisterRpcMethod("cmd");
            localParticipant.unregisterRpcMethod("status");
            Timber.d("Successfully unregistered manual control RPC methods");
        } catch (Exception e) {
            Timber.e(e, "Failed to unregister manual control RPC methods: %s", e.getMessage());
        }
    }

    /**
     * Unregisters autonomous control RPC methods.
     * Autonomous control methods: waypoint-cmd, status
     *
     * @param localParticipant the local participant to unregister methods from.
     */
    private void unregisterAutonomousControlRpcMethods(LocalParticipant localParticipant) {
        try {
            localParticipant.unregisterRpcMethod("waypoint-cmd");
            localParticipant.unregisterRpcMethod("status");
            Timber.d("Successfully unregistered autonomous control RPC methods");
        } catch (Exception e) {
            Timber.e(e, "Failed to unregister autonomous control RPC methods: %s", e.getMessage());
        }
    }

    /**
     * Public method to unregister only autonomous control RPC methods using the current room's LocalParticipant.
     * This is a convenience method for autonomous navigation fragments.
     *
     * @return true if unregistration was successful, false otherwise.
     */
    public boolean unregisterAutonomousControlRpcMethods() {
        if (room != null && room.getState() == Room.State.CONNECTED) {
            try {
                LocalParticipant localParticipant = room.getLocalParticipant();
                if (localParticipant != null) {
                    unregisterAutonomousControlRpcMethods(localParticipant);
                    Timber.d("Successfully unregistered autonomous control RPC methods");
                    return true;
                } else {
                    Timber.w("Local participant is null, cannot unregister autonomous RPC methods");
                    return false;
                }
            } catch (Exception e) {
                Timber.e(e, "Failed to unregister autonomous RPC methods: %s", e.getMessage());
                return false;
            }
        } else {
            Timber.w("Room is not connected, cannot unregister autonomous RPC methods");
            return false;
        }
    }

    /**
     * Initializes manual controls when streaming starts.
     * This emits a client-connected event to trigger control initialization.
     */
    private void initializeManualControls() {
        try {
            Timber.d("Initializing manual controls...");
            // Create a client-connected event to initialize controls
            JSONObject clientConnectedEvent = new JSONObject();
            clientConnectedEvent.put("command", "CLIENT_CONNECTED");
            clientConnectedEvent.put("timestamp", System.currentTimeMillis());

            // Emit the event to initialize controls
            ControllerToBotEventBus.emitEvent(clientConnectedEvent.toString());
            Timber.d("Manual controls initialized");
        } catch (JSONException e) {
            Timber.e(e, "Failed to initialize manual controls");
        }
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
     * Stops streaming (disables camera and microphone) but keeps the room connection.
     * Also unregisters manual control RPC methods.
     */
    public void stopStreaming() {
        if (room == null || room.getState() != Room.State.CONNECTED) {
            return;
        }

        try {
            LocalParticipant localParticipant = room.getLocalParticipant();
            localParticipant.setCameraEnabled(false, new BaseBooleanContinuation());
            localParticipant.setMicrophoneEnabled(false, new BaseBooleanContinuation());

            // Unregister manual control RPC methods when stopping streaming
            unregisterManualControlRpcMethods(localParticipant);
        } catch (Exception ignored) {}
    }

    /**
     * Disconnects from the LiveKit room.
     */
    public void disconnect() {
        //room.disconnect();
        LocalParticipant localParticipant = room.getLocalParticipant();
        try {
            localParticipant.setCameraEnabled(false, new BaseBooleanContinuation());
            localParticipant.setMicrophoneEnabled(false, new BaseBooleanContinuation());
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

    /**
     * Checks if the room is connected to the LiveKit server.
     *
     * @return true if the room is connected, false otherwise.
     */
    public boolean isRoomConnected() {
        return room != null && room.getState() == Room.State.CONNECTED;
    }





    /**
     * Gets the current room state as a string.
     *
     * @return the room state as a string.
     */
    public String getRoomState() {
        return room != null ? room.getState().toString() : "Unknown";
    }

    /**
     * Gets the server URL.
     *
     * @return the server URL or null if not available.
     */
    public String getServerUrl() {
        try {
            return tokenManager != null ? tokenManager.getServerAddress() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets participant information.
     *
     * @return participant information as a string.
     */
    public String getParticipantInfo() {
        if (room != null && room.getLocalParticipant() != null) {
            return "Local participant connected";
        }
        return "No participant";
    }

    /**
     * Gets the connection quality of the local participant.
     *
     * @return connection quality as a string.
     */
    public String getConnectionQuality() {
        if (room != null && room.getLocalParticipant() != null) {
            try {
                ConnectionQuality quality = room.getLocalParticipant().getConnectionQuality();
                if (quality != null) {
                    return quality.toString();
                }
            } catch (Exception e) {
                Timber.e(e, "Error getting connection quality");
            }
        }
        return "Unknown";
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



    public static class BaseContinuation implements Continuation<Unit> {
        @Override
        public CoroutineContext getContext() {
            return Dispatchers.getDefault(); // or another appropriate context
        }
        @Override
        public void resumeWith(@NonNull Object result) {
        }
    }


    public static class BaseBooleanContinuation implements Continuation<Boolean> {
        @Override
        public CoroutineContext getContext() {
            return Dispatchers.getDefault(); // or another appropriate context
        }
        @Override
        public void resumeWith(@NonNull Object result) {
        }
    }

    public class OnRoomConnected extends BaseBooleanContinuation {
        @Override
        public void resumeWith(@NonNull Object result) {
            if (result instanceof Result.Failure) {
                Throwable error = ((Result.Failure) result).exception;
                Timber.e(error, "Failed to connect to room");
                return;
            }
            Timber.d("Connected to room successfully");
            setConnected(true);

            // Note: Camera, microphone, and RPC methods are now handled by startStreaming() method
            // This callback only handles the room connection itself
        }
    }

    private class OnCameraConnected extends BaseBooleanContinuation {

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

    public static class OnMicConnected extends BaseBooleanContinuation {
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
