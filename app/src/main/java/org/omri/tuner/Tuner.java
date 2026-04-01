package org.omri.tuner;

import java.util.List;
import org.omri.radioservice.RadioService;

public interface Tuner {
    void initializeTuner();
    void deInitializeTuner();
    void suspendTuner();
    void resumeTuner();
    void startRadioService(RadioService radioService);
    void stopRadioService();
    void startRadioServiceScan();
    void stopRadioServiceScan();
    TunerStatus getTunerStatus();
    TunerType getTunerType();
    List<RadioService> getRadioServices();
    RadioService getCurrentRunningRadioService();
    String getHardwareVersion();
    String getSoftwareVersion();
    java.util.ArrayList<RadioService> getLinkedRadioServices(RadioService service);
    void subscribe(TunerListener tunerListener);
    void unsubscribe(TunerListener tunerListener);
}
