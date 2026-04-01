package org.omri.radio.impl;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceDab;
import org.omri.radioservice.RadioServiceDabEdi;

/**
 * USB DAB device helper - bridges Java to native libirtdab.so.
 * Cleaned up from DAB-Z decompiled source.
 */
public class UsbHelper {
    private static final String ACTION_USB_PERMISSION = "de.irt.usbhelper.USB_PERMISSION";
    private static final String TAG = "UsbHelper";
    private static UsbHelper mInstance = null;
    private static String mRawRecordingPath = "";
    private static boolean mRedirectCoutToALog = false;
    private static UsbHelperCallback mUsbCb;
    private final Context mContext;
    private UsbManager mUsbManager;
    private PendingIntent mUsbPermissionIntent;
    private HashMap<String, UsbDevice> mUsbDeviceList = null;
    private boolean mPermissionPending = false;
    private UsbDevice mPendingPermissionDevice = null;
    private BroadcastReceiver mUsbBroadcastReceiver;

    public interface UsbHelperCallback {
        void UsbTunerDeviceAttached(UsbDevice usbDevice);
        void UsbTunerDeviceDetached(UsbDevice usbDevice);
    }

    // Native methods - implemented in libirtdab.so
    private native void created(boolean verbose, String rawRecordingPath);
    private native void deviceAttached(TunerUsb tunerUsb);
    private native void deviceDetached(String deviceName);
    private native void devicePermission(String deviceName, boolean granted);
    private native void startServiceScan(String deviceName);
    private native void stopServiceScan(String deviceName);
    private native void startSrv(String deviceName, RadioServiceDab service);
    private native void stopSrv(String deviceName);
    private native void tuneFreq(String deviceName, long frequencyKHz);
    private native String getHardwareVersion(String deviceName);
    private native String getSoftwareVersion(String deviceName);
    private native boolean getDirectBulkTransferEnabled(String deviceName);
    private native void setDirectBulkTransferEnabled(String deviceName, boolean enabled);
    private native ArrayList<RadioServiceDab> getLinkedServices(String deviceName, RadioServiceDab service);
    private native void demoServiceStart(RadioService radioService);
    private native void demoServiceStop();
    private native void demoTunerAttached(DemoTuner demoTuner);
    private native void demoTunerDetached(DemoTuner demoTuner);
    private native void ediFlushBuffer();
    private native void ediStreamData(byte[] data, int length);
    private native void ediTunerAttached(TunerEdistream tunerEdistream);
    private native void ediTunerDetached(TunerEdistream tunerEdistream);
    private native void startEdiStream(TunerEdistream tunerEdistream, RadioServiceDabEdi service);

    // Methods called by native via JNI (must be accessible)
    public void closeDeviceConnection(UsbDeviceConnection connection) {
        try { if (connection != null) connection.close(); }
        catch (Exception e) { Log.e(TAG, "closeDeviceConnection: " + e.getMessage()); }
    }

