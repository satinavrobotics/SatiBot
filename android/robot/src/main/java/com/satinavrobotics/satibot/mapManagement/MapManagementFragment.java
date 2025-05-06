package com.satinavrobotics.satibot.mapManagement;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import org.openbot.R;
import org.openbot.databinding.FragmentMapManagementBinding;

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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = FirebaseFirestore.getInstance();
        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        mapList = new ArrayList<>();
        preferencesManager = new SharedPreferencesManager(requireContext());

        // Handle back button press
        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                requireActivity().getSupportFragmentManager().popBackStack();
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
        loadMaps();
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

        if (currentUser == null) {
            // Even if not signed in, we still show the Earth option
            adapter.setItems(mapList);

            // Restore selection state after loading maps
            String currentMapId = preferencesManager.getCurrentMapId();
            if (currentMapId != null) {
                adapter.setSelectedMapId(currentMapId);
            }
            return;
        }

        db.collection("maps")
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Timber.d("found maps");
                        // We already have Earth map at index 0, so add the rest
                        for (QueryDocumentSnapshot document : task.getResult()) {
                            Map map = document.toObject(Map.class);
                            Timber.d("" + document.getMetadata().isFromCache());
                            mapList.add(map);
                        }
                        adapter.setItems(mapList);

                        // Restore selection state after loading maps
                        String currentMapId = preferencesManager.getCurrentMapId();
                        if (currentMapId != null) {
                            adapter.setSelectedMapId(currentMapId);
                        }
                    } else {
                        Timber.e(task.getException(), "Error loading maps");
                        Toast.makeText(requireContext(), "Error loading maps: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
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

        db.collection("maps").document(map.getId())
                .delete()
                .addOnSuccessListener(aVoid -> {
                    mapList.remove(map);
                    adapter.setItems(mapList);

                    // If the deleted map was the selected one, clear the selection
                    if (map.getId().equals(preferencesManager.getCurrentMapId())) {
                        preferencesManager.setCurrentMapId(null);
                        adapter.setSelectedMapId(null);
                    }

                    Toast.makeText(requireContext(), "Map deleted successfully", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Timber.e(e, "Error deleting map");
                    Toast.makeText(requireContext(), "Error deleting map: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
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
    }
}
