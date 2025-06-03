package com.satinavrobotics.satibot.livekit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.firebase.auth.FirebaseAuth;
import com.satinavrobotics.satibot.env.ControllerToBotEventBus;
import com.satinavrobotics.satibot.env.StatusManager;
import com.satinavrobotics.satibot.utils.ConnectionUtils;
import com.satinavrobotics.satibot.utils.Constants;
import com.satinavrobotics.satibot.utils.PermissionUtils;

import org.json.JSONException;
import org.json.JSONObject;

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
import io.livekit.android.room.track.TrackException;
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

    // Permission handling
    private boolean allGranted = false;


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
                            int ttl_seconds = !ttl.equals("not found") ? Integer.parseInt(ttl) : 600;
                            // Subtract 60 seconds as a safety buffer and convert to milliseconds
                            long ttl_millis = (ttl_seconds - 60) * 1000L;
                            tokenManager.saveToken(token, ttl_millis);
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
        if (room != null) {
            room.initVideoRenderer(view);
        }
    }

    public void clearView() {
        this.videoRenderer = null;
    }

    public SurfaceViewRenderer setupVideoRenderer(Fragment fragment) {
        // Clear any existing renderer first
        if (videoRenderer != null) {
            try {
                videoRenderer.release();
            } catch (Exception ignored) {}
            videoRenderer = null;
        }

        SurfaceViewRenderer renderer = new SurfaceViewRenderer(fragment.requireContext());
        renderer.setMirror(false);
        renderer.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);

        if (fragment.getView() != null) {
            ViewGroup rootView = (ViewGroup) fragment.getView();
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            renderer.setLayoutParams(layoutParams);
            rootView.addView(renderer, 0);
        }

        // Initialize the renderer with the room first, then set as current renderer
        if (room != null) {
            room.initVideoRenderer(renderer);
        }
        this.videoRenderer = renderer;

        return renderer;
    }

    public void cleanupVideoRenderer(Fragment fragment, SurfaceViewRenderer videoRenderer) {
        if (videoRenderer != null) {
            // Remove video track from renderer first
            if (room != null && room.getState() == Room.State.CONNECTED) {
                try {
                    LocalParticipant localParticipant = room.getLocalParticipant();
                    TrackPublication publication = localParticipant.getTrackPublication(Track.Source.CAMERA);
                    if (publication != null) {
                        LocalVideoTrack localTrack = (LocalVideoTrack) publication.getTrack();
                        if (localTrack != null) {
                            localTrack.removeRenderer(videoRenderer);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Clear the view reference to prevent LiveKit from using it
            if (this.videoRenderer == videoRenderer) {
                this.videoRenderer = null;
            }

            ViewGroup parent = (ViewGroup) videoRenderer.getParent();
            if (parent != null) {
                parent.removeView(videoRenderer);
            }

            try {
                videoRenderer.release();
            } catch (Exception ignored) {}
        }
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

    public ActivityResultLauncher<String[]> createPermissionLauncher(Fragment fragment) {
        return fragment.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    allGranted = true;
                    result.forEach((permission, granted) -> allGranted = allGranted && granted);

                    if (allGranted) {
                        connect();
                    } else {
                        PermissionUtils.showControllerPermissionsToast(fragment.requireActivity());
                    }
                });
    }

    public void initLiveKitServer(Fragment fragment, ActivityResultLauncher<String[]> permissionLauncher) {
        if (!PermissionUtils.hasControllerPermissions(fragment.requireActivity())) {
            permissionLauncher.launch(Constants.PERMISSIONS_CONTROLLER);
        } else {
            connect();
        }
    }

    /**
     * Connects to the LiveKit room using hardcoded parameters.
     * Handles reconnection scenarios and validates tokens.
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
                    Timber.d("Starting connection process...");
                    if (!hasRequiredPermissions()) {
                        Timber.e("Missing required permissions for camera and/or microphone");
                        return;
                    }

                    // Validate token before attempting connection
                    String ROOM_URL = tokenManager.getServerAddress();
                    String ROOM_TOKEN = tokenManager.getToken();

                    if (ROOM_URL == null || ROOM_TOKEN == null) {
                        Timber.e("Cannot connect: missing server URL or token");
                        return;
                    }

                    Room.State currentState = room.getState();
                    Timber.d("Current room state: %s", currentState);

                    if (currentState == Room.State.DISCONNECTED) {
                        Timber.d("Connecting to room...");
                        room.connect(ROOM_URL, ROOM_TOKEN, connectOptions, new OnRoomConnected());
                    } else if (currentState == Room.State.CONNECTED) {
                        Timber.d("Room is already connected, updating connection state");
                        setConnected(true);
                    } else if (currentState == Room.State.CONNECTING || currentState == Room.State.RECONNECTING) {
                        Timber.d("Room is already connecting/reconnecting, current state: %s", currentState);
                        // Don't start another connection attempt, just wait for current one to complete
                    } else {
                        Timber.w("Room is in unexpected state: %s, attempting to reconnect", currentState);
                        // For unexpected states, try to disconnect first then reconnect
                        try {
                            room.disconnect();
                            Thread.sleep(1000); // Brief wait for disconnect
                            room.connect(ROOM_URL, ROOM_TOKEN, connectOptions, new OnRoomConnected());
                        } catch (Exception e) {
                            Timber.e(e, "Error during forced reconnection");
                        }
                    }
                    // Note: setConnected(true) is called in OnRoomConnected callback
                } catch (Exception e) {
                    Timber.e(e, "Error during connection process");
                }
            }
        }).start();
    }

    /**
     * Starts streaming video and microphone if the room is connected.
     * Also registers all RPC methods for command handling and initializes manual controls.
     * Includes state validation to prevent duplicate registrations during reconnection.
     */
    public void startStreaming() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (room.getState() != Room.State.CONNECTED) {
                        Timber.e("Cannot start streaming: room is not connected (state: %s)",
                                room.getState());
                        return;
                    }

                    if (!hasRequiredPermissions()) {
                        Timber.e("Missing required permissions for camera and/or microphone");
                        return;
                    }

                    LocalParticipant localParticipant = room.getLocalParticipant();
                    if (localParticipant == null) {
                        Timber.e("Cannot start streaming: local participant is null");
                        return;
                    }

                    Timber.d("Starting streaming process...");

                    // Check if camera is already enabled to avoid duplicate operations
                    TrackPublication cameraPublication = localParticipant.getTrackPublication(Track.Source.CAMERA);
                    if (cameraPublication == null || !cameraPublication.getTrack().getEnabled()) {
                        Timber.d("Enabling camera...");
                        localParticipant.setCameraEnabled(true, new OnCameraConnected());
                    } else {
                        Timber.d("Camera already enabled, reattaching video track...");
                        // Camera is already enabled, just reattach the video track
                        LocalVideoTrack localTrack = (LocalVideoTrack) cameraPublication.getTrack();
                        if (localTrack != null) {
                            attachLocalVideo(localTrack);
                        }
                    }

                    // Check if microphone is already enabled
                    TrackPublication micPublication = localParticipant.getTrackPublication(Track.Source.MICROPHONE);
                    if (micPublication == null || !micPublication.getTrack().getEnabled()) {
                        Timber.d("Enabling microphone...");
                        localParticipant.setMicrophoneEnabled(true, new OnMicConnected());
                    } else {
                        Timber.d("Microphone already enabled");
                    }

                    // Register all RPC methods (this handles duplicate registrations gracefully)
                    Timber.d("Registering RPC methods...");
                    registerRpcMethods(localParticipant);

                    // Initialize manual controls when streaming starts
                    Timber.d("Initializing manual controls...");
                    initializeManualControls();

                    // Ensure video track is attached to renderer after camera is enabled (fallback)
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            TrackPublication publication = localParticipant.getTrackPublication(Track.Source.CAMERA);
                            if (publication != null) {
                                LocalVideoTrack localTrack = (LocalVideoTrack) publication.getTrack();
                                if (localTrack != null && videoRenderer != null) {
                                    localTrack.addRenderer(videoRenderer);
                                    Timber.d("Video track attached to renderer (fallback)");
                                }
                            }
                        } catch (Exception e) {
                            Timber.w(e, "Failed to attach video track in fallback");
                        }
                    }, 500);

                    Timber.d("Streaming started successfully");

                } catch (Exception e) {
                    Timber.e(e, "Error starting streaming");
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

    public void switchCamera(String cameraName) {
        LocalParticipant localParticipant = room.getLocalParticipant();
        switchCamera(localParticipant, cameraName);
    }
    public void switchCamera(LocalParticipant localParticipant, String cameraName) {
        LocalVideoTrack localTrack = (LocalVideoTrack) localParticipant.getTrackPublication(Track.Source.CAMERA).getTrack();
        Timber.d("Switching camera to: %s", cameraName);
        localTrack.switchCamera(cameraName, null);
        Timber.d("Switched camera successfully to: %s", cameraName);
    }

    /**
     * Registers RPC methods for manual control operations.
     * Manual control methods: switch-camera, client-connected, drive-cmd, cmd, status
     * Handles duplicate registrations gracefully.
     *
     * @param localParticipant the local participant to register methods on.
     */
    private void registerManualControlRpcMethods(LocalParticipant localParticipant) {
        try {
            localParticipant.registerRpcMethod(
                    "switch-camera",  (data, error) -> {
                        // Process the received data
                        Timber.d("Camera switch request received: %s", data.getPayload());
                        try {
                            switchCamera(localParticipant, data.getPayload());
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

            Timber.d("Successfully registered manual control RPC methods");
        } catch (Exception e) {
            Timber.w(e, "Error registering manual control RPC methods (may already be registered)");
        }
    }

    /**
     * Registers RPC methods for autonomous control operations.
     * Autonomous control methods: waypoint-cmd
     * Note: status method is already registered by manual control methods
     * Handles duplicate registrations gracefully.
     *
     * @param localParticipant the local participant to register methods on.
     */
    private void registerAutonomousControlRpcMethods(LocalParticipant localParticipant) {
        try {
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

            Timber.d("Successfully registered autonomous control RPC methods");
        } catch (Exception e) {
            Timber.w(e, "Error registering autonomous control RPC methods (may already be registered)");
        }

        // Note: status method is already registered by manual control methods to avoid conflicts
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
        } catch (Exception e) {
            Timber.e(e, "Failed to unregister manual control RPC methods");
        }
    }

    /**
     * Unregisters autonomous control RPC methods.
     * Autonomous control methods: waypoint-cmd
     * Note: status method is managed by manual control methods
     *
     * @param localParticipant the local participant to unregister methods from.
     */
    private void unregisterAutonomousControlRpcMethods(LocalParticipant localParticipant) {
        try {
            localParticipant.unregisterRpcMethod("waypoint-cmd");
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
            try {
                videoTrack.addRenderer(videoRenderer);
                Timber.d("Video track successfully added to renderer");
            } catch (Exception e) {
                Timber.e(e, "Failed to add video track to renderer");
            }
        } else {
            Timber.w("Cannot attach video track: video renderer is null");
        }
    }

    /**
     * Reattaches the current video track to the renderer.
     * Used when the renderer is recreated but the room is already connected.
     */
    public void reattachVideoTrack() {
        if (room == null) {
            Timber.w("Cannot reattach video track: room is null");
            return;
        }

        if (room.getState() != Room.State.CONNECTED) {
            Timber.w("Cannot reattach video track: room is not connected (state: %s)", room.getState());
            return;
        }

        if (videoRenderer == null) {
            Timber.w("Cannot reattach video track: video renderer is null");
            return;
        }

        try {
            LocalParticipant localParticipant = room.getLocalParticipant();
            if (localParticipant == null) {
                Timber.w("Cannot reattach video track: local participant is null");
                return;
            }

            TrackPublication publication = localParticipant.getTrackPublication(Track.Source.CAMERA);
            if (publication == null) {
                Timber.w("Cannot reattach video track: camera track publication is null");
                return;
            }

            LocalVideoTrack localTrack = (LocalVideoTrack) publication.getTrack();
            if (localTrack == null) {
                Timber.w("Cannot reattach video track: camera track is null");
                return;
            }

            // Remove from current renderer first (if any) and add to new renderer
            try {
                localTrack.removeRenderer(videoRenderer); // Remove first in case it was already added
            } catch (Exception ignored) {}
            localTrack.addRenderer(videoRenderer);
            Timber.d("Video track successfully reattached to new renderer");
        } catch (Exception e) {
            Timber.e(e, "Error reattaching video track to renderer");
        }
    }

    public void stopStreaming() {
        if (room == null || room.getState() != Room.State.CONNECTED) {
            return;
        }

        try {
            LocalParticipant localParticipant = room.getLocalParticipant();
            if (localParticipant != null) {
                // Simple disable - let LiveKit handle cleanup
                localParticipant.setCameraEnabled(false, new BaseBooleanContinuation());
                localParticipant.setMicrophoneEnabled(false, new BaseBooleanContinuation());
                unregisterManualControlRpcMethods(localParticipant);
            }
        } catch (Exception e) {
            Timber.w(e, "Error stopping streaming");
        }
    }

    /**
     * Disconnects from the LiveKit room.
     * Performs complete cleanup including stopping streaming, unregistering RPC methods,
     * and cleaning up video tracks.
     */
    public void disconnect() {
        new Thread(() -> {
            try {
                Timber.d("Starting disconnect process...");

                // First stop streaming and clean up properly
                if (room != null && room.getState() == Room.State.CONNECTED) {
                    LocalParticipant localParticipant = room.getLocalParticipant();
                    if (localParticipant != null) {
                        Timber.d("Cleaning up streaming and RPC methods...");

                        // Disable camera and microphone
                        try {
                            localParticipant.setCameraEnabled(false, new BaseBooleanContinuation());
                            localParticipant.setMicrophoneEnabled(false, new BaseBooleanContinuation());
                        } catch (Exception e) {
                            Timber.w(e, "Error disabling camera/microphone during disconnect");
                        }

                        // Unregister all RPC methods to prevent conflicts on reconnect
                        try {
                            unregisterManualControlRpcMethods(localParticipant);
                            unregisterAutonomousControlRpcMethods(localParticipant);
                            Timber.d("Successfully unregistered all RPC methods");
                        } catch (Exception e) {
                            Timber.w(e, "Error unregistering RPC methods during disconnect");
                        }

                        // Clean up video track attachments
                        try {
                            if (videoRenderer != null) {
                                TrackPublication publication = localParticipant.getTrackPublication(Track.Source.CAMERA);
                                if (publication != null) {
                                    LocalVideoTrack localTrack = (LocalVideoTrack) publication.getTrack();
                                    if (localTrack != null) {
                                        localTrack.removeRenderer(videoRenderer);
                                        Timber.d("Removed video renderer from track");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Timber.w(e, "Error cleaning up video track during disconnect");
                        }
                    }

                    // Now disconnect from the room
                    Timber.d("Disconnecting from room...");
                    room.disconnect();
                    Thread.sleep(2000); // Wait for disconnect to complete
                    Timber.d("Room disconnected successfully");
                }

                // Reset internal state
                setConnected(false);

                // Reset all camera-related singletons to prevent multiple camera sources
                resetCameraSingletons();

                Timber.d("Disconnect process completed");

            } catch (Exception e) {
                Timber.e(e, "Error during disconnect process");
                setConnected(false);
                resetCameraSingletons();
            }
        }).start();
    }

    /**
     * Resets camera singletons to prevent multiple camera sources
     */
    private void resetCameraSingletons() {
        try {
            com.satinavrobotics.satibot.livekit.stream.ArCameraProvider.reset();
            com.satinavrobotics.satibot.livekit.stream.ArCameraSession.reset();
            com.satinavrobotics.satibot.livekit.stream.ExternalCameraSession.resetSingleton();
        } catch (Exception e) {
            Timber.w(e, "Error resetting camera singletons");
        }
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
     * Checks if the room is in a state where streaming can be started.
     *
     * @return true if streaming can be started, false otherwise.
     */
    public boolean canStartStreaming() {
        return room != null && room.getState() == Room.State.CONNECTED;
    }

    /**
     * Gets the current room state safely.
     *
     * @return the current room state, or null if room is null.
     */
    public Room.State getCurrentRoomState() {
        return room != null ? room.getState() : null;
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

    /**
     * Gets the remaining TTL of the token in seconds.
     *
     * @return remaining TTL in seconds, or 0 if token is expired or not available.
     */
    public long getTokenTTLSeconds() {
        try {
            return tokenManager != null ? tokenManager.getRemainingTTLSeconds() : 0;
        } catch (Exception e) {
            Timber.e(e, "Error getting token TTL");
            return 0;
        }
    }

    /**
     * Requests a new token from the server.
     * This is a public wrapper around the existing pollForTokenAsync method.
     */
    public void refreshToken() {
        pollForTokenAsync();
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
                    if (focalLength < 2.5) {
                        wideId = cameraId;
                    } else if (focalLength >= 2.5 && focalLength < 6.0) {
                        mainId = cameraId;
                    } else {
                        telephotoId = cameraId;
                    }
                }
            }

            cameraJson.put("main", mainId);
            cameraJson.put("wide", wideId);
            cameraJson.put("telephoto", telephotoId);
            cameraJson.put("front", frontId);

        } catch (CameraAccessException e) {
            Timber.e("Error accessing camera");
        } catch (JSONException e) {
            Timber.e("Error creating JSON");
        }

        return cameraJson.toString();
    }





    /**
     * Reinitializes the LiveKitServer singleton by disconnecting, cleaning up, and creating a new instance.
     */
    public static synchronized void reinitialize(Context context) {
        if (instance != null) {
            try {
                instance.disconnect();
                Thread.sleep(3000); // Wait longer for complete cleanup
            } catch (Exception ignored) {}
            instance = null;
        }
        getInstance(context);
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
            setConnected(true);
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

            try {
                LocalParticipant localParticipant = room.getLocalParticipant();
                if (localParticipant == null) {
                    return;
                }

                TrackPublication publication = localParticipant.getTrackPublication(Track.Source.CAMERA);
                if (publication != null) {
                    LocalVideoTrack localTrack = (LocalVideoTrack) publication.getTrack();
                    if (localTrack != null) {
                        attachLocalVideo(localTrack);
                    }
                }
            } catch (Exception e) {
                Timber.e(e, "Error in OnCameraConnected callback");
            }
        }
    }

    public static class OnMicConnected extends BaseBooleanContinuation {
        @Override
        public void resumeWith(@NonNull Object result) {
            if (result instanceof Result.Failure) {
                Throwable error = ((Result.Failure) result).exception;
                Timber.e(error, "Failed to enable microphone");
            }
        }
    }


}
