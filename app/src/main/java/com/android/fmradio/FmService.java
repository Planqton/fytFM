package com.android.fmradio;

import android.util.Log;

/**
 * FmService - Empfängt RDS Callbacks von der nativen Library (libfmjni.so)
 *
 * WICHTIG: Diese Klasse muss im Package com.android.fmradio sein,
 * da die native Library diese Callback-Methoden über JNI aufruft!
 *
 * Die Callbacks werden von der nativen Library aufgerufen wenn RDS-Daten empfangen werden.
 * Der RdsManager pollt die Daten aktiv - diese Callbacks dienen nur als Benachrichtigung.
 */
public class FmService {
    private static final String TAG = "FmService";

    // RDS Event Types (aus NavRadio Analyse)
    public static final int RDS_CMD_NOTIFY_PTY_TYPE = 2;    // PTY
    public static final int RDS_CMD_NOTIFY_AF_SWITCH = 9;   // AF: chip retuned to alternative freq
    public static final int RDS_CMD_NOTIFY_PI = 14;         // PI Code

    // Gespeicherte RDS-Daten
    private static int currentPi = 0;
    private static int currentPty = 0;
    /** Alternative-Frequency-Wert vom letzten AF-Switch-Event (Param1 vom
     *  cmjni-Layer). 0 = kein AF aktiv. Einheit = wie chip-emittiert. */
    private static int currentAfRaw = 0;

    // Listener für RDS-Updates (optional)
    private static RdsListener rdsListener = null;

    public interface RdsListener {
        void onRdsEvent(int eventType, int param1, int param2, int param3);
    }

    public static void setRdsListener(RdsListener listener) {
        rdsListener = listener;
    }

    /**
     * RDS Callback - wird von der nativen Library aufgerufen
     *
     * Event Types (aus NavRadio+ Analyse):
     * - Event 2  = PTY (Program Type)
     * - Event 10 = PS Name Notification
     * - Event 11 = RT (Radio Text) Notification
     * - Event 14 = PI Code
     */
    public static int Rdscallback(int eventType, int param1, int param2, int param3) {
        Log.d(TAG, "Rdscallback: type=" + eventType +
              ", p1=" + param1 + ", p2=" + param2 + ", p3=" + param3);

        // PI / PTY / AF aus Callbacks speichern
        switch (eventType) {
            case RDS_CMD_NOTIFY_PTY_TYPE: // Event 2 = PTY
                currentPty = param1;
                Log.i(TAG, "*** PTY RECEIVED: " + param1 + " ***");
                break;
            case RDS_CMD_NOTIFY_AF_SWITCH: // Event 9 = AF Switch
                currentAfRaw = param1;
                Log.i(TAG, "*** AF SWITCH: chip retuned to raw=" + param1 + " ***");
                break;
            case RDS_CMD_NOTIFY_PI: // Event 14 = PI Code
                currentPi = param1;
                Log.i(TAG, "*** PI CODE RECEIVED: 0x" + String.format("%04X", param1 & 0xFFFF) + " ***");
                break;
        }

        if (rdsListener != null) {
            rdsListener.onRdsEvent(eventType, param1, param2, param3);
        }

        return 0;
    }

    /**
     * Alternative Callback-Signatur mit String-Daten
     */
    public static int Rdscallback(int eventType, String data) {
        Log.d(TAG, "Rdscallback(String): type=" + eventType + ", data=" + data);
        return 0;
    }

    /**
     * Callback mit byte-Array
     */
    public static int Rdscallback(int eventType, byte[] data) {
        if (data != null) {
            Log.d(TAG, "Rdscallback(byte[]): type=" + eventType + ", len=" + data.length);
        }
        return 0;
    }

    /**
     * Allgemeiner Callback
     */
    public static int callback(int a, int b) {
        Log.d(TAG, "callback: a=" + a + ", b=" + b);
        return 0;
    }

    // Getter für PI und PTY
    public static int getPi() {
        return currentPi;
    }

    public static int getPty() {
        return currentPty;
    }

    /** Letzter AF-Switch-Wert vom Chip (raw, wie cmjni emittiert). 0 = kein AF. */
    public static int getAfRaw() {
        return currentAfRaw;
    }

    public static void clearRds() {
        currentPi = 0;
        currentPty = 0;
        currentAfRaw = 0;
    }
}
