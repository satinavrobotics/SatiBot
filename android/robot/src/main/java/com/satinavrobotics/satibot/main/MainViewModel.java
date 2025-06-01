package com.satinavrobotics.satibot.main;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.satinavrobotics.satibot.main.model.SubCategory;
import com.satinavrobotics.satibot.vehicle.Vehicle;

public class MainViewModel extends ViewModel {

  private final MutableLiveData<SubCategory> selectedMode = new MutableLiveData<>();

  public void selectMode(SubCategory subCategory) {
    selectedMode.setValue(subCategory);
  }

  public LiveData<SubCategory> getSelectedMode() {
    return selectedMode;
  }

  private final MutableLiveData<String> usbData = new MutableLiveData<>();

  public void setDeviceData(String data) {
    usbData.setValue(data);
  }

  public LiveData<String> getDeviceData() {
    return usbData;
  }

  private final MutableLiveData<Vehicle> vehicle = new MutableLiveData<>();

  public void setVehicle(Vehicle data) {
    vehicle.setValue(data);
  }

  public LiveData<Vehicle> getVehicle() {
    return vehicle;
  }

  private final MutableLiveData<Boolean> usbStatus = new MutableLiveData<>();

  public void setUsbStatus(Boolean data) {
    usbStatus.setValue(data);
  }

  public LiveData<Boolean> getUsbStatus() {
    return usbStatus;
  }
}
