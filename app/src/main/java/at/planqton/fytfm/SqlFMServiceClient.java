package at.planqton.fytfm;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.DataOutputStream;

/**
 * Client für den sqlfmservice Binder-Service
 *
 * Dieser Service stellt RDS-Daten und erweiterte FM-Funktionen bereit.
 */
public class SqlFMServiceClient {
    private static final String TAG = "SqlFMServiceClient";
    private static final String SERVICE_NAME = "sqlfmservice";

    // Binder Transaction Codes (aus Probe-Analyse)
    private static final int TRANSACTION_RDS_ONOFF = 1;
    private static final int TRANSACTION_SET_RDS_CALLBACK = 2;
    private static final int TRANSACTION_SET_CLIENT = 3;
    private static final int TRANSACTION_OPEN_DEV = 4;
    private static final int TRANSACTION_CLOSE_DEV = 5;
    private static final int TRANSACTION_POWER_UP = 6;
    private static final int TRANSACTION_POWER_DOWN = 7;
    private static final int TRANSACTION_TUNE = 8;
    private static final int TRANSACTION_SEEK = 9;
    private static final int TRANSACTION_AUTOSCAN = 10;
    private static final int TRANSACTION_GET_LEVEL = 11;
    private static final int TRANSACTION_STOP_SCAN = 12;
    private static final int TRANSACTION_GET_PS = 13;  // vermutlich
    private static final int TRANSACTION_GET_RT = 14;  // vermutlich

    private IBinder mService;
    private boolean mConnected = false;
    private RdsCallback mCallback;
    private CallbackBinder mCallbackBinder;
    private String mCurrentPs = "";
    private String mCurrentRt = "";

    // Root-Fallback für UIS7870/DUDU7 Geräte
    private boolean mUseRootFallback = false;
    private boolean mRootNotificationShown = false;
    private RootRequiredListener mRootListener;

    /**
     * Callback Binder - empfängt RDS Events vom sqlfmservice
     */
    private class CallbackBinder extends android.os.Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            Log.i(TAG, "CallbackBinder.onTransact: code=" + code + ", dataSize=" + data.dataSize());

