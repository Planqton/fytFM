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

    // RDS Extended Commands (zu testen)
    private static final int CMD_RDSAFCONFIG = 0x18;   // 24 - RDS AF Config SET
    private static final int CMD_RDSGETAFLIST = 0x19;  // 25 - ? AF List GET
    private static final int CMD_GETRDSCONFIG = 0x1a;  // 26 - Get RDS Config
    private static final int CMD_GETRDSSTATE = 0x1b;   // 27 - Get RDS State
    private static final int CMD_RDSGETCT = 0x1c;      // 28 - ? Clock Time GET
    private static final int CMD_RDSGETFREQPS = 0x20;  // 32 - Get FreqPS (PI)

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
            case "rds_af":
                cmdTestAfCommands();
                break;
            case "rds_ct":
                cmdTestCtCommands();
                break;
            case "rds_probe":
                cmdProbeRdsCommands();
                break;
            case "scan_test":
                cmdScanTest();
                break;
            case "autoscan":
                cmdAutoScan();
                break;
            case "native_scan":
                cmdNativeScan();
                break;
            default:
                log("Unknown command: " + cmd);
                log("Available: status, poweron, poweroff, tune, rds, rds_enable, rssi, seek_up, seek_down, mute, unmute, test_all, twutil, sqlfm, sqlfm_probe, sqlfm_ps, rds_af, rds_ct, rds_probe");
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

    /**
     * Testet AF (Alternative Frequencies) Commands
     */
    private void cmdTestAfCommands() {
        log("--- TEST AF COMMANDS ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // 1. Native activeAf()
        log("=== Native activeAf() ===");
        try {
            short af = fm.activeAf();
            log("activeAf() = " + af + " (0x" + Integer.toHexString(af & 0xFFFF) + ")");
            if (af > 0) {
                float afMhz = af / 10.0f;
                log("  -> " + afMhz + " MHz");
            }
        } catch (Throwable e) {
            log("activeAf() EXCEPTION: " + e.getMessage());
        }

        // 2. CMD_RDSAFCONFIG (0x18) - AF Config SET/GET
        log("=== fmsyu_jni(0x18) - RDSAFCONFIG ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_RDSAFCONFIG, inBundle, outBundle);
            log("fmsyu_jni(0x18) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x18) EXCEPTION: " + e.getMessage());
        }

        // 3. CMD_RDSGETAFLIST (0x19) - vermutlich AF List GET
        log("=== fmsyu_jni(0x19) - RDSGETAFLIST? ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_RDSGETAFLIST, inBundle, outBundle);
            log("fmsyu_jni(0x19) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x19) EXCEPTION: " + e.getMessage());
        }

        // 4. CMD_GETRDSCONFIG (0x1a) - Get RDS Config (könnte AF enthalten)
        log("=== fmsyu_jni(0x1a) - GETRDSCONFIG ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_GETRDSCONFIG, inBundle, outBundle);
            log("fmsyu_jni(0x1a) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x1a) EXCEPTION: " + e.getMessage());
        }

        // 5. CMD_GETRDSSTATE (0x1b) - Get RDS State
        log("=== fmsyu_jni(0x1b) - GETRDSSTATE ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_GETRDSSTATE, inBundle, outBundle);
            log("fmsyu_jni(0x1b) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x1b) EXCEPTION: " + e.getMessage());
        }

        log("AF test complete");
    }

    /**
     * Testet CT (Clock Time) Commands
     */
    private void cmdTestCtCommands() {
        log("--- TEST CT (CLOCK TIME) COMMANDS ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // 1. CMD_RDSGETCT (0x1c) - vermutlich Clock Time
        log("=== fmsyu_jni(0x1c) - RDSGETCT? ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_RDSGETCT, inBundle, outBundle);
            log("fmsyu_jni(0x1c) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x1c) EXCEPTION: " + e.getMessage());
        }

        // 2. Probiere 0x1d
        log("=== fmsyu_jni(0x1d) ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(0x1d, inBundle, outBundle);
            log("fmsyu_jni(0x1d) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x1d) EXCEPTION: " + e.getMessage());
        }

        // 3. Schaue in GETRDSSTATE (0x1b) nach CT-Feldern
        log("=== Check GETRDSSTATE for CT ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(CMD_GETRDSSTATE, inBundle, outBundle);
            log("fmsyu_jni(0x1b) = " + result);
            dumpBundle("outBundle (full)", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x1b) EXCEPTION: " + e.getMessage());
        }

        // 4. Probiere 0x16 und 0x17
        log("=== fmsyu_jni(0x16) ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(0x16, inBundle, outBundle);
            log("fmsyu_jni(0x16) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x16) EXCEPTION: " + e.getMessage());
        }

        log("=== fmsyu_jni(0x17) ===");
        try {
            Bundle inBundle = new Bundle();
            Bundle outBundle = new Bundle();
            int result = fm.fmsyu_jni(0x17, inBundle, outBundle);
            log("fmsyu_jni(0x17) = " + result);
            dumpBundle("outBundle", outBundle);
        } catch (Throwable e) {
            log("fmsyu_jni(0x17) EXCEPTION: " + e.getMessage());
        }

        log("CT test complete");
    }

    /**
     * Probiert alle RDS-relevanten Commands durch (0x15 - 0x21)
     */
    private void cmdProbeRdsCommands() {
        log("--- PROBE ALL RDS COMMANDS (0x15 - 0x21) ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        for (int cmd = 0x15; cmd <= 0x21; cmd++) {
            log("=== fmsyu_jni(0x" + Integer.toHexString(cmd) + ") ===");
            try {
                Bundle inBundle = new Bundle();
                Bundle outBundle = new Bundle();
                int result = fm.fmsyu_jni(cmd, inBundle, outBundle);
                log("  result = " + result);
                if (outBundle.size() > 0) {
                    dumpBundle("  outBundle", outBundle);
                } else {
                    log("  outBundle: empty");
                }
            } catch (Throwable e) {
                log("  EXCEPTION: " + e.getMessage());
            }
        }

        log("Probe complete");
    }

    /**
     * Gibt alle Keys und Werte eines Bundles aus
     */
    private void dumpBundle(String name, Bundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            log(name + ": empty");
            return;
        }

        log(name + " (" + bundle.size() + " keys):");
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value instanceof byte[]) {
                byte[] bytes = (byte[]) value;
                log("  " + key + " = byte[" + bytes.length + "]: " + bytesToHex(bytes));
                // Versuche als String zu interpretieren
                String asString = new String(bytes, StandardCharsets.UTF_8).trim();
                if (!asString.isEmpty() && isPrintable(asString)) {
                    log("    -> \"" + asString + "\"");
                }
                // Versuche als short[]-Frequenzliste zu interpretieren
                if (bytes.length >= 2 && bytes.length % 2 == 0) {
                    StringBuilder freqList = new StringBuilder();
                    for (int i = 0; i < bytes.length; i += 2) {
                        int freq = (bytes[i] & 0xFF) | ((bytes[i + 1] & 0xFF) << 8);
                        if (freq >= 875 && freq <= 1080) {
                            freqList.append(String.format("%.1f ", freq / 10.0f));
                        }
                    }
                    if (freqList.length() > 0) {
                        log("    -> Frequencies: " + freqList.toString().trim());
                    }
                }
            } else if (value instanceof short[]) {
                short[] shorts = (short[]) value;
                StringBuilder sb = new StringBuilder();
                for (short s : shorts) {
                    sb.append(s).append(" ");
                }
                log("  " + key + " = short[" + shorts.length + "]: " + sb.toString().trim());
                // Als Frequenzen interpretieren
                StringBuilder freqList = new StringBuilder();
                for (short s : shorts) {
                    if (s >= 875 && s <= 1080) {
                        freqList.append(String.format("%.1f ", s / 10.0f));
                    }
                }
                if (freqList.length() > 0) {
                    log("    -> Frequencies: " + freqList.toString().trim());
                }
            } else if (value instanceof int[]) {
                int[] ints = (int[]) value;
                StringBuilder sb = new StringBuilder();
                for (int i : ints) {
                    sb.append(i).append(" ");
                }
                log("  " + key + " = int[" + ints.length + "]: " + sb.toString().trim());
            } else if (value instanceof float[]) {
                float[] floats = (float[]) value;
                StringBuilder sb = new StringBuilder();
                for (float f : floats) {
                    sb.append(f).append(" ");
                }
                log("  " + key + " = float[" + floats.length + "]: " + sb.toString().trim());
                // Als Frequenzen interpretieren
                StringBuilder freqList = new StringBuilder();
                for (float f : floats) {
                    if (f >= 87.5f && f <= 108.0f) {
                        freqList.append(String.format("%.1f ", f));
                    } else if (f >= 875f && f <= 1080f) {
                        freqList.append(String.format("%.1f ", f / 10.0f));
                    }
                }
                if (freqList.length() > 0) {
                    log("    -> Frequencies: " + freqList.toString().trim());
                }
            } else {
                log("  " + key + " = " + value + " (" + (value != null ? value.getClass().getSimpleName() : "null") + ")");
            }
        }
    }

    private boolean isPrintable(String s) {
        for (char c : s.toCharArray()) {
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') return false;
        }
        return true;
    }

    /**
     * Testet Signal-Erkennung auf ein paar Frequenzen
     */
    private void cmdScanTest() {
        log("--- SCAN TEST ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // Initialize radio if not already on
        log("Initializing radio...");
        try {
            fm.openDev();
            fm.powerUp(87.5f);
            fm.setRds(true);
            radioOn = true;
            log("Radio initialized");
        } catch (Throwable e) {
            log("Radio init (may already be on): " + e.getMessage());
        }

        // Wait for radio to stabilize
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // ignore
        }

        float[] testFreqs = {88.6f, 90.4f, 92.0f, 95.8f, 98.3f, 101.3f, 103.8f, 105.8f};

        for (float freq : testFreqs) {
            log("Testing " + freq + " MHz...");

            // Tune
            try {
                fm.tune(freq);
                Thread.sleep(300);
            } catch (Throwable e) {
                log("  tune FAILED: " + e.getMessage());
                continue;
            }

            // RSSI via fmsyu_jni
            int rssi = 0;
            try {
                Bundle inBundle = new Bundle();
                Bundle outBundle = new Bundle();
                int result = fm.fmsyu_jni(CMD_GETRSSI, inBundle, outBundle);
                if (result == 0) {
                    rssi = outBundle.getInt("rssilevel", 0);
                }
            } catch (Throwable e) {
                // ignore
            }

            // PS check (kurz warten auf RDS)
            String ps = "";
            try {
                Thread.sleep(500);
                Bundle inBundle = new Bundle();
                Bundle outBundle = new Bundle();
                int result = fm.fmsyu_jni(CMD_RDSGETPS, inBundle, outBundle);
                if (result == 0) {
                    byte[] psData = outBundle.getByteArray("PSname");
                    if (psData != null && psData.length > 0) {
                        ps = new String(psData, StandardCharsets.US_ASCII).trim();
                    }
                }
            } catch (Throwable e) {
                // ignore
            }

            String status = rssi >= 20 ? "SIGNAL" : "weak";
            log(String.format("  %.1f MHz: RSSI=%d %s PS='%s'", freq, rssi, status, ps));
        }

        log("Scan test complete");
    }

    /**
     * Testet native FmNative Scan-Funktionen
     */
    private void cmdNativeScan() {
        log("--- NATIVE SCAN TEST ---");

        if (!FmNative.isLibraryLoaded()) {
            log("ERROR: Library not loaded!");
            return;
        }

        FmNative fm = FmNative.getInstance();

        // Radio initialisieren
        log("Initializing radio...");
        try {
            fm.openDev();
            fm.powerUp(87.5f);
            fm.setRds(true);
            Thread.sleep(500);
            log("Radio initialized");
        } catch (Exception e) {
            log("Radio init: " + e.getMessage());
        }

        // Test 1: FmNative.autoScan(band)
        log("=== Test 1: FmNative.autoScan(0) ===");
        try {
            long start = System.currentTimeMillis();
            short[] freqs = FmNative.autoScan(0);  // band 0 = EU
            long duration = System.currentTimeMillis() - start;

            if (freqs != null && freqs.length > 0) {
                log("autoScan found " + freqs.length + " stations in " + duration + "ms:");
                for (int i = 0; i < Math.min(freqs.length, 20); i++) {
                    log(String.format("  [%d] %.1f MHz", i+1, freqs[i]/10.0f));
                }
            } else {
                log("autoScan returned null/empty (duration: " + duration + "ms)");
            }
        } catch (Throwable e) {
            log("autoScan EXCEPTION: " + e.getMessage());
        }

        // Test 2: fm.sqlautoScan(band, freqList, rssiList)
        log("=== Test 2: fm.sqlautoScan(0, ...) ===");
        try {
            short[] freqList = new short[50];
            short[] rssiList = new short[50];

            long start = System.currentTimeMillis();
            int count = fm.sqlautoScan(0, freqList, rssiList);
            long duration = System.currentTimeMillis() - start;

            log("sqlautoScan returned count=" + count + " in " + duration + "ms");

            if (count > 0) {
                log("Found stations:");
                for (int i = 0; i < Math.min(count, 20); i++) {
                    log(String.format("  [%d] %.1f MHz (RSSI: %d)", i+1, freqList[i]/10.0f, rssiList[i]));
                }
            }
        } catch (Throwable e) {
            log("sqlautoScan EXCEPTION: " + e.getMessage());
        }

        log("Native scan test complete");
    }

    /**
     * Testet sqlfmservice AutoScan - so wie SYU Radio es macht
     */
    private void cmdAutoScan() {
        log("--- AUTOSCAN VIA SQLFMSERVICE ---");

        SqlFMServiceClient sqlFm = new SqlFMServiceClient();

        if (!sqlFm.isConnected()) {
            log("ERROR: Could not connect to sqlfmservice");
            return;
        }

        log("Connected to sqlfmservice!");

        // Erst Radio über sqlfmservice initialisieren
        log("Step 1: Testing openDev via sqlfmservice...");
        boolean openResult = sqlFm.testTransact(4);  // OPEN_DEV
        log("  openDev result: " + openResult);

        log("Step 2: Testing powerUp via sqlfmservice...");
        boolean powerResult = sqlFm.powerUpSql(87.5f);
        log("  powerUp result: " + powerResult);

        log("Step 3: Enable RDS...");
        boolean rdsResult = sqlFm.enableRds();
        log("  enableRds result: " + rdsResult);

        // Warten bis Radio stabil
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {}

        log("Step 4: Starting autoscan...");
        long startTime = System.currentTimeMillis();
        short[] freqs = sqlFm.autoScan();
        long duration = System.currentTimeMillis() - startTime;

        if (freqs != null && freqs.length > 0) {
            log("AutoScan found " + freqs.length + " stations in " + duration + "ms:");
            for (int i = 0; i < freqs.length; i++) {
                float mhz = freqs[i] / 10.0f;
                log(String.format("  [%d] %.1f MHz", i+1, mhz));
            }
        } else {
            log("AutoScan returned no stations (duration: " + duration + "ms)");
            log("Trying alternative: Reading cached station list...");

            // Vielleicht gibt es eine gespeicherte Liste?
            sqlFm.probeService();
        }

        log("AutoScan complete");
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
