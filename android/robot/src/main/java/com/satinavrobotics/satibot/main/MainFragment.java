package com.satinavrobotics.satibot.main;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.satinavrobotics.satibot.R;
import com.satinavrobotics.satibot.databinding.FragmentMainBinding;

import com.satinavrobotics.satibot.main.model.SubCategory;

import timber.log.Timber;

public class MainFragment extends Fragment implements OnItemClickListener<SubCategory> {

    private FragmentMainBinding binding;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    binding = FragmentMainBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
    binding.list.setAdapter(new CategoryAdapter(FeatureList.getCategories(), this));
  }

  @Override
  public void onItemClick(SubCategory subCategory) {
    Timber.d("onItemClick: %s", subCategory.getTitle());

    switch (subCategory.getTitle()) {
      case FeatureList.LOCAL_CONTROL:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_localControlFragment);
        break;
      case FeatureList.REMOTE_CONTROL:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_freeRoamFragment);
        break;

      case FeatureList.GAMEPAD_CONTROL:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_loggerFragment);
        break;

      case FeatureList.CONTROLLER:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_localControlFragment);
        break;

      case FeatureList.ROBOT_INFO:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_robotInfoFragment);
        break;

      case FeatureList.MODEL_MANAGEMENT:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_modelManagementFragment);
        break;

      case FeatureList.MAP_MANAGEMENT:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_mapManagementFragment);
        break;

      case FeatureList.POINTCLOUD_MAPPING:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_pointCloudMappingFragment);
        break;

      case FeatureList.DEPTH_MANAGEMENT:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_depthVisualizationFragment);
        break;

      case FeatureList.DEPTH_NAVIGATION:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_autonomousNavigationFragment);
        break;

      case FeatureList.PD_TUNING:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_pdTuningFragment);
        break;
    }
  }
}
