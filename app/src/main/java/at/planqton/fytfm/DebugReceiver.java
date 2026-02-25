package at.planqton.fytfm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.android.fmradio.FmNative;

import java.nio.charset.StandardCharsets;

/**
 * DebugReceiver - Ermöglicht Radio-Steuerung über ADB
 *
 * USAGE:
 *   adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "<command>" [--es freq "90.4"]
 *
 * COMMANDS:
 *   status     - Zeigt aktuellen Status (Library, TWUtil, Radio)
 *   poweron    - Schaltet Radio ein
 *   poweroff   - Schaltet Radio aus
 *   tune       - Tuned zu Frequenz (--es freq "90.4")
 *   rds        - Liest RDS-Daten (PS, RT, RSSI)
 *   rds_enable - Aktiviert RDS
 *   rssi       - Liest nur RSSI
 *   seek_up    - Seek zur nächsten Frequenz mit Signal (aufwärts)
 *   seek_down  - Seek zur nächsten Frequenz mit Signal (abwärts)
 *   mute       - Audio stumm schalten
 *   unmute     - Audio wieder einschalten
 *   test_all   - Führt kompletten Test durch
 *
 * EXAMPLES:
 *   adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "status"
 *   adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "poweron"
 *   adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "tune" --es freq "90.4"
 *   adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "rds"
 *   adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "test_all"
 */
public class DebugReceiver extends BroadcastReceiver {
    private static final String TAG = "fytFM_DEBUG";
    public static final String ACTION = "at.planqton.fytfm.DEBUG";

    // JNI Command Codes
    private static final int CMD_RDSONOFF = 0x15;      // 21
    private static final int CMD_RDSGETPS = 0x1e;      // 30
    private static final int CMD_RDSGETTEXT = 0x1f;    // 31
    private static final int CMD_GETRSSI = 0x0b;       // 11

    private static TWUtilHelper twUtil;
    private static boolean radioOn = false;
    private static float currentFreq = 90.4f;

    public static void setTwUtil(TWUtilHelper util) {
        twUtil = util;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;

        String cmd = intent.getStringExtra("cmd");
        String freq = intent.getStringExtra("freq");

        if (cmd == null) {
            log("ERROR: No command specified. Use --es cmd \"<command>\"");
            return;
        }

        log("========================================");
        log("DEBUG COMMAND: " + cmd);
        log("========================================");

        switch (cmd.toLowerCase()) {
            case "status":
                cmdStatus();
                break;
            case "poweron":
                cmdPowerOn(freq != null ? Float.parseFloat(freq) : currentFreq);
                break;
            case "poweroff":
                cmdPowerOff();
                break;
            case "tune":
                if (freq == null) {
                    log("ERROR: tune requires --es freq \"<frequency>\"");
                } else {
                    cmdTune(Float.parseFloat(freq));
                }
                break;
            case "rds":
                cmdReadRds();
                break;
            case "rds_enable":
                cmdEnableRds();
                break;
            case "rssi":
                cmdReadRssi();
                break;
            case "test_all":
                cmdTestAll(freq != null ? Float.parseFloat(freq) : 90.4f);
                break;
            case "seek_up":
                cmdSeek(true);
                break;
            case "seek_down":
                cmdSeek(false);
                break;
            case "mute":
                cmdMute(true);
                break;
            case "unmute":
                cmdMute(false);
                break;
            case "mcu_mute":
                cmdMcuMute(true);
                break;
            case "mcu_unmute":
                cmdMcuMute(false);
                break;
            case "twutil":
                cmdTwUtilTest();
                break;
            case "sqlfm":
                cmdSqlFmTest();
                break;
            case "sqlfm_probe":
                cmdSqlFmProbe(freq != null ? Float.parseFloat(freq) : 90.4f);
                break;
            case "sqlfm_ps":
                cmdSqlFmReadPs();
                break;
            default:
                log("Unknown command: " + cmd);
                log("Available: status, poweron, poweroff, tune, rds, rds_enable, rssi, seek_up, seek_down, mute, unmute, test_all, twutil, sqlfm, sqlfm_probe, sqlfm_ps");
        }

        log("========================================");
    }

