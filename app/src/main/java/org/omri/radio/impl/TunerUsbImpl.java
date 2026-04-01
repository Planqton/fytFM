package org.omri.radio.impl;

import android.hardware.usb.UsbDevice;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceDab;
import org.omri.radioservice.RadioServiceType;
import org.omri.tuner.ReceptionQuality;
import org.omri.tuner.Tuner;
import org.omri.tuner.TunerListener;
import org.omri.tuner.TunerStatus;
import org.omri.tuner.TunerType;

/**
 * USB DAB Tuner implementation - receives callbacks from native libirtdab.
 * Cleaned up from DAB-Z decompiled source.
 */
public class TunerUsbImpl implements TunerUsb {
    private static final String TAG = "TunerUsb";
    private final UsbDevice mUsbDevice;
    private final TunerType mTunertype = TunerType.TUNER_TYPE_DAB;
    private TunerStatus mTunerStatus = TunerStatus.TUNER_STATUS_NOT_INITIALIZED;
    private final List<RadioService> mServices = Collections.synchronizedList(new ArrayList<>());
    private final List<RadioService> mScannedServices = Collections.synchronizedList(new ArrayList<>());
    private boolean mIsScanning = false;
    private final List<TunerListener> mTunerlisteners = Collections.synchronizedList(new ArrayList<>());
    private RadioServiceDab mCurrentlyRunningService = null;

    public TunerUsbImpl(UsbDevice usbDevice) {
        this.mUsbDevice = usbDevice;
    }

    // === Callbacks from native libirtdab.so ===

    @Override
    public void callBack(int type) {
        TunerUsbCallbackTypes cbType = TunerUsbCallbackTypes.getTypeByValue(type);
        Log.i(TAG, "callBack: " + cbType.name() + " (" + type + ")");

        switch (cbType) {
            case TUNER_READY:
                if (mIsScanning) {
                    mIsScanning = false;
                    mTunerStatus = TunerStatus.TUNER_STATUS_INITIALIZED;
                    // Scan finished - notify
                    synchronized (mTunerlisteners) {
                        for (TunerListener l : mTunerlisteners) {
                            l.tunerScanFinished(this);
                        }
                    }
                } else {
                    mTunerStatus = TunerStatus.TUNER_STATUS_INITIALIZED;
                    synchronized (mTunerlisteners) {
                        for (TunerListener l : mTunerlisteners) {
                            l.tunerStatusChanged(this, mTunerStatus);
                        }
                    }
                }
                break;
            case TUNER_FAILED:
                mTunerStatus = TunerStatus.TUNER_STATUS_ERROR;
                synchronized (mTunerlisteners) {
                    for (TunerListener l : mTunerlisteners) {
                        l.tunerStatusChanged(this, mTunerStatus);
                    }
                }
                break;
            case TUNER_SCAN_IN_PROGRESS:
                mIsScanning = true;
                mTunerStatus = TunerStatus.TUNER_STATUS_SCANNING;
                synchronized (mTunerlisteners) {
                    for (TunerListener l : mTunerlisteners) {
                        l.tunerScanStarted(this);
                    }
                }
                break;
            case SERVICELIST_READY:
                boolean wasScanningServiceList = mIsScanning;
                if (mIsScanning) {
                    mIsScanning = false;
                    Log.i(TAG, "SERVICELIST_READY during scan -> scan finished");
                }
                mTunerStatus = TunerStatus.TUNER_STATUS_INITIALIZED;
                synchronized (mTunerlisteners) {
                    for (TunerListener l : mTunerlisteners) {
                        l.tunerStatusChanged(this, TunerStatus.SERVICES_LIST_READY);
                        if (wasScanningServiceList) {
                            l.tunerScanFinished(this);
                        }
                    }
                }
                break;
            case VISUALLIST_READY:
                synchronized (mTunerlisteners) {
                    for (TunerListener l : mTunerlisteners) {
                        l.tunerStatusChanged(this, TunerStatus.VISUALS_LIST_READY);
                    }
                }
                break;
        }
    }

