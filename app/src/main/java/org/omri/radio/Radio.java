package org.omri.radio;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.omri.radio.impl.TunerUsbImpl;
import org.omri.radio.impl.UsbHelper;
import org.omri.tuner.Tuner;
import org.omri.tuner.TunerType;

/**
 * Radio API for DAB+ USB dongles.
 * Uses UsbHelper + TunerUsbImpl from DAB-Z's OMRI architecture
 * which communicate with native libirtdab.so.
 */
public class Radio implements UsbHelper.UsbHelperCallback {
    private static final String TAG = "OmriRadio";
    private static Radio INSTANCE;

    private Context mContext;
    private boolean mInitialized = false;
    private final List<Tuner> mTunerList = new CopyOnWriteArrayList<>();
    private final List<RadioStatusListener> mStatusListeners = new CopyOnWriteArrayList<>();

    private static final int DAB_VENDOR_ID = 0x16C0;
    private static final int DAB_PRODUCT_ID = 0x05DC;

    public static Radio getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new Radio();
        }
        return INSTANCE;
    }

    public RadioErrorCode initialize(Context context, Bundle opts) {
        if (mInitialized) return RadioErrorCode.RADIO_ERROR_ALREADY_INITIALIZED;

        mContext = context.getApplicationContext();

        // Initialize UsbHelper which loads native libs and sets up USB monitoring
        boolean verbose = opts != null && opts.getBoolean("verbose_native_logs", false);
        UsbHelper.create(mContext, this, verbose, "");

        mInitialized = true;
        Log.i(TAG, "Radio initialized");

        // Scan for already connected DAB devices
        scanForDabDevices();

        return RadioErrorCode.RADIO_ERROR_OK;
    }

    public void deInitialize() {
        for (Tuner tuner : mTunerList) {
            tuner.deInitializeTuner();
        }
        mTunerList.clear();

        UsbHelper helper = UsbHelper.getInstance();
        if (helper != null) helper.destroyInstance();

        mInitialized = false;
        Log.i(TAG, "Radio deinitialized");
    }

    public List<Tuner> getAvailableTuners(TunerType type) {
        List<Tuner> result = new ArrayList<>();
        for (Tuner tuner : mTunerList) {
            if (tuner.getTunerType() == type) {
                result.add(tuner);
            }
        }
        return result;
    }

    public void registerRadioStatusListener(RadioStatusListener listener) {
        if (!mStatusListeners.contains(listener)) {
            mStatusListeners.add(listener);
        }
    }

    public void unregisterRadioStatusListener(RadioStatusListener listener) {
        mStatusListeners.remove(listener);
    }

    private void scanForDabDevices() {
        UsbHelper helper = UsbHelper.getInstance();
        if (helper == null) {
            Log.w(TAG, "UsbHelper not available");
            return;
        }

        List<Pair<Integer, Integer>> dabDevices = new ArrayList<>();
        dabDevices.add(new Pair<>(DAB_VENDOR_ID, DAB_PRODUCT_ID));
        List<android.hardware.usb.UsbDevice> devices = helper.scanForSpecificDevices(dabDevices);

        Log.i(TAG, "Found " + devices.size() + " DAB USB device(s)");

        for (android.hardware.usb.UsbDevice device : devices) {
            Log.i(TAG, "DAB device: " + device.getDeviceName() +
                " VID=" + device.getVendorId() + " PID=" + device.getProductId());

            // Check USB permission
            UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
            if (usbManager != null && usbManager.hasPermission(device)) {
                // Permission granted - create tuner
                attachTuner(device);
            } else if (usbManager != null) {
                // Request permission - tuner will be created in callback
                Log.i(TAG, "Requesting USB permission for " + device.getDeviceName());
                helper.requestPermission(device);
            }
        }
    }

    private void attachTuner(android.hardware.usb.UsbDevice device) {
        TunerUsbImpl tuner = new TunerUsbImpl(device);
        mTunerList.add(tuner);
        Log.i(TAG, "Tuner created for " + device.getDeviceName());

        for (RadioStatusListener listener : mStatusListeners) {
            listener.tunerAttached(tuner);
        }
    }

    // === UsbHelperCallback ===

    @Override
    public void UsbTunerDeviceAttached(android.hardware.usb.UsbDevice device) {
        Log.i(TAG, "USB DAB device attached: " + device.getDeviceName());
        attachTuner(device);
    }

    @Override
    public void UsbTunerDeviceDetached(android.hardware.usb.UsbDevice device) {
        Log.i(TAG, "USB DAB device detached: " + device.getDeviceName());
        for (RadioStatusListener listener : mStatusListeners) {
            // Find matching tuner
            for (Tuner tuner : mTunerList) {
                if (tuner instanceof TunerUsbImpl) {
                    TunerUsbImpl usbTuner = (TunerUsbImpl) tuner;
                    if (usbTuner.getUsbDevice().equals(device)) {
                        listener.tunerDetached(tuner);
                        mTunerList.remove(tuner);
                        break;
                    }
                }
            }
        }
    }
}