    private void cmdStatus() {
        log("--- STATUS ---");
        log("Library loaded: " + FmNative.isLibraryLoaded());
        log("TWUtil available: " + (twUtil != null && twUtil.isAvailable()));
        log("Radio on: " + radioOn);
        log("Current freq: " + currentFreq + " MHz");

        if (FmNative.isLibraryLoaded()) {
            FmNative fm = FmNative.getInstance();
            try {
                int rssi = fm.getrssi();
                log("RSSI: " + rssi + " dBm");
            } catch (Throwable e) {
                log("RSSI: ERROR - " + e.getMessage());
            }

            try {
                int rdsSupport = fm.isRdsSupport();
                log("RDS Support: " + rdsSupport);
            } catch (Throwable e) {
                log("RDS Support: ERROR - " + e.getMessage());
            }
        }
    }

    private void cmdPowerOn(float freq) {
        log("--- POWER ON @ " + freq + " MHz ---");
        currentFreq = freq;

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // Step 1: TWUtil
        if (twUtil != null && twUtil.isAvailable()) {
            log("Step 1: TWUtil.initRadioSequence()");
            twUtil.initRadioSequence();

            log("Step 2: TWUtil.radioOn()");
            twUtil.radioOn();
        } else {
            log("WARN: TWUtil not available, skipping MCU init");
        }

        // Step 3: Open device
        log("Step 3: openDev()");
        boolean openResult = false;
        try {
            openResult = fm.openDev();
            log("  openDev() = " + openResult);
        } catch (Throwable e) {
            log("  openDev() EXCEPTION: " + e.getMessage());
        }

        // Step 4: Power up
        log("Step 4: powerUp(" + freq + ")");
        boolean powerResult = false;
        try {
            powerResult = fm.powerUp(freq);
            log("  powerUp() = " + powerResult);
        } catch (Throwable e) {
            log("  powerUp() EXCEPTION: " + e.getMessage());
        }

        // Step 5: Tune
        log("Step 5: tune(" + freq + ")");
        boolean tuneResult = false;
        try {
            tuneResult = fm.tune(freq);
            log("  tune() = " + tuneResult);
        } catch (Throwable e) {
            log("  tune() EXCEPTION: " + e.getMessage());
        }

        radioOn = openResult && powerResult;
        log("RESULT: radioOn = " + radioOn);

        // Step 6: Enable RDS
        if (radioOn) {
            cmdEnableRds();
        }
    }

    private void cmdPowerOff() {
        log("--- POWER OFF ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        try {
            fm.powerDown(0);
            log("powerDown(0) OK");
        } catch (Throwable e) {
            log("powerDown EXCEPTION: " + e.getMessage());
        }

        try {
            fm.closeDev();
            log("closeDev() OK");
        } catch (Throwable e) {
            log("closeDev EXCEPTION: " + e.getMessage());
        }

        if (twUtil != null && twUtil.isAvailable()) {
            twUtil.radioOff();
            log("TWUtil.radioOff() OK");
        }

        radioOn = false;
        log("RESULT: radioOn = " + radioOn);
    }

    private void cmdTune(float freq) {
        log("--- TUNE TO " + freq + " MHz ---");
        currentFreq = freq;

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        try {
            boolean result = fm.tune(freq);
            log("tune(" + freq + ") = " + result);
        } catch (Throwable e) {
            log("tune EXCEPTION: " + e.getMessage());
        }

        // Read RSSI after tune
        try {
            Thread.sleep(500);
            int rssi = fm.getrssi();
            log("RSSI after tune: " + rssi + " dBm");
        } catch (Throwable e) {
            log("RSSI read failed: " + e.getMessage());
        }
    }