    @Override
    public void scanProgressCallback(int progress, int total) {
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.tunerScanProgress(this, progress, total);
            }
        }
    }

    @Override
    public void serviceFound(RadioServiceDab service) {
        if (service == null) return;
        RadioServiceManager.getInstance().addRadioService(service);
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.tunerScanServiceFound(this, service);
            }
        }
    }

    @Override
    public void serviceStarted(RadioServiceDab service) {
        mCurrentlyRunningService = service;
        if (service != null) {
            synchronized (mTunerlisteners) {
                for (TunerListener l : mTunerlisteners) {
                    l.radioServiceStarted(this, service);
                }
            }
        }
    }

    @Override
    public void serviceStopped(RadioServiceDab service) {
        mCurrentlyRunningService = null;
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.radioServiceStopped(this, service);
            }
        }
    }

    @Override
    public void receptionStatistics(boolean sync, int quality, int snr) {
        if (quality > 5) quality = 5;
        int q = quality;
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.tunerReceptionStatistics(this, sync, ReceptionQuality.values()[q], snr);
            }
        }
    }

    public void dabTimeUpdate(Date date) {
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.dabDateTime(this, date);
            }
        }
    }

    // === Tuner interface ===

    @Override
    public void initializeTuner() {
        if (mTunerStatus == TunerStatus.TUNER_STATUS_NOT_INITIALIZED) {
            UsbHelper helper = UsbHelper.getInstance();
            if (helper == null) {
                Log.e(TAG, "UsbHelper null");
                return;
            }
            helper.attachDevice(this);
        }
    }

    @Override
    public void deInitializeTuner() {
        UsbHelper helper = UsbHelper.getInstance();
        if (helper != null) {
            helper.stopService(mUsbDevice.getDeviceName());
            helper.removeDevice(mUsbDevice);
        }
        mTunerStatus = TunerStatus.TUNER_STATUS_NOT_INITIALIZED;
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.tunerStatusChanged(this, mTunerStatus);
            }
            mTunerlisteners.clear();
        }
        synchronized (mServices) { mServices.clear(); }
        mCurrentlyRunningService = null;
    }

    @Override
    public void startRadioService(RadioService service) {
        UsbHelper helper = UsbHelper.getInstance();
        if (service.getRadioServiceType() == RadioServiceType.RADIOSERVICE_TYPE_DAB && helper != null) {
            helper.startService(mUsbDevice.getDeviceName(), (RadioServiceDab) service);
        }
    }

    @Override
    public void stopRadioService() {
        UsbHelper helper = UsbHelper.getInstance();
        if (helper != null) helper.stopService(mUsbDevice.getDeviceName());
    }

    @Override
    public void startRadioServiceScan() {
        if (getCurrentRunningRadioService() != null) {
            stopRadioService();
            SystemClock.sleep(300);
        }
        UsbHelper helper = UsbHelper.getInstance();
        if (helper == null) {
            Log.e(TAG, "startRadioServiceScan: UsbHelper null");
            return;
        }
        helper.startEnsembleScan(mUsbDevice.getDeviceName());
        synchronized (mScannedServices) { mScannedServices.clear(); }
    }

    @Override
    public void stopRadioServiceScan() {
        UsbHelper helper = UsbHelper.getInstance();
        if (helper != null) helper.stopEnsembleScan(mUsbDevice.getDeviceName());
    }

    @Override
    public void suspendTuner() {
        UsbHelper helper = UsbHelper.getInstance();
        if (helper != null) helper.stopService(mUsbDevice.getDeviceName());
        mTunerStatus = TunerStatus.TUNER_STATUS_SUSPENDED;
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.tunerStatusChanged(this, mTunerStatus);
            }
        }
    }

    @Override
    public void resumeTuner() {
        mTunerStatus = TunerStatus.TUNER_STATUS_INITIALIZED;
        synchronized (mTunerlisteners) {
            for (TunerListener l : mTunerlisteners) {
                l.tunerStatusChanged(this, mTunerStatus);
            }
        }
    }

    @Override public TunerStatus getTunerStatus() { return mTunerStatus; }
    @Override public TunerType getTunerType() { return mTunertype; }
    @Override public UsbDevice getUsbDevice() { return mUsbDevice; }
    @Override public RadioService getCurrentRunningRadioService() { return mCurrentlyRunningService; }

    @Override
    public List<RadioService> getRadioServices() {
        return mTunerStatus == TunerStatus.TUNER_STATUS_INITIALIZED
            ? RadioServiceManager.getInstance().getRadioServices(RadioServiceType.RADIOSERVICE_TYPE_DAB)
            : new ArrayList<>();
    }

    @Override public String getHardwareVersion() {
        UsbHelper h = UsbHelper.getInstance();
        return h != null ? h.getHwVersion(mUsbDevice.getDeviceName()) : "";
    }

    @Override public String getSoftwareVersion() {
        UsbHelper h = UsbHelper.getInstance();
        return h != null ? h.getSwVersion(mUsbDevice.getDeviceName()) : "";
    }

    @Override public ArrayList<RadioService> getLinkedRadioServices(RadioService service) { return new ArrayList<>(); }
    @Override public boolean getDirectBulkTransferModeEnabled() {
        UsbHelper h = UsbHelper.getInstance();
        return h != null ? h.getDirectBulkTransferModeEnabled(mUsbDevice.getDeviceName()) : true;
    }
    @Override public void setDirectBulkTransferModeEnabled(boolean enabled) {
        UsbHelper h = UsbHelper.getInstance();
        if (h != null) h.setDirectBulkTransferModeEnabled(mUsbDevice.getDeviceName(), enabled);
    }

    @Override
    public void subscribe(TunerListener listener) {
        synchronized (mTunerlisteners) {
            if (!mTunerlisteners.contains(listener)) mTunerlisteners.add(listener);
        }
    }

    @Override
    public void unsubscribe(TunerListener listener) {
        synchronized (mTunerlisteners) { mTunerlisteners.remove(listener); }
    }
}
