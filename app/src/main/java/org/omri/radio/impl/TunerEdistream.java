package org.omri.radio.impl;

import java.util.ArrayList;
import java.util.List;
import org.omri.radioservice.RadioService;
import org.omri.tuner.Tuner;
import org.omri.tuner.TunerListener;
import org.omri.tuner.TunerStatus;
import org.omri.tuner.TunerType;

public class TunerEdistream implements Tuner {
    private static final String TAG = "TunerEdistream";
    private TunerStatus mTunerStatus = TunerStatus.TUNER_STATUS_NOT_INITIALIZED;
    private final List<RadioService> mServices = new ArrayList<>();
    private final List<TunerListener> mTunerListeners = new ArrayList<>();
    private RadioService mCurrentRunningService = null;

    @Override public void initializeTuner() { }
    @Override public void deInitializeTuner() { }
    @Override public void suspendTuner() { }
    @Override public void resumeTuner() { }
    @Override public void startRadioService(RadioService radioService) { }
    @Override public void stopRadioService() { }
    @Override public void startRadioServiceScan() { }
    @Override public void stopRadioServiceScan() { }
    @Override public TunerStatus getTunerStatus() { return this.mTunerStatus; }
    @Override public TunerType getTunerType() { return TunerType.TUNER_TYPE_IP_EDI; }
    @Override public List<RadioService> getRadioServices() { return this.mServices; }
    @Override public RadioService getCurrentRunningRadioService() { return this.mCurrentRunningService; }
    @Override public String getHardwareVersion() { return ""; }
    @Override public String getSoftwareVersion() { return ""; }
    @Override public ArrayList<RadioService> getLinkedRadioServices(RadioService service) { return new ArrayList<>(); }
    @Override public void subscribe(TunerListener listener) { }
    @Override public void unsubscribe(TunerListener listener) { }
}
