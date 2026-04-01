package org.omri.radio.impl;

import android.content.Context;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.omri.radio.Radio;
import org.omri.radio.RadioErrorCode;
import org.omri.radio.RadioStatus;
import org.omri.radio.RadioStatusListener;
import org.omri.radioservice.RadioService;
import org.omri.tuner.ReceptionQuality;
import org.omri.tuner.Tuner;
import org.omri.tuner.TunerListener;
import org.omri.tuner.TunerStatus;
import org.omri.tuner.TunerType;

public class RadioImpl extends Radio implements TunerListener, UsbHelper.UsbHelperCallback {
    public static final String RADIO_INIT_OPT_DEMO_MODE = "demo_mode";
    public static final String RADIO_INIT_OPT_RAW_RECORDING_PATH = "raw_recording_path";
    public static final String RADIO_INIT_OPT_VERBOSE_NATIVE_LOGS = "verbose_native_logs";
    public static final String SERVICE_SEARCH_OPT_DELETE_SERVICES = "delete_services";
    public static final String SERVICE_SEARCH_OPT_HYBRID_SCAN = "hybrid_scan";

    private final List<Tuner> mTunerList = new CopyOnWriteArrayList<>();
    private final List<RadioService> mRadioserviceList = new CopyOnWriteArrayList<>();
    private final List<RadioStatusListener> mRadioStatusListeners = new CopyOnWriteArrayList<>();

    public RadioImpl() {
    }

    @Override
    public void tunerStatusChanged(Tuner tuner, TunerStatus status) { }
    @Override
    public void tunerScanStarted(Tuner tuner) { }
    @Override
    public void tunerScanProgress(Tuner tuner, int progress, int total) { }
    @Override
    public void tunerScanServiceFound(Tuner tuner, RadioService radioService) { }
    @Override
    public void tunerScanFinished(Tuner tuner) { }
    @Override
    public void radioServiceStarted(Tuner tuner, RadioService radioService) { }
    @Override
    public void radioServiceStopped(Tuner tuner, RadioService radioService) { }
    @Override
    public void tunerReceptionStatistics(Tuner tuner, boolean sync, ReceptionQuality quality, int snr) { }
    @Override
    public void dabDateTime(Tuner tuner, Date date) { }
    @Override
    public void tunerRawData(Tuner tuner, byte[] data) { }

    @Override
    public void UsbTunerDeviceAttached(android.hardware.usb.UsbDevice device) { }
    @Override
    public void UsbTunerDeviceDetached(android.hardware.usb.UsbDevice device) { }
}