    /**
     * Manueller Seek zum nächsten Sender mit Signal.
     * Native seek() hat JNI-Bugs, daher manuell implementiert.
     */
    private void cmdSeek(boolean seekUp) {
        log("--- SEEK " + (seekUp ? "UP" : "DOWN") + " from " + currentFreq + " MHz ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        if (!radioOn) {
            log("ERROR: Radio is OFF! Use 'poweron' first.");
            return;
        }

        FmNative fm = FmNative.getInstance();

        float step = 0.1f;
        float minFreq = 87.5f;
        float maxFreq = 108.0f;
        int rssiThreshold = 200;  // 0-255 Skala, ~200+ = gutes Signal

        float freq = seekUp ? currentFreq + step : currentFreq - step;
        int attempts = 0;
        int maxAttempts = 205;  // Ganzes Band

        log("Starting seek... (threshold: " + rssiThreshold + " dBm)");

        while (attempts < maxAttempts) {
            // Wrap around
            if (freq > maxFreq) freq = minFreq;
            if (freq < minFreq) freq = maxFreq;

            // Tune
            try {
                fm.tune(freq);
            } catch (Throwable e) {
                log("tune(" + freq + ") EXCEPTION: " + e.getMessage());
                break;
            }

            // Warten
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }

            // RSSI messen
            int rssi = 0;
            try {
                rssi = fm.getrssi();
            } catch (Throwable e) {
                // ignore
            }

            log(String.format("  %.1f MHz -> RSSI %d", freq, rssi));

            if (rssi >= rssiThreshold) {
                log("*** FOUND: " + String.format("%.1f", freq) + " MHz (RSSI: " + rssi + ") ***");
                currentFreq = freq;
                return;
            }

            freq = seekUp ? freq + step : freq - step;
            attempts++;
        }

