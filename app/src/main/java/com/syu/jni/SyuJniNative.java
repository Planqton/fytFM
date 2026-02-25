package com.syu.jni;

import android.os.Bundle;
import android.util.Log;

/**
 * SyuJniNative - Direkter Zugriff auf libsyu_jni.so
 *
 * Diese Klasse muss im Package com.syu.jni sein, da die native Library
 * die JNI-Methoden unter diesem Package-Namen registriert!
 */
public class SyuJniNative {
    private static final String TAG = "SyuJniNative";

    private static SyuJniNative instance;
    private static boolean libraryLoaded = false;

    static {
        try {
            System.loadLibrary("syu_jni");
            libraryLoaded = true;
            Log.i(TAG, "libsyu_jni.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libsyu_jni.so: " + e.getMessage());
            libraryLoaded = false;
        }
    }

    private SyuJniNative() {}

    public static synchronized SyuJniNative getInstance() {
        if (instance == null) {
            instance = new SyuJniNative();
        }
        return instance;
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    /**
     * Native JNI command method
     * This is the main entry point for all SYU JNI commands
     */
    public native int syu_jni_command(int cmd, Object inData, Object outData);

    /**
     * Mute/Unmute the amplifier
     * Command 6 = mute_amp
     *
     * @param mute true = mute, false = unmute
     * @return 0 on success
     */
    public int muteAmp(boolean mute) {
        if (!libraryLoaded) {
            Log.w(TAG, "Library not loaded");
            return -1;
        }

        try {
            Bundle inBundle = new Bundle();
            inBundle.putInt("param0", mute ? 1 : 0);
            int result = syu_jni_command(6, inBundle, null);
            Log.i(TAG, "muteAmp(" + mute + ") = " + result);
            return result;
        } catch (Throwable e) {
            Log.e(TAG, "muteAmp failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Get amplifier state
     * Command 7 = get_amp_state
     *
     * @return 0 = unmuted, 1 = muted, -1 = error
     */
    public int getAmpState() {
        if (!libraryLoaded) {
            return -1;
        }

        try {
            Bundle outBundle = new Bundle();
            int result = syu_jni_command(7, null, outBundle);
            if (result == 0) {
                return outBundle.getInt("param0", -1);
            }
            return -1;
        } catch (Throwable e) {
            Log.e(TAG, "getAmpState failed: " + e.getMessage());
            return -1;
        }
    }
}
