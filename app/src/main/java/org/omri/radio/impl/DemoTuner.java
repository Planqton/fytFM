package org.omri.radio.impl;

import java.util.ArrayList;
import java.util.List;
import org.omri.radioservice.RadioService;
import org.omri.tuner.Tuner;
import org.omri.tuner.TunerListener;
import org.omri.tuner.TunerStatus;
import org.omri.tuner.TunerType;

public class DemoTuner implements Tuner {
    private final String mInputFilesPath;
    private final TunerType mTunertype = TunerType.TUNER_TYPE_DAB;
    private final List<RadioService> mServices = new ArrayList<>();
    private final List<TunerListener> mTunerlisteners = new ArrayList<>();
    private TunerStatus mTunerStatus = TunerStatus.TUNER_STATUS_NOT_INITIALIZED;
    private RadioService mCurrentlyRunningService = null;

    public DemoTuner(String inputFilesPath) {
        this.mInputFilesPath = inputFilesPath;
    }

    public void callBack(int callbackType) { }
    public void serviceStarted(RadioService radioService) { }
    public void serviceStopped(RadioService radioService) { }

    @Override public void initializeTuner() { }
    @Override public void deInitializeTuner() { }
    @Override public void suspendTuner() { }
    @Override public void resumeTuner() { }
    @Override public void startRadioService(RadioService radioService) { }
    @Override public void stopRadioService() { }
    @Override public void startRadioServiceScan() { }
    @Override public void stopRadioServiceScan() { }
    @Override public TunerStatus getTunerStatus() { return this.mTunerStatus; }
    @Override public TunerType getTunerType() { return this.mTunertype; }
    @Override public List<RadioService> getRadioServices() { return this.mServices; }
    @Override public RadioService getCurrentRunningRadioService() { return this.mCurrentlyRunningService; }
    @Override public String getHardwareVersion() { return "demoHw"; }
    @Override public String getSoftwareVersion() { return "demoSw"; }
    @Override public ArrayList<RadioService> getLinkedRadioServices(RadioService service) { return new ArrayList<>(); }
    @Override public void subscribe(TunerListener listener) { }
    @Override public void unsubscribe(TunerListener listener) { }
}