            try {
                // Interface-Token lesen (falls vorhanden)
                try {
                    data.enforceInterface("sqlfmserver.ISqlFMClientCallback");
                } catch (Exception e) {
                    // Token nicht vorhanden - Daten von Anfang lesen
                    data.setDataPosition(0);
                }

                // Verschiedene Callback-Formate probieren
                int dataAvail = data.dataAvail();
                Log.i(TAG, "Data available: " + dataAvail + " bytes");

                if (code == 1) {
                    // RDS Callback - Rohdaten analysieren
                    int startPos = data.dataPosition();
                    int avail = data.dataAvail();

                    // Rohdaten direkt aus Parcel lesen (nicht readByteArray!)
                    if (avail > 0) {
                        byte[] raw = data.marshall();
                        if (raw != null && raw.length > 0) {
                            StringBuilder hex = new StringBuilder();
                            StringBuilder ascii = new StringBuilder();
                            for (int i = 0; i < Math.min(raw.length, 80); i++) {
                                byte b = raw[i];
                                hex.append(String.format("%02X ", b));
                                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
                            }
                            Log.i(TAG, "RDS raw[" + raw.length + "]: " + hex.toString());
                            Log.i(TAG, "RDS ascii: " + ascii.toString());

                            // Versuche PS/RT aus Rohdaten zu extrahieren
                            String ascii_str = ascii.toString();
                            // Suche nach zusammenhängenden Text-Blöcken
                            StringBuilder textBlock = new StringBuilder();
                            for (int i = 0; i < ascii_str.length(); i++) {
                                char c = ascii_str.charAt(i);
                                if (c != '.') {
                                    textBlock.append(c);
                                } else if (textBlock.length() > 0) {
                                    String text = textBlock.toString().trim();
                                    if (text.length() >= 2 && text.matches(".*[A-Za-z].*")) {
                                        Log.i(TAG, "RDS text block: '" + text + "'");
                                        if (text.length() <= 8 && mCurrentPs.isEmpty()) {
                                            mCurrentPs = text;
                                            if (mCallback != null) mCallback.onRdsPs(text);
                                        } else if (text.length() > 8) {
                                            mCurrentRt = text;
                                            if (mCallback != null) mCallback.onRdsRt(text);
                                        }
                                    }
                                    textBlock = new StringBuilder();
                                }
                            }
                        }
                    }

                    // Auch Integer-Format versuchen
                    data.setDataPosition(startPos);
                    parseIntegerCallback(data);
                } else if (code == 2) {
                    // PS Update
                    String ps = data.readString();
                    if (ps != null && !ps.isEmpty()) {
                        Log.i(TAG, "RDS PS Update: " + ps);
                        mCurrentPs = ps;
                        if (mCallback != null) {
                            mCallback.onRdsPs(ps);
                        }
                    }
                } else if (code == 3) {
                    // RT Update
                    String rt = data.readString();
                    if (rt != null && !rt.isEmpty()) {
                        Log.i(TAG, "RDS RT Update: " + rt);
                        mCurrentRt = rt;
                        if (mCallback != null) {
                            mCallback.onRdsRt(rt);
                        }
                    }
                } else {
                    // Generischer Callback - versuche verschiedene Formate
                    parseGenericCallback(code, data);
                }

                if (reply != null) {
                    reply.writeNoException();
                }
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Callback error: " + e.getMessage(), e);
                return super.onTransact(code, data, reply, flags);
            }
        }

        private void parseIntegerCallback(Parcel data) {
            try {
                int eventType = data.readInt();
                int p1 = data.readInt();
                int p2 = data.readInt();
                int p3 = data.readInt();

                Log.i(TAG, "RDS Int Callback: type=0x" + Integer.toHexString(eventType) +
                      ", p1=" + p1 + ", p2=" + p2 + ", p3=" + p3);

                int subType = 0;

                // Spezielle Event-Typen analysieren
                // 0xc2000004 scheint ein häufiger RDS Event zu sein
                if ((eventType & 0xFFFF0000) == 0xc2000000) {
                    subType = eventType & 0xFFFF;
                    Log.i(TAG, "RDS subtype: " + subType);

                    // Versuche nach den Int-Werten noch String-Daten zu lesen
                    if (data.dataAvail() > 0) {
                        int pos = data.dataPosition();
                        try {
                            String str = data.readString();
                            if (str != null && !str.isEmpty() && str.matches(".*[A-Za-z].*")) {
                                Log.i(TAG, "RDS Event String: '" + str + "'");
                                if (str.length() <= 8) {
                                    mCurrentPs = str;
                                    if (mCallback != null) mCallback.onRdsPs(str);
                                } else {
                                    mCurrentRt = str;
                                    if (mCallback != null) mCallback.onRdsRt(str);
                                }
                            }
                        } catch (Exception e) {
                            data.setDataPosition(pos);
                        }
                    }
                }

                // Event Type 4 könnte PSNAME sein (oder letzte 4 Bits = 4)
                if ((eventType & 0xFF) == 4 || subType == 4) {
                    Log.i(TAG, "Possible PS event - p2=" + p2 + ", p3=" + p3);
                    // Probiere p3 als Pointer zu interpreptieren (falls String-Adresse)
                    // p2=24 könnte Länge sein (aber 24 ist zu lang für PS)
                }

                if (mCallback != null) {
                    mCallback.onRdsEvent(eventType, p1, p2, p3);
                }
            } catch (Exception e) {
                Log.d(TAG, "Integer parse failed: " + e.getMessage());
            }
        }

        private void parseGenericCallback(int code, Parcel data) {
            // Dump raw data für Debug
            int pos = data.dataPosition();
            int size = data.dataAvail();

            if (size > 0) {
                byte[] rawData = new byte[Math.min(size, 256)];
                data.readByteArray(rawData);

                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (byte b : rawData) {
                    hex.append(String.format("%02X ", b));
                    ascii.append((b >= 32 && b < 127) ? (char)b : '.');
                }
                Log.i(TAG, "Callback " + code + " raw: " + hex.toString());
                Log.i(TAG, "Callback " + code + " ascii: " + ascii.toString());

                // Versuche als String zu interpretieren
                String strData = new String(rawData).trim();
                if (!strData.isEmpty() && strData.matches(".*[A-Za-z0-9].*")) {
                    Log.i(TAG, "Possible RDS text: " + strData);
                    // Könnte PS oder RT sein
                    if (strData.length() <= 8) {
                        mCurrentPs = strData;
                        if (mCallback != null) mCallback.onRdsPs(strData);
                    } else {
                        mCurrentRt = strData;
                        if (mCallback != null) mCallback.onRdsRt(strData);
                    }
                }
            }

            data.setDataPosition(pos);
        }
    }

    public interface RdsCallback {
        void onRdsPs(String ps);
        void onRdsRt(String rt);
        void onRdsEvent(int type, int p1, int p2, int p3);
    }

    /**
     * Listener für Root-Benachrichtigung (UIS7870/DUDU7 Geräte)
     */
    public interface RootRequiredListener {
        void onRootRequired();
    }

    public void setRootRequiredListener(RootRequiredListener listener) {
        mRootListener = listener;
    }

    public boolean isUsingRootFallback() {
        return mUseRootFallback;
    }

    public SqlFMServiceClient() {
        mCallbackBinder = new CallbackBinder();
        connect();
    }

    /**
     * Callback beim sqlfmservice registrieren
     */
    public boolean registerCallback() {
        if (!isConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                // setSqlFMClient aufrufen (Transaction 3)
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeStrongBinder(mCallbackBinder);

                boolean success = mService.transact(TRANSACTION_SET_CLIENT, data, reply, 0);

                if (success) {
                    reply.readException();
                    Log.i(TAG, "Callback registered successfully");
                    return true;
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "registerCallback failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * RDS aktivieren beim sqlfmservice
     */
    public boolean enableRds() {
        if (!isConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeInt(1); // enable = 1

                boolean success = mService.transact(TRANSACTION_RDS_ONOFF, data, reply, 0);

                if (success) {
                    reply.readException();
                    Log.i(TAG, "RDS enabled");
                    return true;
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "enableRds failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Verbindung zum sqlfmservice herstellen
     */
    public boolean connect() {
        try {
            // ServiceManager via Reflection
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            mService = (IBinder) getService.invoke(null, SERVICE_NAME);

            if (mService != null) {
                mConnected = true;
                Log.i(TAG, "Connected to sqlfmservice");

                // Versuche den Service Descriptor zu lesen
                try {
                    String descriptor = mService.getInterfaceDescriptor();
                    Log.i(TAG, "Service descriptor: " + descriptor);
                } catch (Exception e) {
                    Log.w(TAG, "Could not get descriptor: " + e.getMessage());
                }

                return true;
            } else {
                Log.w(TAG, "sqlfmservice not found");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect: " + e.getMessage());
            return false;
        }
    }

    public boolean isConnected() {
        return mConnected && mService != null && mService.isBinderAlive();
    }

    public void setRdsCallback(RdsCallback callback) {
        mCallback = callback;
    }

    /**
     * RDS PS (Program Service Name) abrufen
     * Versucht verschiedene Methoden um PS zu lesen
     */
    public String getPs() {
        // Primär: Wert aus Callbacks
        if (!mCurrentPs.isEmpty()) {
            return mCurrentPs;
        }

        if (!isConnected()) return "";

        // Methode 1: Transaction 13 (vermuteter GET_PS Code)
        String ps = tryGetRdsString(TRANSACTION_GET_PS, "PS via tx13");
        if (!ps.isEmpty()) return ps;

        // Methode 2: Probiere andere mögliche Transaction Codes
        for (int txCode : new int[]{15, 16, 17, 18, 19, 20}) {
            ps = tryGetRdsString(txCode, "PS via tx" + txCode);
            if (!ps.isEmpty()) return ps;
        }

        return mCurrentPs;
    }

    /**
     * Versuche RDS String via spezifische Transaction zu lesen
     */
    private String tryGetRdsString(int txCode, String description) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                boolean success = mService.transact(txCode, data, reply, 0);

                if (success) {
                    int pos = reply.dataPosition();
                    int avail = reply.dataAvail();

                    try {
                        reply.readException();
                    } catch (Exception e) {
                        // Wenn Exception-Format nicht stimmt, Position zurücksetzen
                        reply.setDataPosition(pos);
                    }

                    avail = reply.dataAvail();
                    if (avail > 0) {
                        // Versuche als String zu lesen
                        try {
                            String str = reply.readString();
                            if (str != null && !str.isEmpty() && !str.contains("not support") &&
                                str.matches(".*[A-Za-z0-9].*")) {
                                Log.i(TAG, description + " = '" + str + "'");
                                mCurrentPs = str;
                                return str;
                            }
                        } catch (Exception e) {
                            // String-Format passt nicht
                        }

                        // Versuche als Byte-Array zu lesen
                        reply.setDataPosition(pos);
                        try {
                            reply.readException();
                        } catch (Exception e) {
                            reply.setDataPosition(pos);
                        }

                        avail = reply.dataAvail();
                        if (avail > 4) {
                            byte[] bytes = new byte[Math.min(avail, 64)];
                            reply.readByteArray(bytes);

                            // Extrahiere lesbaren Text
                            StringBuilder text = new StringBuilder();
                            for (byte b : bytes) {
                                if (b >= 32 && b < 127) {
                                    text.append((char) b);
                                }
                            }
                            String extracted = text.toString().trim();
                            if (extracted.length() >= 2 && extracted.matches(".*[A-Za-z].*")) {
                                Log.i(TAG, description + " (from bytes) = '" + extracted + "'");
                                return extracted;
                            }
                        }
                    }
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            // Ignorieren
        }
        return "";
    }

    /**
     * RDS RT (Radio Text) abrufen
     * Gibt den über Callback empfangenen Wert zurück
     */
    public String getRt() {
        // Primär: Wert aus Callbacks
        if (!mCurrentRt.isEmpty()) {
            return mCurrentRt;
        }

        // Fallback: Direkter Transact
        if (!isConnected()) return "";

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                boolean success = mService.transact(TRANSACTION_GET_RT, data, reply, 0);

                if (success) {
                    reply.readException();
                    String rt = reply.readString();
                    if (rt != null && !rt.isEmpty() && !rt.contains("not support")) {
                        Log.d(TAG, "getRt() via transact = " + rt);
                        mCurrentRt = rt;
                        return rt;
                    }
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.d(TAG, "getRt transact failed: " + e.getMessage());
        }
        return mCurrentRt;
    }

    /**
     * RDS Daten zurücksetzen (z.B. bei Frequenzwechsel)
     */
    public void clearRdsData() {
        mCurrentPs = "";
        mCurrentRt = "";
    }

    /**
     * RDS aktivieren/deaktivieren
     */
    public boolean setRds(boolean enable) {
        if (!isConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeInt(enable ? 1 : 0);
                boolean success = mService.transact(TRANSACTION_RDS_ONOFF, data, reply, 0);

                if (success) {
                    reply.readException();
                    Log.i(TAG, "setRds(" + enable + ") success");
                    return true;
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "setRds failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * AutoScan über sqlfmservice starten
     * Gibt gefundene Frequenzen zurück (in kHz * 10)
     */
    public short[] autoScan() {
        if (!isConnected()) return null;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeInt(0); // band = 0 (EU)

                boolean success = mService.transact(TRANSACTION_AUTOSCAN, data, reply, 0);
                Log.i(TAG, "autoScan transact = " + success);

                if (success) {
                    reply.readException();

                    // Versuche Array zu lesen
                    int count = reply.readInt();
                    Log.i(TAG, "autoScan returned count: " + count);

                    if (count > 0 && count < 100) {
                        short[] freqs = new short[count];
                        for (int i = 0; i < count; i++) {
                            freqs[i] = (short) reply.readInt();
                            Log.i(TAG, "autoScan freq[" + i + "] = " + freqs[i]);
                        }
                        return freqs;
                    }
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "autoScan failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * PowerUp über sqlfmservice
     */
    public boolean powerUpSql(float freqMHz) {
        if (!isConnected()) return false;

        int freqKhz10 = (int)(freqMHz * 100);

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeInt(freqKhz10);

                boolean success = mService.transact(TRANSACTION_POWER_UP, data, reply, 0);
                Log.i(TAG, "powerUpSql(" + freqMHz + ") = " + success);

                if (success) {
                    reply.readException();
                    return true;
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "powerUpSql failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Tune über sqlfmservice
     */
    public boolean tune(float freqMHz) {
        // Root-Fallback Modus (UIS7870/DUDU7)
        if (mUseRootFallback) {
            return tuneViaSu(freqMHz);
        }

        if (!isConnected()) {
            // Versuche Root-Fallback wenn nicht verbunden
            if (isRootAvailable()) {
                Log.i(TAG, "Binder nicht verbunden, versuche Root-Fallback...");
                enableRootFallback();
                return tuneViaSu(freqMHz);
            }
            return false;
        }

        int freqKhz10 = (int)(freqMHz * 100); // 90.4 -> 9040

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeInt(freqKhz10);
                data.writeInt(0); // second parameter (band?)

                boolean success = mService.transact(TRANSACTION_TUNE, data, reply, 0);
                Log.i(TAG, "tune(" + freqMHz + " = " + freqKhz10 + ") = " + success);

                if (success) {
                    reply.readException();
                    return true;
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (SecurityException e) {
            // Binder-Zugriff verweigert - versuche Root-Fallback
            Log.w(TAG, "tune: SecurityException, versuche Root-Fallback...");
            if (isRootAvailable()) {
                enableRootFallback();
                return tuneViaSu(freqMHz);
            }
        } catch (Exception e) {
            Log.e(TAG, "tune failed: " + e.getMessage());
            // Bei anderen Fehlern auch Root-Fallback versuchen
            if (isRootAvailable()) {
                enableRootFallback();
                return tuneViaSu(freqMHz);
            }
        }
        return false;
    }

    /**
     * getLPSname über sqlfmservice - versucht PS-Namen für Frequenz zu holen
     */
    public String getLPSname(float freqMHz) {
        if (!isConnected()) return "";

        int freqKhz10 = (int)(freqMHz * 100);

        // Versuche verschiedene Transaction Codes für getLPSname
        for (int txCode : new int[]{15, 16, 17, 18, 19, 20}) {
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();

                try {
                    data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                    data.writeInt(freqKhz10);

                    boolean success = mService.transact(txCode, data, reply, 0);

                    if (success) {
                        int replySize = reply.dataAvail();
                        if (replySize > 4) {
                            reply.readException();

                            // Versuche String zu lesen
                            try {
                                String str = reply.readString();
                                if (str != null && !str.isEmpty() && str.matches(".*[A-Za-z0-9].*")) {
                                    Log.i(TAG, "getLPSname TX" + txCode + " = '" + str + "'");
                                    return str;
                                }
                            } catch (Exception e) {
                                // Kein String
                            }
                        }
                    }
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } catch (Exception e) {
                // Ignorieren
            }
        }
        return "";
    }

    /**
     * Seek über sqlfmservice - asynchron, gibt gefundene Frequenz zurück
     */
    public float seek(boolean up) {
        if (!isConnected()) return -1;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeInt(up ? 1 : 0);

                boolean success = mService.transact(TRANSACTION_SEEK, data, reply, 0);
                Log.i(TAG, "seek transact = " + success);

                if (success) {
                    reply.readException();

                    // Versuche verschiedene Formate zu lesen
                    int pos = reply.dataPosition();
                    int avail = reply.dataAvail();
                    Log.i(TAG, "seek reply: pos=" + pos + ", avail=" + avail);

                    if (avail >= 4) {
                        int freqKhz = reply.readInt();
                        Log.i(TAG, "seek returned freq (int): " + freqKhz);

                        // Format 1: Frequenz in kHz*10 (z.B. 9040 für 90.4 MHz)
                        if (freqKhz >= 8750 && freqKhz <= 10800) {
                            return freqKhz / 100.0f;
                        }

                        // Format 2: Frequenz in kHz (z.B. 90400 für 90.4 MHz)
                        if (freqKhz >= 87500 && freqKhz <= 108000) {
                            return freqKhz / 1000.0f;
                        }

                        // Format 3: Frequenz direkt als float-ähnlicher int
                        if (freqKhz >= 875 && freqKhz <= 1080) {
                            return freqKhz / 10.0f;
                        }
                    }
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "seek failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Aktuelle Frequenz vom Service abfragen
     */
    public float getCurrentFrequency() {
        if (!isConnected()) return -1;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");

                // Transaction 11 könnte GET_LEVEL/GET_FREQ sein
                boolean success = mService.transact(TRANSACTION_GET_LEVEL, data, reply, 0);

                if (success) {
                    reply.readException();
                    int freq = reply.readInt();
                    Log.i(TAG, "getCurrentFrequency: " + freq);
                    if (freq >= 8750 && freq <= 10800) {
                        return freq / 100.0f;
                    }
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.d(TAG, "getCurrentFrequency failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Generische Transact-Methode für Tests
     */
    public boolean testTransact(int code) {
        if (!isConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                boolean success = mService.transact(code, data, reply, 0);
                Log.i(TAG, "testTransact(" + code + ") = " + success);

                if (success) {
                    // Versuche Reply zu lesen
                    try {
                        reply.readException();
                        int resultCode = reply.readInt();
                        Log.i(TAG, "  result code: " + resultCode);
                    } catch (Exception e) {
                        // Ignorieren
                    }
                }
                return success;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "testTransact failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Service-Methoden durch Probing entdecken
     */
    public void probeService() {
        if (!isConnected()) return;

        Log.i(TAG, "Probing sqlfmservice methods (detailed)...");

        // Interessante Transaction Codes probieren und Daten anzeigen
        int[] testCodes = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25};

        for (int code : testCodes) {
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();

                try {
                    data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                    // Für manche Codes die Frequenz übergeben
                    if (code >= 15 && code <= 20) {
                        data.writeInt(9040); // 90.4 MHz als 9040
                    }

                    boolean success = mService.transact(code, data, reply, 0);

                    if (success) {
                        int replySize = reply.dataAvail();
                        Log.i(TAG, "Transaction " + code + ": SUCCESS, replySize=" + replySize);

                        if (replySize > 0) {
                            // Rohdaten dumpen
                            byte[] raw = new byte[Math.min(replySize, 64)];
                            int pos = reply.dataPosition();
                            reply.readByteArray(raw);

                            StringBuilder hex = new StringBuilder();
                            StringBuilder ascii = new StringBuilder();
                            for (byte b : raw) {
                                hex.append(String.format("%02X ", b));
                                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
                            }
                            Log.i(TAG, "  TX" + code + " hex: " + hex.toString().trim());
                            Log.i(TAG, "  TX" + code + " ascii: " + ascii.toString());

                            // Versuche als Strings zu interpretieren
                            reply.setDataPosition(pos);
                            try {
                                reply.readException();
                                String str = reply.readString();
                                if (str != null && !str.isEmpty()) {
                                    Log.i(TAG, "  TX" + code + " string: '" + str + "'");
                                }
                            } catch (Exception e) {
                                // Kein String-Format
                            }
                        }
                    }
                } catch (RemoteException e) {
                    Log.d(TAG, "Transaction " + code + ": RemoteException");
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } catch (Exception e) {
                Log.d(TAG, "Transaction " + code + ": ERROR - " + e.getMessage());
            }
        }
    }

    /**
     * Erweiterte Probe-Funktion für getLPSname-ähnliche Methoden
     * Basierend auf Stock App Logs: "getLPSname 9160" (91.6 MHz)
     */
    public void probeLPSname(float freqMHz) {
        if (!isConnected()) return;

        int freqKhz10 = (int)(freqMHz * 100);
        Log.i(TAG, "=== Probing getLPSname for freq " + freqMHz + " (" + freqKhz10 + ") ===");

        // Transaction Codes 13-25 durchprobieren mit Frequenz-Parameter
        for (int code = 13; code <= 30; code++) {
            probeLPSnameWithCode(code, freqKhz10);
        }
    }

    private void probeLPSnameWithCode(int code, int freqKhz10) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();

            try {
                data.writeInterfaceToken("sqlfmserver.ISqlFMService");
                data.writeInt(freqKhz10);

                boolean success = mService.transact(code, data, reply, 0);

                if (success) {
                    int replySize = reply.dataAvail();
                    if (replySize > 4) {
                        int pos = reply.dataPosition();

                        // Rohdaten dumpen
                        byte[] raw = new byte[Math.min(replySize, 128)];
                        reply.readByteArray(raw);

                        StringBuilder hex = new StringBuilder();
                        StringBuilder ascii = new StringBuilder();
                        for (byte b : raw) {
                            hex.append(String.format("%02X ", b));
                            ascii.append((b >= 32 && b < 127) ? (char)b : '.');
                        }

                        Log.i(TAG, "TX" + code + " replySize=" + replySize);
                        Log.i(TAG, "  hex: " + hex.toString().trim());
                        Log.i(TAG, "  ascii: " + ascii.toString());

                        // Versuche String zu lesen
                        reply.setDataPosition(pos);
                        try {
                            reply.readException();
                            String str = reply.readString();
                            if (str != null && !str.isEmpty() && str.matches(".*[A-Za-z0-9].*")) {
                                Log.i(TAG, "  TX" + code + " STRING: '" + str + "' *** POSSIBLE PS ***");
                                if (str.length() <= 8) {
                                    mCurrentPs = str;
                                }
                            }
                        } catch (Exception e) {
                            // Kein String-Format
                        }

                        // Versuche Int + String
                        reply.setDataPosition(pos);
                        try {
                            int resultCode = reply.readInt();
                            if (reply.dataAvail() > 0) {
                                String str = reply.readString();
                                if (str != null && !str.isEmpty() && str.matches(".*[A-Za-z0-9].*")) {
                                    Log.i(TAG, "  TX" + code + " INT+STRING: code=" + resultCode + ", str='" + str + "'");
                                }
                            }
                        } catch (Exception e) {
                            // Ignorieren
                        }
                    }
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            // Ignorieren
        }
    }

    /**
     * Direkte RDS-Daten lesen mit verschiedenen Parcel-Formaten
     */
    public String readPsDirect() {
        if (!isConnected()) return "";

        // Probiere verschiedene Transaction Codes
        int[] psCodes = {13, 15, 17, 19, 21, 23};

        for (int code : psCodes) {
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();

                try {
                    data.writeInterfaceToken("sqlfmserver.ISqlFMService");

                    boolean success = mService.transact(code, data, reply, 0);

                    if (success && reply.dataAvail() > 0) {
                        int pos = reply.dataPosition();

                        // Format 1: Nur String
                        try {
                            String str = reply.readString();
                            if (str != null && !str.isEmpty() && str.matches(".*[A-Za-z0-9].*")
                                && !str.contains("sqlfm") && str.length() <= 8) {
                                Log.i(TAG, "readPsDirect TX" + code + " string: '" + str + "'");
                                return str;
                            }
                        } catch (Exception e) {
                            reply.setDataPosition(pos);
                        }

                        // Format 2: Exception + String
                        try {
                            reply.readException();
                            String str = reply.readString();
                            if (str != null && !str.isEmpty() && str.matches(".*[A-Za-z0-9].*")
                                && !str.contains("sqlfm") && str.length() <= 8) {
                                Log.i(TAG, "readPsDirect TX" + code + " exc+string: '" + str + "'");
                                return str;
                            }
                        } catch (Exception e) {
                            reply.setDataPosition(pos);
                        }

                        // Format 3: Bytes direkt lesen
                        byte[] raw = new byte[Math.min(reply.dataAvail(), 32)];
                        reply.readByteArray(raw);
                        StringBuilder text = new StringBuilder();
                        for (byte b : raw) {
                            if (b >= 32 && b < 127) {
                                text.append((char) b);
                            }
                        }
                        String extracted = text.toString().trim();
                        if (extracted.length() >= 2 && extracted.length() <= 8
                            && extracted.matches(".*[A-Za-z].*") && !extracted.contains("sqlfm")) {
                            Log.i(TAG, "readPsDirect TX" + code + " bytes: '" + extracted + "'");
                            return extracted;
                        }
                    }
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } catch (Exception e) {
                // Ignorieren
            }
        }

        return "";
    }

    public void disconnect() {
        mService = null;
        mConnected = false;
    }

    // ============================================================
    // Root-Fallback Methoden für UIS7870/DUDU7 Geräte
    // ============================================================

    /**
     * Aktiviert den Root-Fallback Modus
     */
    public void enableRootFallback() {
        mUseRootFallback = true;
        Log.i(TAG, "Root-Fallback aktiviert für sqlfmservice Zugriff");

        // Einmalige Benachrichtigung an die App
        if (!mRootNotificationShown && mRootListener != null) {
            mRootNotificationShown = true;
            mRootListener.onRootRequired();
        }
    }

    /**
     * Führt einen service call über su aus
     * @param transactionCode Der Binder Transaction Code
     * @param args Optionale int32 Argumente
     * @return Das Ergebnis als String oder null bei Fehler
     */
    private String executeServiceCallViaSu(int transactionCode, int... args) {
        try {
            StringBuilder cmd = new StringBuilder();
            cmd.append("service call sqlfmservice ").append(transactionCode);
            for (int arg : args) {
                cmd.append(" i32 ").append(arg);
            }

            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(cmd.toString() + "\n");
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            process.waitFor();
            reader.close();
            os.close();

            return output.toString();
        } catch (Exception e) {
            Log.e(TAG, "executeServiceCallViaSu failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parst Parcel-Output von service call (z.B. "Result: Parcel(00000000 '....')")
     * Extrahiert String-Daten aus dem Hex-Teil
     */
    private String parseParcelOutput(String output) {
        if (output == null || output.isEmpty()) return "";

        // Format: Result: Parcel(HEXDATA 'ASCII')
        // Wir extrahieren den ASCII-Teil zwischen den Quotes
        int quoteStart = output.indexOf("'");
        int quoteEnd = output.lastIndexOf("'");

        if (quoteStart >= 0 && quoteEnd > quoteStart) {
            String ascii = output.substring(quoteStart + 1, quoteEnd);
            // Filtere nur druckbare Zeichen
            StringBuilder result = new StringBuilder();
            for (char c : ascii.toCharArray()) {
                if (c >= 32 && c < 127 && c != '.') {
                    result.append(c);
                }
            }
            return result.toString().trim();
        }

        return "";
    }

    /**
     * Tune über Root-Fallback
     */
    public boolean tuneViaSu(float freqMHz) {
        int freqKhz10 = (int)(freqMHz * 100);
        String result = executeServiceCallViaSu(TRANSACTION_TUNE, freqKhz10, 0);
        boolean success = result != null && !result.contains("error");
        Log.i(TAG, "tuneViaSu(" + freqMHz + ") = " + success);
        return success;
    }

    /**
     * RDS aktivieren über Root-Fallback
     */
    public boolean enableRdsViaSu() {
        String result = executeServiceCallViaSu(TRANSACTION_RDS_ONOFF, 1);
        boolean success = result != null && !result.contains("error");
        Log.i(TAG, "enableRdsViaSu() = " + success);
        return success;
    }

    /**
     * Prüft ob Root verfügbar ist
     */
    public static boolean isRootAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("su -c whoami");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();
            reader.close();
            return "root".equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}
