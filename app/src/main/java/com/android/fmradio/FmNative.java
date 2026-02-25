package com.android.fmradio;

import android.os.Bundle;
import android.util.Log;
import java.nio.charset.StandardCharsets;

/**
 * FmNative - JNI Wrapper für die System FM-Library (libfmjni.so)
 *
 * WICHTIG: Muss im Package com.android.fmradio sein, da die native Library
 * diese Klasse über JNI_OnLoad sucht!
 *
 * Native Methoden basieren auf NavRadio+ Reverse-Engineering.
 * Nur Methoden die tatsächlich in libfmjni.so registriert sind!
 */
public class FmNative {
    private static final String TAG = "FmNative";

    // fmsyu_jni Command Codes (NavRadio-Stil)
    public static final int CMD_GETMONOSTERO = 0x09;   // 9 - Mono/Stereo Status lesen
    public static final int CMD_SETMONOSTERO = 0x0a;   // 10 - Mono/Stereo setzen
    public static final int CMD_GETRSSI = 0x0b;        // 11 - RSSI abrufen
    public static final int CMD_DENSENSEDETECT = 0x0e; // 14 - LOC/Local Mode (Empfindlichkeit)
    public static final int CMD_SETGET_AREA = 0x14;    // 20 - Radio Area/Region
    public static final int CMD_RDSONOFF = 0x15;       // 21 - RDS ein/aus
    public static final int CMD_RDSGETPS = 0x1e;       // 30 - PS Name
    public static final int CMD_RDSGETTEXT = 0x1f;     // 31 - Radio Text

    // Radio Area Konstanten (basierend auf NavRadio+ Analyse)
    public static final int AREA_USA_KOREA = 0;        // USA/Korea: 87.5-108 MHz, 200kHz step
    public static final int AREA_LATIN_AMERICA = 1;    // Latin America
    public static final int AREA_EUROPE = 2;           // Europe: 87.5-108 MHz, 100kHz step
    public static final int AREA_RUSSIA = 3;           // Russia
    public static final int AREA_JAPAN = 4;            // Japan: 76-90 MHz, 100kHz step

    private static FmNative instance;
    private static boolean libraryLoaded = false;

    static {
        try {
            System.loadLibrary("fmjni");
            libraryLoaded = true;
            Log.i(TAG, "libfmjni.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libfmjni.so: " + e.getMessage());
            libraryLoaded = false;
        } catch (Exception e) {
            Log.e(TAG, "Exception loading libfmjni.so: " + e.getMessage());
            libraryLoaded = false;
        }
    }

    private FmNative() {}

