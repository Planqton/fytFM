package at.planqton.fytfm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.fmradio.FmNative;

import java.nio.charset.StandardCharsets;

/**
 * RdsManager - Saubere RDS-Implementierung für FYT Head Units
 *
 * Basiert auf NavRadio+ Reverse-Engineering:
 * - PS wird über fmsyu_jni(0x1e) abgerufen (FMJNI_CMD_30_RDSGETPS)
 * - RT wird über fmsyu_jni(0x1f) abgerufen (FMJNI_CMD_31_RDSGETTEXT)
 * - RDS wird über fmsyu_jni(0x15) aktiviert (FMJNI_CMD_21_RDSONOFF)
 */
public class RdsManager {
    private static final String TAG = "RdsManager";

    // JNI Command Codes (aus NavRadio+ FmNative.smali)
    private static final int CMD_CURRENTFREQ = 0x12;   // 18 - Get current hardware frequency
    private static final int CMD_RDSONOFF = 0x15;      // 21 - RDS ein/aus
    private static final int CMD_RDSAFCONFIG = 0x18;   // 24 - RDS AF Config (SET)
    private static final int CMD_GETRDSCONFIG = 0x1a;  // 26 - Get RDS Config (might have AF!)
    private static final int CMD_GETRDSSTATE = 0x1b;   // 27 - Get RDS State (PTY, PI, TP, TA)
    private static final int CMD_RDSGETPS = 0x1e;      // 30 - PS abrufen
    private static final int CMD_RDSGETTEXT = 0x1f;    // 31 - RT abrufen
    private static final int CMD_RDSGETFREQPS = 0x20;  // 32 - Get FreqPS (enthält PI!)

    // Polling (optimiert für langsame RDS-Sender)
    private static final int POLL_INTERVAL_MS = 150;
    private static final int RDS_READ_ITERATIONS = 12;  // Mehrere readRds() für besseren RT-Empfang

    private final FmNative fmNative;
    private final Handler handler;

    private RdsCallback callback;
    private Runnable pollingRunnable;
    private boolean isPolling = false;
    private boolean isRadioOn = false;

    // Aktuelle RDS-Daten (volatile für Thread-Sicherheit - Scanner liest aus Background-Thread)
    private volatile String currentPs = "";
    private volatile String currentRt = "";
    private volatile int currentRssi = 0;
    private volatile int currentPi = 0;
    private volatile int currentPty = 0;
    private volatile int currentTp = 0;
    private volatile int currentTa = 0;
    private volatile short[] currentAfList = null;
    private volatile boolean afEnabled = false;
    private volatile float hardwareFrequency = 0f;  // Echte Frequenz vom FM-Chip
    private volatile float uiFrequency = 0f;        // Vom User eingestellte Frequenz

    // Timestamps für jeden RDS-Wert
    private long lastPsTimestamp = 0;
    private long lastPiTimestamp = 0;
    private long lastPtyTimestamp = 0;
    private long lastRtTimestamp = 0;
    private long lastRssiTimestamp = 0;
    private long lastTpTaTimestamp = 0;
    private long lastAfTimestamp = 0;

    public interface RdsCallback {
        void onRdsUpdate(String ps, String rt, int rssi, int pi, int pty, int tp, int ta, short[] afList);
    }

