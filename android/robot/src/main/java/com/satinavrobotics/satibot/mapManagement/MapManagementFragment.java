package com.satinavrobotics.satibot.mapManagement;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.navigation.Navigation;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.databinding.FragmentMapManagementBinding;

import com.satinavrobotics.satibot.env.SharedPreferencesManager;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class MapManagementFragment extends Fragment implements MapAdapter.OnItemClickListener<Map> {

    private FragmentMapManagementBinding binding;
    private MapAdapter adapter;
    private List<Map> mapList;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private OnBackPressedCallback onBackPressedCallback;
    private SharedPreferencesManager preferencesManager;
    private boolean mapsLoaded = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize Firebase components with error handling
        try {
            // Initialize Firestore
            db = FirebaseFirestore.getInstance();

            // Get current user
            FirebaseAuth auth = FirebaseAuth.getInstance();
            currentUser = auth.getCurrentUser();

            // Log authentication state
            if (currentUser != null) {
                Timber.d("User authenticated: %s", currentUser.getEmail());
            } else {
                Timber.w("No user authenticated");
            }
        } catch (Exception e) {
            Timber.e(e, "Error initializing Firebase components");
            Toast.makeText(requireContext(), "Error initializing Firebase: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        mapList = new ArrayList<>();
        preferencesManager = new SharedPreferencesManager(requireContext());

        // Handle back button press
        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Navigate directly to the main fragment instead of using popBackStack
                // This prevents navigation issues when returning to this fragment
                Navigation.findNavController(requireActivity(), R.id.nav_host_fragment)
                    .navigate(R.id.mainFragment);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMapManagementBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupUI();

        // Check Google Play Services availability first
        if (checkGooglePlayServices()) {
            // Check authentication state before loading maps
            refreshAuthState();
            loadMaps();
        }
    }

    /**
     * Checks if Google Play Services is available and up to date
     * @return true if Google Play Services is available and up to date, false otherwise
     */
    private boolean checkGooglePlayServices() {
        GoogleApiAvailability googleAPI = GoogleApiAvailability.getInstance();
        int result = googleAPI.isGooglePlayServicesAvailable(requireContext());

        if (result != ConnectionResult.SUCCESS) {
            if (googleAPI.isUserResolvableError(result)) {
                // Show dialog to fix Google Play Services
                googleAPI.getErrorDialog(this, result, 9000, dialog -> {
                    // This callback is called if the dialog is canceled
                    Toast.makeText(requireContext(),
                        "Google Play Services must be installed and up to date to use this feature",
                        Toast.LENGTH_LONG).show();
                }).show();
            } else {
                Toast.makeText(requireContext(),
                    "This device doesn't support Google Play Services, which is required for this app",
                    Toast.LENGTH_LONG).show();
            }
            return false;
        }
        return true;
    }

    /**
     * Refreshes the authentication state and updates the currentUser field
     */
    private void refreshAuthState() {
        try {
            // Get the latest authentication state
            FirebaseAuth auth = FirebaseAuth.getInstance();
            currentUser = auth.getCurrentUser();

            if (currentUser != null) {
                // User is signed in, refresh the token to ensure it's valid
                currentUser.getIdToken(true)
                    .addOnSuccessListener(result -> {
                        Timber.d("Token refreshed successfully");
                    })
                    .addOnFailureListener(e -> {
                        Timber.e(e, "Failed to refresh token");
                        Toast.makeText(requireContext(),
                            "Authentication error. Please sign in again.",
                            Toast.LENGTH_LONG).show();
                    });
            } else {
                Timber.w("No user is signed in");
            }
        } catch (Exception e) {
            Timber.e(e, "Error refreshing authentication state");
        }
    }

    private void setupUI() {
        // Setup auto-sync animation
        ObjectAnimator rotation = ObjectAnimator.ofFloat(binding.autoSync, "rotation", 360f, 0f);
        rotation.setDuration(1000);
        rotation.setRepeatCount(ObjectAnimator.INFINITE);
        rotation.setInterpolator(new LinearInterpolator());

        // Setup RecyclerView
        adapter = new MapAdapter(mapList, requireContext(), this);
        binding.mapListContainer.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.mapListContainer.setAdapter(adapter);
        binding.mapListContainer.addItemDecoration(
                new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        // Set the selected map ID from preferences
        String currentMapId = preferencesManager.getCurrentMapId();
        if (currentMapId != null) {
            adapter.setSelectedMapId(currentMapId);
        }

        // Setup sync button
        binding.autoSync.setOnClickListener(v -> {
            rotation.start();
            loadMaps();
            rotation.cancel();
        });

        // Setup add map button
        binding.addMap.setOnClickListener(v -> {
            if (currentUser == null) {
                Toast.makeText(requireContext(), "Please sign in to add maps", Toast.LENGTH_SHORT).show();
                return;
            }
            // Navigate to the map scanning fragment
            Navigation.findNavController(requireView())
                    .navigate(R.id.action_mapManagementFragment_to_mapScanningFragment);
        });

        // Setup resolve map button
        binding.resolveMap.setOnClickListener(v -> {
            if (currentUser == null) {
                Toast.makeText(requireContext(), "Please sign in to resolve maps", Toast.LENGTH_SHORT).show();
                return;
            }

            // Check if a map is selected
            String selectedMapId = adapter.getSelectedMapId();
            if (selectedMapId == null) {
                Toast.makeText(requireContext(), "Please select a map to resolve", Toast.LENGTH_SHORT).show();
                return;
            }

            // Navigate to the map resolving fragment
            Navigation.findNavController(requireView())
                    .navigate(R.id.action_mapManagementFragment_to_mapResolvingFragment);
        });

        // Setup dense mapping button
        binding.denseMapping.setOnClickListener(v -> {
            if (currentUser == null) {
                Toast.makeText(requireContext(), "Please sign in to create dense maps", Toast.LENGTH_SHORT).show();
                return;
            }

            // Check if a map is selected
            String selectedMapId = adapter.getSelectedMapId();
            if (selectedMapId == null) {
                Toast.makeText(requireContext(), "Please select a map for dense mapping", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create a bundle to pass the dense mapping flag
            Bundle args = new Bundle();
            args.putBoolean("dense_mapping_mode", true);

            // Navigate to the map resolving fragment with the dense mapping flag
            Navigation.findNavController(requireView())
                    .navigate(R.id.action_mapManagementFragment_to_mapResolvingFragment, args);
        });
    }

    private void loadMaps() {
        Timber.d("Loading maps...");

        // Always start with a clear list
        mapList.clear();

        // Add the special "Earth" map as the first item
        Map earthMap = new Map();
        earthMap.setId("earth");
        earthMap.setName("Earth");
        earthMap.setCreatorEmail("system");
        earthMap.setCreatorId("system");
        earthMap.setCreatedAt(System.currentTimeMillis());
        earthMap.setUpdatedAt(System.currentTimeMillis());
        // Add an empty list of anchors
        earthMap.setAnchors(new ArrayList<>());
        mapList.add(earthMap);

        // Add the special "No Map" option as the second item
        Map noMap = new Map();
        noMap.setId("no_map");
        noMap.setName("No Map");
        noMap.setCreatorEmail("system");
        noMap.setCreatorId("system");
        noMap.setCreatedAt(System.currentTimeMillis());
        noMap.setUpdatedAt(System.currentTimeMillis());
        // Add an empty list of anchors
        noMap.setAnchors(new ArrayList<>());
        mapList.add(noMap);

        // Update adapter with at least the Earth map and No Map option
        adapter.setItems(mapList);

        // Restore selection state after loading maps
        String currentMapId = preferencesManager.getCurrentMapId();
        if (currentMapId != null) {
            adapter.setSelectedMapId(currentMapId);
        }

        // If not signed in, just show Earth map and return
        if (currentUser == null) {
            Toast.makeText(requireContext(), "Sign in to access your maps", Toast.LENGTH_SHORT).show();
            mapsLoaded = true; // Mark as loaded even with just Earth map
            return;
        }

        // Verify Firebase Auth state before accessing Firestore
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            // Try to re-authenticate
            Timber.w("Firebase user is null, attempting to re-authenticate");
            FirebaseAuth.getInstance().signOut();
            Toast.makeText(requireContext(), "Authentication error. Please sign in again.", Toast.LENGTH_LONG).show();
            mapsLoaded = true; // Mark as loaded even with just Earth map
            return;
        }

        try {
            // Add a timeout to the Firestore query
            db.collection("maps")
                    .get()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Timber.d("Found maps");
                            // We already have Earth map at index 0, so add the rest
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                Map map = document.toObject(Map.class);
                                Timber.d("Map from cache: %s", document.getMetadata().isFromCache());

                                // Check if this map already exists in the list to prevent duplicates
                                String mapId = map.getId();
                                boolean mapExists = false;
                                for (Map existingMap : mapList) {
                                    if (existingMap.getId() != null && existingMap.getId().equals(mapId)) {
                                        mapExists = true;
                                        break;
                                    }
                                }

                                if (!mapExists) {
                                    mapList.add(map);
                                }
                            }
                            adapter.setItems(mapList);

                            // Restore selection state after loading maps
                            String updatedMapId = preferencesManager.getCurrentMapId();
                            if (updatedMapId != null) {
                                adapter.setSelectedMapId(updatedMapId);
                            }

                            // Mark maps as loaded
                            mapsLoaded = true;
                        } else {
                            Exception exception = task.getException();
                            Timber.e(exception, "Error loading maps");

                            String errorMessage = "Error loading maps";
                            if (exception != null) {
                                errorMessage += ": " + exception.getMessage();

                                // Check for specific security exception
                                if (exception instanceof SecurityException &&
                                    exception.getMessage().contains("Unknown calling package name")) {
                                    errorMessage = "Authentication error with Google Play Services. Attempting to fix...";

                                    // Log detailed error for debugging
                                    Timber.e("Google Play Services authentication error: %s", exception.getMessage());

                                    // Try to fix Google Play Services
                                    handleGooglePlayServicesError();
                                }
                            }

                            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
                            mapsLoaded = true; // Mark as loaded even on error
                        }
                    });
        } catch (Exception e) {
            Timber.e(e, "Exception when trying to access Firestore");
            Toast.makeText(requireContext(), "Error accessing Firestore: " + e.getMessage(), Toast.LENGTH_LONG).show();
            mapsLoaded = true; // Mark as loaded even on error
        }
    }



    @Override
    public void onItemClick(Map item) {
        // Update selection state
        String mapId = item.getId();
        if (mapId != null) {
            // Save the selected map ID to preferences
            preferencesManager.setCurrentMapId(mapId);
            adapter.setSelectedMapId(mapId);

            // Show confirmation message
            Toast.makeText(requireContext(), "Selected map: " + item.getName(), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(), "Cannot select map without ID", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMapDelete(Map item) {
        // Extra safety check to prevent deletion of special maps
        if (item != null && ("earth".equals(item.getId()) || "no_map".equals(item.getId()))) {
            Timber.w("Attempted to delete special map %s, which is not allowed", item.getId());
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Map")
                .setMessage("Are you sure you want to delete this map? This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deleteMap(item))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteMap(Map map) {
        if (map.getId() == null) {
            Toast.makeText(requireContext(), "Cannot delete map without ID", Toast.LENGTH_SHORT).show();
            return;
        }

        // Extra safety check to prevent deletion of special maps
        if ("earth".equals(map.getId()) || "no_map".equals(map.getId())) {
            Timber.w("Attempted to delete special map %s, which is not allowed", map.getId());
            Toast.makeText(requireContext(), "The " + map.getName() + " map cannot be deleted", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("maps").document(map.getId())
                .delete()
                .addOnSuccessListener(aVoid -> {
                    // Remove the map from the list without reloading all maps
                    mapList.remove(map);
                    adapter.setItems(mapList);

                    // If the deleted map was the selected one, clear the selection
                    if (map.getId().equals(preferencesManager.getCurrentMapId())) {
                        preferencesManager.setCurrentMapId(null);
                        adapter.setSelectedMapId(null);
                    }

                    // Maps are still considered loaded even after deletion
                    mapsLoaded = true;

                    Toast.makeText(requireContext(), "Map deleted successfully", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Timber.e(e, "Error deleting map");
                    Toast.makeText(requireContext(), "Error deleting map: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Check Google Play Services first, then refresh authentication
        if (checkGooglePlayServices()) {
            refreshAuthState();
            // Reload maps if needed and not already loaded
            if (!mapsLoaded || mapList.isEmpty()) {
                loadMaps();
            }
        }
    }

    /**
     * Handles Google Play Services errors, specifically the "Unknown calling package name" error
     */
    private void handleGooglePlayServicesError() {
        GoogleApiAvailability googleAPI = GoogleApiAvailability.getInstance();
        int result = googleAPI.isGooglePlayServicesAvailable(requireContext());

        if (result != ConnectionResult.SUCCESS) {
            if (googleAPI.isUserResolvableError(result)) {
                // Show dialog to fix Google Play Services
                googleAPI.getErrorDialog(this, result, 9001, dialog -> {
                    // This callback is called if the dialog is canceled
                    Toast.makeText(requireContext(),
                        "Google Play Services must be fixed to continue",
                        Toast.LENGTH_LONG).show();
                }).show();
            } else {
                new AlertDialog.Builder(requireContext())
                    .setTitle("Google Play Services Error")
                    .setMessage("There is an issue with Google Play Services that cannot be automatically resolved. " +
                               "Try restarting the app or reinstalling Google Play Services.")
                    .setPositiveButton("OK", null)
                    .show();
            }
        } else {
            // Google Play Services is available but we still got the error
            // This might be a package name mismatch or other configuration issue
            new AlertDialog.Builder(requireContext())
                .setTitle("Authentication Error")
                .setMessage("There was an error authenticating with Google Play Services. " +
                           "Please restart the app and try again.")
                .setPositiveButton("OK", null)
                .show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        onBackPressedCallback.remove();
        // Reset the maps loaded flag so maps will be reloaded when the fragment is recreated
        mapsLoaded = false;
    }
}
