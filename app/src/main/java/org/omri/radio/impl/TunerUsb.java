package org.omri.radio.impl;

import android.hardware.usb.UsbDevice;
import org.omri.radioservice.RadioServiceDab;
import org.omri.tuner.Tuner;

interface TunerUsb extends Tuner {
    void callBack(int i);
    boolean getDirectBulkTransferModeEnabled();
    UsbDevice getUsbDevice();
    void receptionStatistics(boolean sync, int quality, int snr);
    void scanProgressCallback(int progress, int total);
    void serviceFound(RadioServiceDab service);
    void serviceStarted(RadioServiceDab service);
    void serviceStopped(RadioServiceDab service);
    void setDirectBulkTransferModeEnabled(boolean enabled);
}
