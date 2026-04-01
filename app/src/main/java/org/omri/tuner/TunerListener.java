package org.omri.tuner;

import org.omri.radioservice.RadioService;
import java.util.Date;

public interface TunerListener {
    void tunerStatusChanged(Tuner tuner, TunerStatus status);
    void tunerScanStarted(Tuner tuner);
    void tunerScanProgress(Tuner tuner, int progress, int total);
    void tunerScanServiceFound(Tuner tuner, RadioService radioService);
    void tunerScanFinished(Tuner tuner);
    void radioServiceStarted(Tuner tuner, RadioService radioService);
    void radioServiceStopped(Tuner tuner, RadioService radioService);
    void tunerReceptionStatistics(Tuner tuner, boolean sync, ReceptionQuality quality, int snr);
    void dabDateTime(Tuner tuner, Date date);
    void tunerRawData(Tuner tuner, byte[] data);
}