    public static synchronized FmNative getInstance() {
        if (instance == null) {
            instance = new FmNative();
        }
        return instance;
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    // ========== Native Methoden - NUR die in libfmjni.so registrierten! ==========
    // Diese Liste basiert auf NavRadio+ smali Analyse

    public native boolean openDev();
    public native boolean closeDev();
    public native boolean powerUp(float frequency);
    public native boolean powerDown(int type);
    public native boolean tune(float frequency);
    public native float[] seek(float frequency, boolean isUp);
    public native int setMute(boolean mute);
    public static native short[] autoScan(int band);
    public native boolean stopScan();

    // RDS Methoden
    public native byte[] getPs();
    public native byte[] getLrText();
    public native int setRds(boolean enable);
    public native short readRds();
    public native int isRdsSupport();
    // getAFList() existiert nicht in libfmjni.so - stattdessen activeAf() verwenden

    // Sonstige
    public native int setmonostero(int mode);
    public native int getmonostero(int mode);
    public native int switchAntenna(int antenna);
    public native short activeAf();  // Gibt aktive AF-Frequenz zurück
    public native int setconfig(String config);
    public native int sqlautoScan(int band, short[] freqList, short[] rssiList);

    // DER WICHTIGSTE: NavRadio-Stil JNI für alle erweiterten Befehle
    public native int fmsyu_jni(int cmd, Object inData, Object outData);

    // ========== Wrapper Methoden ==========

    /**
     * Radio einschalten
     */
    public boolean powerOn(float frequency) {
        if (!libraryLoaded) {
            Log.e(TAG, "Library not loaded");
            return false;
        }
        try {
            Log.i(TAG, "powerOn: openDev()");
            if (!openDev()) {
                Log.e(TAG, "openDev() failed");
                return false;
            }

            Log.i(TAG, "powerOn: powerUp(" + frequency + ")");
            if (!powerUp(frequency)) {
                Log.e(TAG, "powerUp() failed");
                closeDev();
                return false;
            }

            Log.i(TAG, "FM powered on at " + frequency + " MHz");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "powerOn failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Radio ausschalten
     */
    public boolean powerOff() {
        if (!libraryLoaded) return false;
        try {
            powerDown(0);
            closeDev();
            Log.i(TAG, "FM powered off");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "powerOff failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * RSSI über fmsyu_jni abrufen
     */
    public int getrssi() {
        if (!libraryLoaded) return 0;

        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();

            int result = fmsyu_jni(CMD_GETRSSI, inBundle, outBundle);
            if (result == 0) {
                return outBundle.getInt("rssilevel", 0);
            }
        } catch (Throwable e) {
            Log.d(TAG, "getrssi via fmsyu_jni failed: " + e.getMessage());
        }

        return 0;
    }

    /**
     * PS String abrufen - probiert mehrere Methoden
     */
    public String getPsString() {
        if (!libraryLoaded) return "";

        // Methode 1: Via fmsyu_jni (NavRadio-Methode)
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();

            int result = fmsyu_jni(CMD_RDSGETPS, inBundle, outBundle);
            if (result == 0) {
                byte[] psData = outBundle.getByteArray("PSname");
                if (psData != null && psData.length > 0) {
                    String ps = cleanRdsString(new String(psData, StandardCharsets.US_ASCII));
                    if (!ps.isEmpty()) {
                        return ps;
                    }
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "fmsyu_jni getPs failed: " + e.getMessage());
        }

        // Methode 2: Direkter nativer Aufruf
        try {
            byte[] ps = getPs();
            if (ps != null && ps.length > 0) {
                String result = cleanRdsString(new String(ps, StandardCharsets.US_ASCII));
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "native getPs failed: " + e.getMessage());
        }

        return "";
    }

    /**
     * Radio Text abrufen - probiert mehrere Methoden
     */
    public String getRadioText() {
        if (!libraryLoaded) return "";

        // Methode 1: Via fmsyu_jni (NavRadio-Methode)
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();

            int result = fmsyu_jni(CMD_RDSGETTEXT, inBundle, outBundle);
            if (result == 0) {
                int textSize = outBundle.getInt("TextSize", 0);
                if (textSize > 0) {
                    byte[] textData = outBundle.getByteArray("Text");
                    if (textData != null && textData.length > 0) {
                        String rt = cleanRdsString(new String(textData, StandardCharsets.UTF_8));
                        if (!rt.isEmpty()) {
                            return rt;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "fmsyu_jni getRt failed: " + e.getMessage());
        }

        // Methode 2: Direkter nativer Aufruf
        try {
            byte[] rt = getLrText();
            if (rt != null && rt.length > 0) {
                String result = cleanRdsString(new String(rt, StandardCharsets.UTF_8));
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "native getLrText failed: " + e.getMessage());
        }

        return "";
    }

    /**
     * Bereinigt RDS-Strings
     */
    private String cleanRdsString(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\x00-\\x1F]", "").trim();
        if (s.toLowerCase().contains("not support")) {
            return "";
        }
        return s;
    }

    // ========== Dummy-Methoden für Kompatibilität ==========
    // Diese existieren nicht als native, werden aber vom Code verwendet

    public int sql_getrssi() {
        return getrssi(); // Redirect zu fmsyu_jni Version
    }

    public byte[] sqlradio_getps() {
        // Redirect zu fmsyu_jni Version
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            if (fmsyu_jni(CMD_RDSGETPS, inBundle, outBundle) == 0) {
                return outBundle.getByteArray("PSname");
            }
        } catch (Throwable e) {}
        return null;
    }

    public byte[] sqlradio_getlrtext() {
        // Redirect zu fmsyu_jni Version
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            if (fmsyu_jni(CMD_RDSGETTEXT, inBundle, outBundle) == 0) {
                return outBundle.getByteArray("Text");
            }
        } catch (Throwable e) {}
        return null;
    }

    // ========== Tuner Settings Methoden ==========

    /**
     * Mono-Modus setzen (für Rauschunterdrückung bei schwachem Signal)
     * @param enabled true = Mono erzwingen, false = Stereo erlauben
     * @return true wenn erfolgreich
     */
    public boolean setMonoMode(boolean enabled) {
        if (!libraryLoaded) return false;
        try {
            int result = setmonostero(enabled ? 1 : 0);
            Log.i(TAG, "setMonoMode(" + enabled + ") = " + result);
            return result == 0;
        } catch (Throwable e) {
            Log.e(TAG, "setMonoMode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Mono-Modus Status abfragen
     * @return true wenn Mono aktiv, false wenn Stereo
     */
    public boolean isMonoMode() {
        if (!libraryLoaded) return false;
        try {
            int result = getmonostero(0);
            Log.d(TAG, "isMonoMode() = " + result);
            return result == 1;
        } catch (Throwable e) {
            Log.e(TAG, "isMonoMode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * LOC (Local) Modus setzen - filtert schwache Sender
     * @param enabled true = nur starke lokale Sender, false = alle Sender
     * @return true wenn erfolgreich
     */
    public boolean setLocalMode(boolean enabled) {
        if (!libraryLoaded) return false;
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            inBundle.putInt("value", enabled ? 1 : 0);
            int result = fmsyu_jni(CMD_DENSENSEDETECT, inBundle, outBundle);
            Log.i(TAG, "setLocalMode(" + enabled + ") = " + result);
            return result == 0;
        } catch (Throwable e) {
            Log.e(TAG, "setLocalMode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * LOC (Local) Modus Status abfragen
     * @return true wenn LOC aktiv
     */
    public boolean isLocalMode() {
        if (!libraryLoaded) return false;
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fmsyu_jni(CMD_DENSENSEDETECT, inBundle, outBundle);
            if (result == 0) {
                int value = outBundle.getInt("value", 0);
                Log.d(TAG, "isLocalMode() = " + value);
                return value == 1;
            }
        } catch (Throwable e) {
            Log.e(TAG, "isLocalMode failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Radio Area/Region setzen
     * @param area AREA_EUROPE, AREA_USA oder AREA_JAPAN
     * @return true wenn erfolgreich
     */
    public boolean setRadioArea(int area) {
        if (!libraryLoaded) return false;
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            inBundle.putInt("area", area);
            int result = fmsyu_jni(CMD_SETGET_AREA, inBundle, outBundle);
            Log.i(TAG, "setRadioArea(" + area + ") = " + result);
            return result == 0;
        } catch (Throwable e) {
            Log.e(TAG, "setRadioArea failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Radio Area/Region abfragen
     * @return Area-Konstante oder -1 bei Fehler
     */
    public int getRadioArea() {
        if (!libraryLoaded) return -1;
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fmsyu_jni(CMD_SETGET_AREA, inBundle, outBundle);
            if (result == 0) {
                int area = outBundle.getInt("area", 0);
                Log.d(TAG, "getRadioArea() = " + area);
                return area;
            }
        } catch (Throwable e) {
            Log.e(TAG, "getRadioArea failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Stereo-Status abfragen (ob aktuell Stereo empfangen wird)
     * @return true wenn Stereo-Empfang
     */
    public boolean isStereoReceiving() {
        if (!libraryLoaded) return false;
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fmsyu_jni(CMD_GETMONOSTERO, inBundle, outBundle);
            if (result == 0) {
                // 0 = Mono, 1 = Stereo
                int status = outBundle.getInt("status", 0);
                return status == 1;
            }
        } catch (Throwable e) {
            Log.e(TAG, "isStereoReceiving failed: " + e.getMessage());
        }
        return false;
    }
}