    public UsbDeviceConnection openDevice(UsbDevice device) {
        try { return mUsbManager.openDevice(device); }
        catch (SecurityException e) { Log.e(TAG, "openDevice: " + e.getMessage()); return null; }
    }

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("fec");
            System.loadLibrary("irtdab");
            Log.i(TAG, "Native DAB libraries loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libs: " + e.getMessage());
        }
    }

    private UsbHelper(Context context) {
        mUsbBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                UsbDevice device = intent.getParcelableExtra("device");
                String action = intent.getAction();
                if (device == null || action == null) return;

                Executors.newCachedThreadPool().execute(() -> {
                    switch (action) {
                        case "android.hardware.usb.action.USB_DEVICE_ATTACHED":
                            if (mUsbCb != null) mUsbCb.UsbTunerDeviceAttached(device);
                            break;
                        case "android.hardware.usb.action.USB_DEVICE_DETACHED":
                            deviceDetached(device.getDeviceName());
                            if (mUsbCb != null) mUsbCb.UsbTunerDeviceDetached(device);
                            break;
                        case ACTION_USB_PERMISSION:
                            mPermissionPending = false;
                            devicePermission(device.getDeviceName(),
                                intent.getBooleanExtra("permission", false));
                            if (mPendingPermissionDevice != null && !device.equals(mPendingPermissionDevice)) {
                                requestPermission(mPendingPermissionDevice);
                            } else {
                                mPendingPermissionDevice = null;
                            }
                            break;
                    }
                });
            }
        };

        Context appCtx = context.getApplicationContext();
        mContext = appCtx;
        if (appCtx != null) {
            mUsbManager = (UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(appCtx.getPackageName());
            mUsbPermissionIntent = PendingIntent.getBroadcast(appCtx, 0, intent, flags);

            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
            filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
            appCtx.registerReceiver(mUsbBroadcastReceiver, filter);

            try {
                created(mRedirectCoutToALog, mRawRecordingPath);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Native created() failed: " + e.getMessage());
            }
        }
    }

    public static void create(Context context, UsbHelperCallback callback, boolean verbose, String rawPath) {
        if (mInstance == null) {
            mUsbCb = callback;
            mRedirectCoutToALog = verbose;
            mRawRecordingPath = rawPath != null ? rawPath : "";
            mInstance = new UsbHelper(context);
        }
    }

    public static UsbHelper getInstance() {
        return mInstance;
    }

    public void destroyInstance() {
        try {
            mContext.unregisterReceiver(mUsbBroadcastReceiver);
        } catch (Exception ignored) {}
        if (mUsbDeviceList != null) mUsbDeviceList.clear();
        mUsbCb = null;
        mInstance = null;
    }

    public void requestPermission(UsbDevice device) {
        if (mPermissionPending) {
            mPendingPermissionDevice = device;
        } else {
            mPermissionPending = true;
            mUsbManager.requestPermission(device, mUsbPermissionIntent);
        }
    }

    public void attachDevice(TunerUsb tunerUsb) {
        try { deviceAttached(tunerUsb); }
        catch (UnsatisfiedLinkError e) { Log.e(TAG, "deviceAttached native error: " + e.getMessage()); }
    }

    public void removeDevice(UsbDevice device) {
        if (device != null) {
            try { deviceDetached(device.getDeviceName()); }
            catch (UnsatisfiedLinkError e) { Log.e(TAG, "deviceDetached native error: " + e.getMessage()); }
        }
    }

    public List<UsbDevice> scanForSpecificDevices(List<Pair<Integer, Integer>> vidPidList) {
        ArrayList<UsbDevice> result = new ArrayList<>();
        if (mUsbManager != null) {
            HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
            mUsbDeviceList = deviceList;
            for (UsbDevice device : deviceList.values()) {
                for (Pair<Integer, Integer> vidPid : vidPidList) {
                    if (device.getVendorId() == vidPid.first && device.getProductId() == vidPid.second) {
                        result.add(device);
                    }
                }
            }
        }
        return result;
    }

    public void startEnsembleScan(String deviceName) {
        try { startServiceScan(deviceName); }
        catch (UnsatisfiedLinkError e) { Log.e(TAG, "startServiceScan native error: " + e.getMessage()); }
    }

    public void stopEnsembleScan(String deviceName) {
        try { stopServiceScan(deviceName); }
        catch (UnsatisfiedLinkError e) { Log.e(TAG, "stopServiceScan native error: " + e.getMessage()); }
    }

    public void startService(String deviceName, RadioServiceDab service) {
        try { startSrv(deviceName, service); }
        catch (UnsatisfiedLinkError e) { Log.e(TAG, "startSrv native error: " + e.getMessage()); }
    }

    public void stopService(String deviceName) {
        try { stopSrv(deviceName); }
        catch (UnsatisfiedLinkError e) { Log.e(TAG, "stopSrv native error: " + e.getMessage()); }
    }

    public void tuneFrequencyKHz(String deviceName, long freq) {
        try { tuneFreq(deviceName, freq); }
        catch (UnsatisfiedLinkError e) { Log.e(TAG, "tuneFreq native error: " + e.getMessage()); }
    }

    public String getHwVersion(String deviceName) {
        try { return getHardwareVersion(deviceName); }
        catch (UnsatisfiedLinkError e) { return ""; }
    }

    public String getSwVersion(String deviceName) {
        try { return getSoftwareVersion(deviceName); }
        catch (UnsatisfiedLinkError e) { return ""; }
    }

    public boolean getDirectBulkTransferModeEnabled(String deviceName) {
        try { return getDirectBulkTransferEnabled(deviceName); }
        catch (UnsatisfiedLinkError e) { return true; }
    }

    public void setDirectBulkTransferModeEnabled(String deviceName, boolean enabled) {
        try { setDirectBulkTransferEnabled(deviceName, enabled); }
        catch (UnsatisfiedLinkError e) { /* ignore */ }
    }

    public ArrayList<RadioServiceDab> getLinkedDabServices(String deviceName, RadioServiceDab service) {
        try { return getLinkedServices(deviceName, service); }
        catch (UnsatisfiedLinkError e) { return new ArrayList<>(); }
    }
}