    public RdsManager(FmNative fmNative) {
        this.fmNative = fmNative;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Aktiviert RDS auf der Hardware.
     * Muss NACH powerUp aufgerufen werden!
     */
    public boolean enableRds() {
        Log.i(TAG, "=== enableRds() called ===");

        if (!FmNative.isLibraryLoaded()) {
            Log.e(TAG, "enableRds: Library not loaded!");
            return false;
        }

        boolean success = false;

        // Methode 1: Direkt über setRds
        try {
            int result = fmNative.setRds(true);
            Log.i(TAG, "enableRds: setRds(true) = " + result);
            if (result == 0) success = true;
        } catch (Throwable e) {
            Log.w(TAG, "enableRds: setRds failed: " + e.getMessage());
        }

        // Methode 2: Via fmsyu_jni (NavRadio-Stil)
        try {
            Bundle inBundle = new Bundle();
            inBundle.putInt("rdsonoff", 1);  // WICHTIG: Kleinbuchstaben!
            Bundle outBundle = new Bundle();

            int jniResult = fmNative.fmsyu_jni(CMD_RDSONOFF, inBundle, outBundle);
            Log.i(TAG, "enableRds: fmsyu_jni(0x15, rdsonoff=1) = " + jniResult);
            if (jniResult == 0) success = true;
        } catch (Throwable e) {
            Log.w(TAG, "enableRds: fmsyu_jni failed: " + e.getMessage());
        }

        // Methode 3: readRds aufrufen um RDS-Decoder zu triggern
        try {
            short rdsResult = fmNative.readRds();
            Log.i(TAG, "enableRds: readRds() = " + rdsResult);
        } catch (Throwable e) {
            Log.w(TAG, "enableRds: readRds failed: " + e.getMessage());
        }

        Log.i(TAG, "=== enableRds() done, success=" + success + " ===");
        return success;
    }

    /**
     * Aktiviert AF (Alternative Frequencies).
     * Muss aufgerufen werden um AF-Listen zu empfangen.
     */
    public boolean enableAf(boolean enable) {
        if (!FmNative.isLibraryLoaded()) return false;

        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            // Try different values: 1, 2 (RDS_ITEM_AF), or higher
            int configValue = enable ? 2 : 0;  // RDS_ITEM_AF = 2 from FmConstants
            inBundle.putInt("configdata", configValue);
            Log.i(TAG, "enableAf: setting configdata=" + configValue);

            int result = fmNative.fmsyu_jni(CMD_RDSAFCONFIG, inBundle, outBundle);
            Log.i(TAG, "enableAf(" + enable + "): ret=" + result);

            return result == 0;
        } catch (Throwable e) {
            Log.e(TAG, "enableAf failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Startet das RDS-Polling.
     */
    public void startPolling(RdsCallback callback) {
        this.callback = callback;
        if (isPolling) return;

        isPolling = true;
        isRadioOn = true;

        // AF aktivieren für Alternative Frequencies
        enableAf(true);

        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPolling) return;
                pollRds();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        handler.postDelayed(pollingRunnable, POLL_INTERVAL_MS);
        Log.i(TAG, "RDS polling started");
    }

    /**
     * Stoppt das RDS-Polling.
     */
    public void stopPolling() {
        isPolling = false;
        isRadioOn = false;
        if (pollingRunnable != null) {
            handler.removeCallbacks(pollingRunnable);
        }
        Log.i(TAG, "RDS polling stopped");
    }

    /**
     * Pollt RDS-Daten von der Hardware.
     */
    private void pollRds() {
        if (!FmNative.isLibraryLoaded()) {
            Log.w(TAG, "pollRds: Library not loaded!");
            return;
        }

        // WICHTIG: readRds() mehrfach aufrufen um RDS-Decoder zu triggern!
        // Dies gibt dem Chip mehr Zeit, RT-Daten zu akkumulieren
        for (int i = 0; i < RDS_READ_ITERATIONS; i++) {
            try {
                short rdsResult = fmNative.readRds();
                if (rdsResult != 0) {
                    Log.d(TAG, "pollRds: readRds()[" + i + "] = " + rdsResult);
                }
            } catch (Throwable e) {
                // Ignorieren
            }
        }

        // PS abrufen
        String ps = fetchPs();
        Log.d(TAG, "pollRds: fetchPs() returned: '" + ps + "'");
        if (ps != null && !ps.isEmpty()) {
            lastPsTimestamp = System.currentTimeMillis();
            if (!ps.equals(currentPs)) {
                currentPs = ps;
                Log.i(TAG, "PS: '" + ps + "'");
            }
        }

        // RT abrufen
        String rt = fetchRt();
        Log.d(TAG, "pollRds: fetchRt() returned: '" + rt + "'");
        if (rt != null && !rt.isEmpty()) {
            lastRtTimestamp = System.currentTimeMillis();  // RT empfangen - Timestamp aktualisieren
            if (!rt.equals(currentRt)) {
                currentRt = rt;
                Log.i(TAG, "RT: '" + rt + "'");
            }
        }

        // RSSI abrufen
        try {
            currentRssi = fmNative.getrssi();
            lastRssiTimestamp = System.currentTimeMillis();
        } catch (Throwable e) {
            // Ignorieren
        }

        // PI, PTY, TP, TA via GETRDSSTATE abrufen
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fmNative.fmsyu_jni(CMD_GETRDSSTATE, inBundle, outBundle);

            if (result == 0) {
                // PTY
                int pty = outBundle.getInt("PTYstate", outBundle.getInt("pty", -1));
                if (pty >= 0) {
                    currentPty = pty;
                    lastPtyTimestamp = System.currentTimeMillis();
                }

                // PI
                int pi = outBundle.getInt("PIcode", outBundle.getInt("PIstate", 0));
                if (pi != 0) {
                    currentPi = pi;
                    lastPiTimestamp = System.currentTimeMillis();
                }

                // TP/TA
                int tp = outBundle.getInt("TPstate", -1);
                int ta = outBundle.getInt("TAstate", -1);
                if (tp >= 0 || ta >= 0) {
                    if (tp >= 0) currentTp = tp;
                    if (ta >= 0) currentTa = ta;
                    lastTpTaTimestamp = System.currentTimeMillis();
                }

                Log.d(TAG, "pollRds: GETRDSSTATE -> PI=0x" + Integer.toHexString(currentPi) +
                      " PTY=" + currentPty + " TP=" + currentTp + " TA=" + currentTa);
            }
        } catch (Throwable e) {
            Log.w(TAG, "GETRDSSTATE failed: " + e.getMessage());
        }

        // Fallback 1: PI von FmService holen (via Rdscallback Event 14)
        if (currentPi == 0) {
            try {
                int fmSvcPi = com.android.fmradio.FmService.getPi();
                if (fmSvcPi != 0) {
                    currentPi = fmSvcPi;
                    lastPiTimestamp = System.currentTimeMillis();
                    Log.d(TAG, "pollRds: PI from FmService callback: 0x" + Integer.toHexString(currentPi));
                }
            } catch (Throwable e) {
                // Ignorieren
            }
        }

        // Fallback 2: PI via RDSGETFREQPS (0x20) - NavRadio+ Methode
        if (currentPi == 0) {
            try {
                Bundle inBundle = new Bundle();
                Bundle outBundle = new Bundle();
                int result = fmNative.fmsyu_jni(CMD_RDSGETFREQPS, inBundle, outBundle);

                if (result == 0) {
                    byte[] freqPsData = outBundle.getByteArray("FreqPSname");
                    if (freqPsData != null && freqPsData.length >= 4) {
                        // FreqPSname Format: freq (2 bytes) + PI (2 bytes) + PS (8 bytes)
                        // Versuche PI aus verschiedenen Byte-Positionen zu extrahieren
                        int pi1 = (freqPsData[0] & 0xFF) | ((freqPsData[1] & 0xFF) << 8); // LE bytes 0-1
                        int pi2 = (freqPsData[2] & 0xFF) | ((freqPsData[3] & 0xFF) << 8); // LE bytes 2-3
                        int pi3 = ((freqPsData[0] & 0xFF) << 8) | (freqPsData[1] & 0xFF); // BE bytes 0-1
                        int pi4 = ((freqPsData[2] & 0xFF) << 8) | (freqPsData[3] & 0xFF); // BE bytes 2-3

                        // Finde gültigen PI (0x1000-0xFFFF)
                        for (int candidatePi : new int[]{pi1, pi2, pi3, pi4}) {
                            if (candidatePi >= 0x1000 && candidatePi <= 0xFFFF) {
                                currentPi = candidatePi;
                                lastPiTimestamp = System.currentTimeMillis();
                                Log.i(TAG, "pollRds: PI from RDSGETFREQPS: 0x" + Integer.toHexString(currentPi));
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "RDSGETFREQPS failed: " + e.getMessage());
            }
        }

        // Fallback 3: PI anhand PS-Name nachschlagen (bekannte Sender)
        if (currentPi == 0 && currentPs != null && !currentPs.isEmpty()) {
            int lookupPi = lookupPiByPs(currentPs);
            if (lookupPi != 0) {
                currentPi = lookupPi;
                lastPiTimestamp = System.currentTimeMillis();
                Log.d(TAG, "pollRds: PI from PS lookup '" + currentPs + "': 0x" + Integer.toHexString(currentPi));
            }
        }

        // Hardware-Frequenz abfragen via CMD_CURRENTFREQ (0x12)
        // Um zu erkennen ob sqlfm zu einer AF gewechselt hat
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fmNative.fmsyu_jni(CMD_CURRENTFREQ, inBundle, outBundle);
            Log.d(TAG, "pollRds: CMD_CURRENTFREQ returned: " + result + ", bundle size: " + outBundle.size());

            if (result == 0 && !outBundle.isEmpty()) {
                // Debug: Alle Keys anzeigen
                for (String key : outBundle.keySet()) {
                    Object val = outBundle.get(key);
                    Log.d(TAG, "pollRds: CURRENTFREQ key='" + key + "' = " + val);
                }

                // Versuche verschiedene Keys für Frequenz
                int freqInt = outBundle.getInt("frequency", outBundle.getInt("freq", outBundle.getInt("Freq", -1)));
                if (freqInt > 0) {
                    // Frequenz könnte in verschiedenen Formaten sein: 1037 = 103.7, 10370 = 103.70
                    if (freqInt > 10000) {
                        hardwareFrequency = freqInt / 100.0f;  // 10370 -> 103.70
                    } else {
                        hardwareFrequency = freqInt / 10.0f;   // 1037 -> 103.7
                    }
                    Log.i(TAG, "pollRds: Hardware freq = " + hardwareFrequency + " MHz (raw: " + freqInt + ")");
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "pollRds: CMD_CURRENTFREQ failed: " + e.getMessage());
        }

        // AF (Alternative Frequencies) Status abrufen via CMD_GETRDSCONFIG (0x1a)
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fmNative.fmsyu_jni(CMD_GETRDSCONFIG, inBundle, outBundle);

            if (result == 0 && !outBundle.isEmpty()) {
                // AF enabled status
                int afConfig = outBundle.getInt("AFconfig", -1);
                if (afConfig >= 0) {
                    afEnabled = (afConfig == 1);
                    lastAfTimestamp = System.currentTimeMillis();
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "pollRds: GETRDSCONFIG failed: " + e.getMessage());
        }

        // Methode 2: Via native activeAf() - gibt einzelne AF zurück
        if (currentAfList == null) {
            try {
                short afFreq = fmNative.activeAf();
                if (afFreq > 0) {
                    currentAfList = new short[]{afFreq};
                    lastAfTimestamp = System.currentTimeMillis();
                    float freqMhz = afFreq / 10.0f;
                    Log.i(TAG, "pollRds: AF from activeAf(): " + String.format("%.1f MHz", freqMhz));
                }
            } catch (Throwable e) {
                Log.d(TAG, "pollRds: activeAf() failed: " + e.getMessage());
            }
        }

        Log.d(TAG, "pollRds: currentPs='" + currentPs + "' currentRt='" + currentRt + "' rssi=" + currentRssi);

        // Callback aufrufen
        if (callback != null) {
            handler.post(() -> callback.onRdsUpdate(currentPs, currentRt, currentRssi, currentPi, currentPty, currentTp, currentTa, currentAfList));
        }
    }

    /**
     * Holt den PS (Program Service) Name vom FM-Chip.
     * Verwendet die NavRadio-Methode: fmsyu_jni(0x1e)
     */
    private String fetchPs() {
        // Methode 1: Via fmsyu_jni (NavRadio-Stil) - FMJNI_CMD_30_RDSGETPS
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();

            int result = fmNative.fmsyu_jni(CMD_RDSGETPS, inBundle, outBundle);
            Log.d(TAG, "fetchPs: fmsyu_jni(0x1e) returned: " + result);

            if (result == 0) {
                byte[] psData = outBundle.getByteArray("PSname");
                Log.d(TAG, "fetchPs: PSname bytes: " + (psData != null ? psData.length : "null"));
                if (psData != null && psData.length > 0) {
                    String ps = cleanRdsString(new String(psData, StandardCharsets.US_ASCII));
                    Log.d(TAG, "fetchPs: fmsyu_jni PS = '" + ps + "'");
                    if (!ps.isEmpty()) {
                        return ps;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "fmsyu_jni(RDSGETPS) failed: " + e.getMessage());
        }

        // Methode 2: Direkter nativer Aufruf
        try {
            byte[] ps = fmNative.getPs();
            Log.d(TAG, "fetchPs: native getPs() bytes: " + (ps != null ? ps.length : "null"));
            if (ps != null && ps.length > 0) {
                String result = cleanRdsString(new String(ps, StandardCharsets.US_ASCII));
                Log.d(TAG, "fetchPs: native getPs = '" + result + "'");
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "getPs() failed: " + e.getMessage());
        }

        // Methode 3: getPsString Wrapper
        try {
            String ps = fmNative.getPsString();
            Log.d(TAG, "fetchPs: getPsString = '" + ps + "'");
            if (ps != null && !ps.isEmpty()) {
                return cleanRdsString(ps);
            }
        } catch (Throwable e) {
            Log.w(TAG, "getPsString() failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Holt den RT (Radio Text) vom FM-Chip.
     * Verwendet die NavRadio-Methode: fmsyu_jni(0x1f)
     */
    private String fetchRt() {
        // Methode 1: Via fmsyu_jni (NavRadio-Stil) - FMJNI_CMD_31_RDSGETTEXT
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();

            int result = fmNative.fmsyu_jni(CMD_RDSGETTEXT, inBundle, outBundle);
            Log.d(TAG, "fetchRt: fmsyu_jni(0x1f) returned: " + result);

            if (result == 0) {
                int textSize = outBundle.getInt("TextSize", 0);
                Log.d(TAG, "fetchRt: TextSize = " + textSize);
                if (textSize > 0) {
                    byte[] textData = outBundle.getByteArray("Text");
                    if (textData != null && textData.length > 0) {
                        String rt = cleanRdsString(new String(textData, StandardCharsets.UTF_8));
                        Log.d(TAG, "fetchRt: fmsyu_jni RT = '" + rt + "'");
                        if (!rt.isEmpty()) {
                            return rt;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "fmsyu_jni(RDSGETTEXT) failed: " + e.getMessage());
        }

        // Methode 2: Direkter nativer Aufruf
        try {
            byte[] rt = fmNative.getLrText();
            Log.d(TAG, "fetchRt: native getLrText() bytes: " + (rt != null ? rt.length : "null"));
            if (rt != null && rt.length > 0) {
                String result = cleanRdsString(new String(rt, StandardCharsets.UTF_8));
                Log.d(TAG, "fetchRt: native getLrText = '" + result + "'");
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "getLrText() failed: " + e.getMessage());
        }

        // Methode 3: getRadioText Wrapper
        try {
            String rt = fmNative.getRadioText();
            Log.d(TAG, "fetchRt: getRadioText = '" + rt + "'");
            if (rt != null && !rt.isEmpty()) {
                return cleanRdsString(rt);
            }
        } catch (Throwable e) {
            Log.w(TAG, "getRadioText() failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Bereinigt RDS-Strings von Steuerzeichen und "not support" Meldungen.
     */
    private String cleanRdsString(String s) {
        if (s == null) return "";

        // Steuerzeichen entfernen
        s = s.replaceAll("[\\x00-\\x1F]", "").trim();

        // "not support" Meldungen filtern
        if (s.toLowerCase().contains("not support")) {
            return "";
        }

        return s;
    }

    /**
     * Tune zu einer Frequenz.
     */
    public boolean tune(float frequency) {
        Log.i(TAG, "Tune to " + frequency + " MHz");
        clearRds();

        try {
            return fmNative.tune(frequency);
        } catch (Throwable e) {
            Log.w(TAG, "tune failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Löscht die aktuellen RDS-Daten.
     */
    public void clearRds() {
        currentPs = "";
        currentRt = "";
        currentRssi = 0;
        currentPi = 0;
        currentPty = 0;
        currentTp = 0;
        currentTa = 0;
        currentAfList = null;
        // Alle Timestamps zurücksetzen
        lastPsTimestamp = 0;
        lastPiTimestamp = 0;
        lastPtyTimestamp = 0;
        lastRtTimestamp = 0;
        lastRssiTimestamp = 0;
        lastTpTaTimestamp = 0;
        lastAfTimestamp = 0;
    }

    // Getter
    public String getPs() { return currentPs; }
    public long getPsAgeMs() { return lastPsTimestamp > 0 ? System.currentTimeMillis() - lastPsTimestamp : -1; }
    public String getRt() { return currentRt; }
    public long getRtAgeMs() { return lastRtTimestamp > 0 ? System.currentTimeMillis() - lastRtTimestamp : -1; }
    public int getRssi() { return currentRssi; }
    public long getRssiAgeMs() { return lastRssiTimestamp > 0 ? System.currentTimeMillis() - lastRssiTimestamp : -1; }
    public int getPi() { return currentPi; }
    public long getPiAgeMs() { return lastPiTimestamp > 0 ? System.currentTimeMillis() - lastPiTimestamp : -1; }
    public int getPty() { return currentPty; }
    public long getPtyAgeMs() { return lastPtyTimestamp > 0 ? System.currentTimeMillis() - lastPtyTimestamp : -1; }
    public int getTp() { return currentTp; }
    public int getTa() { return currentTa; }
    public long getTpTaAgeMs() { return lastTpTaTimestamp > 0 ? System.currentTimeMillis() - lastTpTaTimestamp : -1; }
    public short[] getAfList() { return currentAfList; }
    public boolean isAfEnabled() { return afEnabled; }
    public long getAfAgeMs() { return lastAfTimestamp > 0 ? System.currentTimeMillis() - lastAfTimestamp : -1; }
    public boolean isPolling() { return isPolling; }
    public float getHardwareFrequency() { return hardwareFrequency; }
    public void setUiFrequency(float freq) { this.uiFrequency = freq; }
    public boolean isUsingAlternateFrequency() {
        // Prüfen ob Hardware auf anderer Frequenz ist als UI zeigt
        // Toleranz von 0.05 MHz für Rundungsfehler
        return hardwareFrequency > 0 && uiFrequency > 0 &&
               Math.abs(hardwareFrequency - uiFrequency) > 0.05f;
    }

    /**
     * Wandelt PTY-Code in lesbaren Namen um (RDS/Europe Standard).
     */
    public static String getPtyName(int pty) {
        String[] ptyNames = {
            "None", "News", "Current Affairs", "Information",
            "Sport", "Education", "Drama", "Culture",
            "Science", "Varied", "Pop Music", "Rock Music",
            "Easy Listening", "Light Classical", "Serious Classical", "Other Music",
            "Weather", "Finance", "Children's", "Social Affairs",
            "Religion", "Phone In", "Travel", "Leisure",
            "Jazz Music", "Country Music", "National Music", "Oldies Music",
            "Folk Music", "Documentary", "Alarm Test", "Alarm"
        };
        if (pty >= 0 && pty < ptyNames.length) {
            return ptyNames[pty];
        }
        return "Unknown";
    }

    /**
     * PI-Code Lookup anhand des PS-Namens (Fallback wenn Hardware keinen PI liefert).
     * Bekannte österreichische Sender.
     */
    public static int lookupPiByPs(String ps) {
        if (ps == null || ps.isEmpty()) return 0;
        String psUpper = ps.toUpperCase().trim();

        // ORF Sender (Österreich, Ländercode A = 0xA)
        if (psUpper.contains("OE3") || psUpper.contains("Ö3") || psUpper.contains("OE 3") || psUpper.contains("HITRAD")) return 0xA503;
        if (psUpper.contains("OE1") || psUpper.contains("Ö1") || psUpper.contains("OE 1")) return 0xA501;
        if (psUpper.contains("FM4") || psUpper.contains("FM 4")) return 0xA504;
        if (psUpper.contains("RADIO WIEN") || psUpper.contains("OE2W")) return 0xA209;
        if (psUpper.contains("RADIO NOE") || psUpper.contains("OE2N") || psUpper.contains("NÖ")) return 0xA20E;
        if (psUpper.contains("RADIO BGLD") || psUpper.contains("OE2B") || psUpper.contains("BURGENLAND")) return 0xA211;
        if (psUpper.contains("RADIO STMK") || psUpper.contains("OE2ST") || psUpper.contains("STEIERMARK")) return 0xA206;
        if (psUpper.contains("RADIO KTN") || psUpper.contains("OE2K") || psUpper.contains("KÄRNTEN")) return 0xA202;
        if (psUpper.contains("RADIO OOE") || psUpper.contains("OE2O") || psUpper.contains("OBERÖSTERREICH")) return 0xA204;
        if (psUpper.contains("RADIO SBG") || psUpper.contains("OE2S") || psUpper.contains("SALZBURG")) return 0xA205;
        if (psUpper.contains("RADIO TIROL") || psUpper.contains("OE2T")) return 0xA207;
        if (psUpper.contains("RADIO VBG") || psUpper.contains("OE2V") || psUpper.contains("VORARLBERG")) return 0xA208;

        // Kronehit
        if (psUpper.contains("KRONEHIT") || psUpper.contains("KRONE HIT")) return 0xA0CA;

        // Antenne Sender
        if (psUpper.contains("ANTENNE")) {
            if (psUpper.contains("WIEN")) return 0xA318;
            if (psUpper.contains("STEI") || psUpper.contains("STMK")) return 0xA31A;
            if (psUpper.contains("KÄRN") || psUpper.contains("KTN")) return 0xA31B;
            if (psUpper.contains("SALZ") || psUpper.contains("SBG")) return 0xA31C;
            if (psUpper.contains("TIROL")) return 0xA31D;
            if (psUpper.contains("VORARL") || psUpper.contains("VBG")) return 0xA31E;
            if (psUpper.contains("BAYERN")) return 0xD318;
        }

        // Life Radio
        if (psUpper.contains("LIFE RADIO") || psUpper.contains("LIFERADIO")) return 0xA320;

        // Radio Arabella
        if (psUpper.contains("ARABELLA")) return 0xA350;

        // 88.6 Der Musiksender
        if (psUpper.contains("88.6") || psUpper.contains("88,6")) return 0xA386;

        // Energy
        if (psUpper.contains("ENERGY") || psUpper.contains("NRJ")) return 0xA0E0;

        return 0;
    }
}
