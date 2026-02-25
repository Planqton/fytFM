package at.planqton.fytfm;

import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * SyuJniHelper - Wrapper für com.syu.jni.SyuJniNative
 *
 * Ermöglicht Zugriff auf System-Level Funktionen wie Verstärker-Mute
 */
public class SyuJniHelper {
    private static final String TAG = "SyuJniHelper";
    private static final String SYU_JNI_CLASS = "com.syu.jni.SyuJniNative";

    // JNI Command für Amplifier Mute
    private static final int CMD_MUTE_AMP = 0x6;

    private static SyuJniHelper instance;
    private Object syuJniInstance;
    private Method syuJniCommandMethod;
    private boolean isAvailable = false;

    private SyuJniHelper() {
        init();
    }

    public static synchronized SyuJniHelper getInstance() {
        if (instance == null) {
            instance = new SyuJniHelper();
        }
        return instance;
    }

    private void init() {
        try {
            Class<?> syuJniClass = Class.forName(SYU_JNI_CLASS);

            // SyuJniNative ist ein Singleton mit getInstance()
            Method getInstanceMethod = syuJniClass.getMethod("getInstance");
            syuJniInstance = getInstanceMethod.invoke(null);

            // syu_jni_command(int cmd, Object inData, Object outData)
            syuJniCommandMethod = syuJniClass.getMethod("syu_jni_command", int.class, Object.class, Object.class);

            isAvailable = true;
            Log.i(TAG, "SyuJniNative is available");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "SyuJniNative not found: " + e.getMessage());
            isAvailable = false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to init SyuJniNative: " + e.getMessage());
            isAvailable = false;
        }
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    /**
     * Mute/Unmute den Verstärker (AMP)
     * Dies ist der Hardware-Mute der tatsächlich das Audio stumm schaltet
     *
     * @param mute true = mute, false = unmute
     * @return 0 bei Erfolg, sonst Fehlercode
     */
    public int muteAmp(boolean mute) {
        if (!isAvailable) {
            Log.w(TAG, "SyuJniNative not available");
            return -1;
        }

        try {
            Bundle inBundle = new Bundle();
            inBundle.putInt("param0", mute ? 1 : 0);

            int result = (int) syuJniCommandMethod.invoke(syuJniInstance, CMD_MUTE_AMP, inBundle, null);
            Log.i(TAG, "muteAmp(" + mute + ") = " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "muteAmp failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Amplifier Status abfragen
     * @return 0 = unmuted, 1 = muted, -1 = error
     */
    public int getAmpState() {
        if (!isAvailable) {
            return -1;
        }

        try {
            Bundle outBundle = new Bundle();
            int result = (int) syuJniCommandMethod.invoke(syuJniInstance, 0x7, null, outBundle);
            if (result == 0) {
                return outBundle.getInt("param0", -1);
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "getAmpState failed: " + e.getMessage());
            return -1;
        }
    }
}
