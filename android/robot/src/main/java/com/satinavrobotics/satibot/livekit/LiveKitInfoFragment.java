package com.satinavrobotics.satibot.livekit;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.databinding.FragmentLivekitInfoBinding;
import com.satinavrobotics.satibot.utils.ConnectionUtils;
import com.satinavrobotics.satibot.utils.PermissionUtils;

import timber.log.Timber;

public class LiveKitInfoFragment extends Fragment {

    private FragmentLivekitInfoBinding binding;
    private LiveKitServer liveKitServer;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentLivekitInfoBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        liveKitServer = LiveKitServer.getInstance(requireContext());

        setupUI();
        updateConnectionStatus();

        // Update status every 2 seconds
        view.postDelayed(this::updateConnectionStatus, 2000);
    }

    private void setupUI() {
        binding.connectButton.setOnClickListener(v -> {
            if (!PermissionUtils.hasControllerPermissions(requireActivity())) {
                PermissionUtils.showControllerPermissionsToast(requireActivity());
                return;
            }

            if (liveKitServer.isRoomConnected()) {
                liveKitServer.disconnect();
            } else {
                // Check internet connectivity before attempting to connect
                if (!ConnectionUtils.isInternetAvailable(requireContext())) {
                    Toast.makeText(requireContext(),
                        "No internet connection available. Please check your network connection and try again.",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                liveKitServer.connect();
            }

            // Update status after a short delay
            binding.getRoot().postDelayed(this::updateConnectionStatus, 1000);
        });

        binding.refreshTokenButton.setOnClickListener(v -> {
            // Check internet connectivity before attempting to refresh token
            if (!ConnectionUtils.isInternetAvailable(requireContext())) {
                Toast.makeText(requireContext(),
                    "No internet connection available. Please check your network connection and try again.",
                    Toast.LENGTH_LONG).show();
                return;
            }

            // Disable button temporarily to prevent multiple requests
            binding.refreshTokenButton.setEnabled(false);
            binding.refreshTokenButton.setText("Refreshing...");

            // Request new token
            liveKitServer.refreshToken();

            Toast.makeText(requireContext(), "Token refresh requested", Toast.LENGTH_SHORT).show();

            // Re-enable button after a delay
            binding.getRoot().postDelayed(() -> {
                if (binding != null) {
                    binding.refreshTokenButton.setEnabled(true);
                    binding.refreshTokenButton.setText("Refresh Token");
                }
            }, 3000);

            // Update status after a short delay
            binding.getRoot().postDelayed(this::updateConnectionStatus, 1000);
        });

        binding.reinitializeButton.setOnClickListener(v -> {
            LiveKitServer.reinitialize(requireContext());
            liveKitServer = LiveKitServer.getInstance(requireContext());
            Toast.makeText(requireContext(), "LiveKit server reinitialized", Toast.LENGTH_SHORT).show();
            updateConnectionStatus();
        });
    }

    private void updateConnectionStatus() {
        if (binding == null || liveKitServer == null) {
            return;
        }

        try {
            boolean isConnected = liveKitServer.isConnected();
            boolean isRoomConnected = liveKitServer.isRoomConnected();

            // Update connection status
            binding.connectionStatusText.setText(isRoomConnected ? "Connected" : "Disconnected");
            binding.connectionStatusText.setTextColor(
                getResources().getColor(isRoomConnected ? R.color.green : R.color.red, null)
            );

            // Update room state
            String roomState = liveKitServer.getRoomState();
            binding.roomStateText.setText("Room State: " + roomState);

            // Update server URL
            String serverUrl = liveKitServer.getServerUrl();
            binding.serverUrlText.setText("Server: " + (serverUrl != null ? serverUrl : "Not available"));

            // Update participant info
            String participantInfo = liveKitServer.getParticipantInfo();
            binding.participantInfoText.setText("Participant: " + participantInfo);

            // Update connection quality
            String connectionQuality = liveKitServer.getConnectionQuality();
            binding.connectionQualityText.setText("Connection Quality: " + connectionQuality);

            // Update token TTL
            long ttlSeconds = liveKitServer.getTokenTTLSeconds();
            String ttlText = formatTTL(ttlSeconds);
            binding.tokenTtlText.setText("Token TTL: " + ttlText);

            // Color code the TTL based on remaining time
            if (ttlSeconds <= 0) {
                binding.tokenTtlText.setTextColor(getResources().getColor(R.color.red, null));
            } else if (ttlSeconds < 300) { // Less than 5 minutes
                binding.tokenTtlText.setTextColor(getResources().getColor(android.R.color.holo_orange_light, null));
            } else {
                binding.tokenTtlText.setTextColor(getResources().getColor(R.color.green, null));
            }

            // Update button states
            binding.connectButton.setText(isRoomConnected ? "Disconnect" : "Connect");
            binding.connectButton.setEnabled(true);

        } catch (Exception e) {
            Timber.e(e, "Error updating connection status");
            binding.connectionStatusText.setText("Error");
            binding.connectionStatusText.setTextColor(getResources().getColor(R.color.red, null));
        }

        // Schedule next update
        if (binding != null) {
            binding.getRoot().postDelayed(this::updateConnectionStatus, 2000);
        }
    }

    /**
     * Formats the TTL seconds into a human-readable string.
     *
     * @param ttlSeconds the TTL in seconds
     * @return formatted TTL string
     */
    private String formatTTL(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return "Expired";
        }

        long hours = ttlSeconds / 3600;
        long minutes = (ttlSeconds % 3600) / 60;
        long seconds = ttlSeconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