        log("No station found after " + attempts + " attempts");
        // Zurück zur ursprünglichen Frequenz
        try {
            fm.tune(currentFreq);
        } catch (Throwable e) {
            // ignore
        }
    }

    private void cmdMute(boolean mute) {
        log("--- " + (mute ? "MUTE" : "UNMUTE") + " (FmNative) ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        try {
            int result = fm.setMute(mute);
            log("FmNative.setMute(" + mute + ") = " + result);
        } catch (Throwable e) {
            log("setMute EXCEPTION: " + e.getMessage());
        }
    }

    private void cmdMcuMute(boolean mute) {
        log("--- " + (mute ? "MCU MUTE" : "MCU UNMUTE") + " (TWUtil) ---");

        if (twUtil == null || !twUtil.isAvailable()) {
            log("ERROR: TWUtil not available!");
            return;
        }

        try {
            if (mute) {
                twUtil.mute();
                log("TWUtil.mute() called");
            } else {
                twUtil.unmute();
                log("TWUtil.unmute() called");
            }
        } catch (Throwable e) {
            log("TWUtil mute EXCEPTION: " + e.getMessage());
        }
    }

    private void cmdEnableRds() {
        log("--- ENABLE RDS ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // Method 1: setRds(true)
        try {
            int result = fm.setRds(true);
            log("setRds(true) = " + result);
        } catch (Throwable e) {
            log("setRds EXCEPTION: " + e.getMessage());
        }

        // Method 2: fmsyu_jni(0x15)
        try {
            Bundle inBundle = new Bundle();
            inBundle.putInt("RdsOnOff", 1);
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_RDSONOFF, inBundle, outBundle);
            log("fmsyu_jni(0x15, RdsOnOff=1) = " + result);
        } catch (Throwable e) {
            log("fmsyu_jni(0x15) EXCEPTION: " + e.getMessage());
        }

        // Method 3: readRds to trigger decoder
        try {
            short result = fm.readRds();
            log("readRds() = " + result);
        } catch (Throwable e) {
            log("readRds EXCEPTION: " + e.getMessage());
        }
    }

    private void cmdReadRds() {
        log("--- READ RDS ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // Trigger RDS decoder
        try {
            short rdsResult = fm.readRds();
            log("readRds() = " + rdsResult);
        } catch (Throwable e) {
            log("readRds EXCEPTION: " + e.getMessage());
        }

        // Read PS via fmsyu_jni(0x1e)
        log("--- PS via fmsyu_jni(0x1e) ---");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_RDSGETPS, inBundle, outBundle);
            log("fmsyu_jni(0x1e) = " + result);

            byte[] psData = outBundle.getByteArray("PSname");
            if (psData != null && psData.length > 0) {
                String ps = new String(psData, StandardCharsets.US_ASCII).trim();
                log("PSname bytes: " + psData.length);
                log("PSname hex: " + bytesToHex(psData));
                log("PSname string: '" + ps + "'");
            } else {
                log("PSname: null or empty");
            }
        } catch (Throwable e) {
            log("fmsyu_jni(0x1e) EXCEPTION: " + e.getMessage());
        }

        // Read PS via native getPs()
        log("--- PS via native getPs() ---");
        try {
            byte[] ps = fm.getPs();
            if (ps != null && ps.length > 0) {
                log("getPs() bytes: " + ps.length);
                log("getPs() hex: " + bytesToHex(ps));
                log("getPs() string: '" + new String(ps, StandardCharsets.US_ASCII).trim() + "'");
            } else {
                log("getPs(): null or empty");
            }
        } catch (Throwable e) {
            log("getPs() EXCEPTION: " + e.getMessage());
        }

        // Read RT via fmsyu_jni(0x1f)
        log("--- RT via fmsyu_jni(0x1f) ---");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_RDSGETTEXT, inBundle, outBundle);
            log("fmsyu_jni(0x1f) = " + result);

            int textSize = outBundle.getInt("TextSize", 0);
            log("TextSize: " + textSize);

            byte[] textData = outBundle.getByteArray("Text");
            if (textData != null && textData.length > 0) {
                log("Text bytes: " + textData.length);
                log("Text hex: " + bytesToHex(textData));
                log("Text string: '" + new String(textData, StandardCharsets.UTF_8).trim() + "'");
            } else {
                log("Text: null or empty");
            }
        } catch (Throwable e) {
            log("fmsyu_jni(0x1f) EXCEPTION: " + e.getMessage());
        }

        // Read RT via native getLrText()
        log("--- RT via native getLrText() ---");
        try {
            byte[] rt = fm.getLrText();
            if (rt != null && rt.length > 0) {
                log("getLrText() bytes: " + rt.length);
                log("getLrText() hex: " + bytesToHex(rt));
                log("getLrText() string: '" + new String(rt, StandardCharsets.UTF_8).trim() + "'");
            } else {
                log("getLrText(): null or empty");
            }
        } catch (Throwable e) {
            log("getLrText() EXCEPTION: " + e.getMessage());
        }

        // Read RSSI
        cmdReadRssi();
    }

    private void cmdReadRssi() {
        log("--- READ RSSI ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // Via fmsyu_jni(0x0b) - die einzige funktionierende Methode
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_GETRSSI, inBundle, outBundle);
            int rssi = outBundle.getInt("rssilevel", -1);
            log("fmsyu_jni(0x0b) = " + result + ", rssilevel = " + rssi + " dBm");
        } catch (Throwable e) {
            log("fmsyu_jni(0x0b) EXCEPTION: " + e.getMessage());
        }

        // Wrapper-Methode (verwendet intern fmsyu_jni)
        try {
            int rssi = fm.getrssi();
            log("getrssi() wrapper = " + rssi + " dBm");
        } catch (Throwable e) {
            log("getrssi() EXCEPTION: " + e.getMessage());
        }
    }

    private void cmdTwUtilTest() {
        log("--- TWUTIL TEST ---");

        if (twUtil == null) {
            log("ERROR: TWUtil is null");
            return;
        }

        log("TWUtil.isAvailable() = " + twUtil.isAvailable());

        if (!twUtil.isAvailable()) {
            log("TWUtil not available on this device");
            return;
        }

        log("Testing TWUtil commands...");

        // Test initRadioSequence
        log("initRadioSequence():");
        twUtil.initRadioSequence();

        // Test radioOn
        log("radioOn():");
        twUtil.radioOn();

        log("TWUtil test complete");
    }

    private void cmdSqlFmTest() {
        log("--- SQLFM SERVICE TEST ---");

        SqlFMServiceClient sqlFm = new SqlFMServiceClient();

        if (!sqlFm.isConnected()) {
            log("ERROR: Could not connect to sqlfmservice");
            return;
        }

        log("Connected to sqlfmservice!");

        // Register callback
        log("Registering callback...");
        sqlFm.registerCallback();

        // Enable RDS
        log("Enabling RDS via sqlfmservice...");
        sqlFm.enableRds();

        // Probe service methods
        log("Probing service methods...");
        sqlFm.probeService();

        // Wait for RDS data
        log("Waiting 2 seconds for RDS callbacks...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            // ignore
        }

        // Tune to 90.4 MHz via sqlfmservice
        log("Tuning to 90.4 MHz via sqlfmservice...");
        sqlFm.tune(90.4f);

        // Wait for tune
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // ignore
        }

        // Get PS and RT
        log("--- RDS DATA FROM SQLFM ---");
        String ps = sqlFm.getPs();
        String rt = sqlFm.getRt();
        log("PS from callback: '" + ps + "'");
        log("RT from callback: '" + rt + "'");

        // Try getLPSname
        String lps = sqlFm.getLPSname(90.4f);
        log("LPSname(90.4): '" + lps + "'");

        // Get current frequency
        float curFreq = sqlFm.getCurrentFrequency();
        log("Current Freq from sqlfm: " + curFreq + " MHz");

        log("SQLFM test complete");
    }

    private void cmdTestAll(float freq) {
        log("========== FULL TEST @ " + freq + " MHz ==========");

        // 1. Status
        cmdStatus();

        // 2. Power On
        cmdPowerOn(freq);

        // 3. Wait for RDS
        log("Waiting 3 seconds for RDS data...");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            // ignore
        }

        // 4. Read RDS
        cmdReadRds();

        log("========== FULL TEST COMPLETE ==========");
    }

    /**
     * Erweiterte Probe-Funktion für sqlfmservice
     * Probiert alle möglichen Transaction-Codes mit Frequenz-Parameter
     */
    private void cmdSqlFmProbe(float freq) {
        log("--- SQLFM PROBE @ " + freq + " MHz ---");

        SqlFMServiceClient sqlFm = new SqlFMServiceClient();

        if (!sqlFm.isConnected()) {
            log("ERROR: Could not connect to sqlfmservice");
            return;
        }

        log("Connected to sqlfmservice!");

        // Register callback first
        log("Registering callback...");
        sqlFm.registerCallback();

        // Enable RDS
        log("Enabling RDS...");
        sqlFm.enableRds();

        // Tune to frequency via sqlfmservice
        log("Tuning to " + freq + " MHz...");
        sqlFm.tune(freq);

        // Wait a bit
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            // ignore
        }

        // Probe getLPSname-style methods
        log("Probing getLPSname methods...");
        sqlFm.probeLPSname(freq);

        // Try direct PS read
        log("--- DIRECT PS READ ---");
        String ps = sqlFm.readPsDirect();
        log("Direct PS: '" + ps + "'");

        log("Probe complete");
    }

    /**
     * Versucht PS direkt vom sqlfmservice zu lesen
     */
    private void cmdSqlFmReadPs() {
        log("--- SQLFM READ PS ---");

        SqlFMServiceClient sqlFm = new SqlFMServiceClient();

        if (!sqlFm.isConnected()) {
            log("ERROR: Could not connect to sqlfmservice");
            return;
        }

        // Versuche PS direkt zu lesen
        String ps = sqlFm.readPsDirect();
        log("Direct PS: '" + ps + "'");

        // Aus Callback
        String psCallback = sqlFm.getPs();
        log("Callback PS: '" + psCallback + "'");

        // LPSname für aktuelle Frequenz
        float curFreq = sqlFm.getCurrentFrequency();
        if (curFreq > 0) {
            String lps = sqlFm.getLPSname(curFreq);
            log("LPSname(" + curFreq + "): '" + lps + "'");
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
