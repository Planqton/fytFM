# fytFM - Dokumentation

FM-Radio App für FYT Android Head Units mit RDS-Unterstützung.

---

## Getestete Hardware

| Komponente | Details |
|------------|---------|
| **Plattform** | Unisoc UMS512 |
| **FM Tuner** | TDA7708 (externer IC) |
| **Android** | 10+ |
| **FM Service** | sqlfmservice |
| **TWUtil** | **NICHT verfügbar** auf dieser Plattform |

### Bestätigte Funktionen ✓
- **PS (Program Service)** - Sendername (z.B. "OE3")
- **RT (Radio Text)** - Zusatztext (z.B. "Nickelback - How You Remind Me")
- **RSSI** - Signalstärke in dBm
- **FREQ** - Frequenzanzeige und -wechsel

---

## Architektur

```
┌─────────────────────────────────────────────────────────────────┐
│                         MainActivity.kt                          │
│  - UI Steuerung                                                  │
│  - Power On/Off                                                  │
│  - Frequenz-Anzeige + Persistenz                                 │
│  - Debug Overlays (RDS + Layout Info)                            │
└─────────────────┬───────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────┐     ┌─────────────────────────────┐
│       RdsManager.java       │     │     TWUtilHelper.java       │
│  - RDS Polling (500ms)      │     │  - MCU Kommunikation        │
│  - PS/RT Abruf via JNI      │     │  - Audio-Routing            │
│  - RSSI Messung             │     │  ⚠️ NICHT VERFÜGBAR auf     │
│  - Cache Management         │     │    Unisoc UMS512!           │
└─────────────┬───────────────┘     └─────────────────────────────┘
              │
              ▼
┌─────────────────────────────┐
│       FmNative.java         │
│  - JNI Wrapper              │
│  - fmsyu_jni() Befehle      │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│     libfmjni.so             │
│  (System Native Library)    │
│  /system/lib64/libfmjni.so  │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│     sqlfmservice            │
│  (System FM Service)        │
│  - Steuert TDA7708          │
│  - RDS Caching auf HW-Level │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│      TDA7708 FM Tuner       │
│  (Externer IC via I2C/SPI)  │
└─────────────────────────────┘
```

---

## Komponenten

### 1. FmNative.java
**Pfad:** `com.android.fmradio.FmNative`

JNI-Wrapper für die System FM-Library. Muss im Package `com.android.fmradio` sein, da die native Library diese Klasse über JNI_OnLoad sucht.

**Wichtige Methoden:**
| Methode | Beschreibung |
|---------|--------------|
| `openDev()` | Öffnet FM-Device |
| `powerUp(freq)` | Schaltet FM-Chip ein |
| `tune(freq)` | Tuned zu Frequenz |
| `setRds(enable)` | Aktiviert/deaktiviert RDS |
| `readRds()` | Triggert RDS-Decoder |
| `getPs()` | Holt PS-Name (byte[]) |
| `getLrText()` | Holt Radio-Text (byte[]) |
| `getrssi()` | Liest Signalstärke |
| `fmsyu_jni(cmd, in, out)` | NavRadio-Stil JNI-Befehle |

**fmsyu_jni Command Codes (✓ = getestet & funktioniert):**
| Code | Hex | Beschreibung | Status |
|------|-----|--------------|--------|
| 11 | 0x0b | GETRSSI - Signalstärke | ✓ Funktioniert |
| 21 | 0x15 | RDSONOFF - RDS ein/aus | ✓ Funktioniert |
| 30 | 0x1e | RDSGETPS - Sendername | ✓ Funktioniert |
| 31 | 0x1f | RDSGETTEXT - Radio Text | ✓ Funktioniert |

**Hinweis:** Die klassischen `getPs()`, `getLrText()`, `getrssi()` Methoden liefern auf dieser Plattform **keine Daten**. Nur `fmsyu_jni()` funktioniert!

### 2. RdsManager.java
**Pfad:** `at.planqton.fytfm.RdsManager`

Verwaltet RDS-Daten und Polling.

**Ablauf:**
1. `enableRds()` - Aktiviert RDS auf Hardware
2. `startPolling(callback)` - Startet 500ms Polling
3. `pollRds()` - Ruft `fetchPs()` und `fetchRt()` auf
4. Callback mit PS, RT, RSSI

**PS-Abruf Hierarchie:**
1. `fmsyu_jni(0x1e)` → Bundle "PSname"
2. `getPs()` → byte[]
3. `getPsString()` → String

**RT-Abruf Hierarchie:**
1. `fmsyu_jni(0x1f)` → Bundle "Text" + "TextSize"
2. `getLrText()` → byte[]
3. `getRadioText()` → String

### 3. TWUtilHelper.java
**Pfad:** `at.planqton.fytfm.TWUtilHelper`

Wrapper für FYT-spezifische MCU-Kommunikation via `android.tw.john.TWUtil`.

**⚠️ NICHT VERFÜGBAR auf Unisoc UMS512!**

Auf Unisoc-Plattformen mit TDA7708 Tuner existiert `android.tw.john.TWUtil` nicht.
Die Audio-Kontrolle erfolgt stattdessen über `sqlfmservice`.

**Commands (nur für andere Plattformen):**
| Command | Hex | Beschreibung |
|---------|-----|--------------|
| CMD_RADIO_POWER | 0x101 | Radio Ein/Aus |
| CMD_RADIO_FREQ | 0x102 | Frequenz setzen |
| CMD_AUDIO_SOURCE | 0x110 | Audio-Routing |

**Wichtig:** `initRadioSequence()` muss VOR `powerOn()` aufgerufen werden!

### 4. FmService.java
**Pfad:** `com.android.fmradio.FmService`

Empfängt RDS-Callbacks von der nativen Library. Muss im Package `com.android.fmradio` sein.

**RDS Event Types (NavRadio-Stil):**
| Event | Beschreibung |
|-------|--------------|
| 2 | PTY (Program Type) |
| 10 | PS Name |
| 11 | Radio Text |
| 14 | PI Code |

---

## Power-On Sequenz

**KRITISCHE REIHENFOLGE:**

```
1. TWUtil.initRadioSequence()    ← MCU vorbereiten
2. TWUtil.radioOn()              ← Audio-Routing aktivieren
3. FmNative.openDev()            ← FM-Device öffnen
4. FmNative.powerUp(freq)        ← FM-Chip einschalten
5. FmNative.tune(freq)           ← Frequenz einstellen
6. FmNative.setRds(true)         ← RDS aktivieren
7. RdsManager.startPolling()     ← RDS-Daten abrufen
```

---

## ADB Debug-Befehle

Der `DebugReceiver` ermöglicht vollständige Radio-Steuerung über ADB.

### Basis-Syntax
```bash
adb shell am broadcast -p at.planqton.fytfm -a at.planqton.fytfm.DEBUG --es cmd "<command>" [--es freq "<freq>"]
```

**⚠️ WICHTIG: `-p at.planqton.fytfm` ist erforderlich!**

Ab Android 8+ werden Broadcasts an nicht laufende Apps im Hintergrund blockiert.
Der `-p` Flag (package) stellt sicher, dass der Broadcast explizit an die App gesendet wird.

Ohne `-p` erhält man: `Background execution not allowed`

### Verfügbare Befehle

#### Status abfragen
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "status"
```
Zeigt: Library loaded, TWUtil available, Radio on, RSSI, RDS Support

#### Radio einschalten
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "poweron"
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "poweron" --es freq "90.4"
```

#### Radio ausschalten
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "poweroff"
```

#### Frequenz wechseln
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "tune" --es freq "90.4"
```

#### RDS-Daten lesen
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "rds"
```
Zeigt alle PS/RT Abruf-Methoden mit Hex-Dump.

#### RDS aktivieren
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "rds_enable"
```

#### RSSI lesen
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "rssi"
```

#### TWUtil testen
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "twutil"
```

#### Kompletter Test
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "test_all"
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "test_all" --es freq "90.4"
```
Führt aus: Status → PowerOn → 3s warten → RDS lesen

### Log-Ausgabe beobachten
```bash
adb logcat -s fytFM_DEBUG:I
```

Oder mit allen relevanten Tags:
```bash
adb logcat -s fytFM_DEBUG:I fytFM:I RdsManager:D TWUtilHelper:I FmNative:I
```

---

## Troubleshooting

### Problem: Radio hört man nicht

**Diagnose:**
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "test_all" --es freq "90.4"
adb logcat -s fytFM_DEBUG:I
```

**Mögliche Ursachen:**

| Log-Ausgabe | Problem | Lösung |
|-------------|---------|--------|
| `Library loaded: false` | libfmjni.so fehlt | Firmware-Problem |
| `TWUtil available: false` | Kein FYT-Gerät | MCU-API nicht vorhanden |
| `openDev() = false` | FM-Device Fehler | Neustart oder Permissions |
| `powerUp() = false` | FM-Chip startet nicht | Hardware-Problem |
| `RSSI = 0` | Kein Signal | Antenne prüfen |

### Problem: RDS zeigt nur "-"

**Diagnose:**
```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "rds"
adb logcat -s fytFM_DEBUG:I
```

**Erwartete Ausgabe bei funktionierendem RDS:**
```
fmsyu_jni(0x1e) = 0
PSname bytes: 8
PSname hex: 4F 45 33 20 20 20 20 20
PSname string: 'OE3'
```

**Mögliche Ursachen:**

| Log-Ausgabe | Problem | Lösung |
|-------------|---------|--------|
| `fmsyu_jni(0x1e) = -1` | Befehl nicht unterstützt | Andere Methode probieren |
| `PSname bytes: 0` | RDS nicht aktiv | `rds_enable` ausführen |
| `PSname: null` | Kein RDS-Signal | Frequenz mit RDS wählen |
| `setRds(true) = -1` | RDS-Aktivierung fehlgeschlagen | Hardware-Problem |

### Problem: RSSI immer 0

```bash
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es cmd "rssi"
```

- `getrssi() = 0` → Methode nicht unterstützt
- `fmsyu_jni(0x0b) rssilevel = -1` → Alternative Methode nicht verfügbar
- `sql_getrssi() = 0` → Keine SQL-Methode

---

## Plattform-Kompatibilität

| Chipsatz | FM-Chip | TWUtil | RDS | Getestet |
|----------|---------|--------|-----|----------|
| **Unisoc UMS512** | **TDA7708** | **Nein** | **Ja** | **✓ Funktioniert** |
| SD662 (7862) | WCN3980 | Ja | Ja | - |
| SD665 (7870) | WCN3980 | Ja | Ja | - |
| SD680 (8581) | WCN6750 | Ja | Ja | - |

**Hinweis Unisoc UMS512:**
- TWUtil ist NICHT verfügbar
- RDS funktioniert NUR über `fmsyu_jni()` Befehle
- Klassische Methoden (`getPs()`, `getLrText()`) liefern keine Daten
- FM-Hardware wird über `sqlfmservice` gesteuert

---

## Dateistruktur

```
app/src/main/java/
├── com/android/fmradio/
│   ├── FmNative.java       # JNI Wrapper
│   └── FmService.java      # RDS Callbacks
└── at/planqton/fytfm/
    ├── MainActivity.kt     # UI + Debug Overlays
    ├── RdsManager.java     # RDS Polling + Cache
    ├── TWUtilHelper.java   # MCU Kommunikation (nicht auf UMS512)
    ├── DebugReceiver.java  # ADB Debug-Befehle
    ├── FrequencyScaleView.kt
    ├── data/
    │   ├── RadioStation.kt
    │   └── PresetRepository.kt
    ├── ui/
    │   ├── StationAdapter.kt
    │   └── StationEditorAdapter.kt
    └── scanner/
        └── RadioScanner.kt  # (deaktiviert)
```

---

## Layout-Varianten

Die App verwendet mehrere Layout-Varianten für verschiedene Bildschirmkonfigurationen:

```
app/src/main/res/
├── layout/                    # Standard-Layout (Portrait)
│   └── activity_main.xml
├── layout-land/               # Landscape-Modus ← WIRD AUF HEAD UNIT VERWENDET
│   └── activity_main.xml
├── layout-v26/                # Android 8.0+ (API 26)
│   └── activity_main.xml
└── layout-sw600dp/            # Tablets (≥600dp Breite)
    └── activity_main.xml
```

**⚠️ WICHTIG:** Auf FYT Head Units wird typischerweise `layout-land` verwendet!

Bei Änderungen am Layout müssen ALLE Varianten angepasst werden, da Android automatisch die passende auswählt.

### Layout-Info Debug Overlay

Die App zeigt im Debug-Modus an, welches Layout aktiv ist:
- Layout-Name (z.B. "layout-land")
- Bildschirmgröße (z.B. "1024x600")
- Display-Density (z.B. "1.5 (240dpi)")

---

## Debug Overlays

Die App hat zwei Debug-Overlays, aktivierbar über die Checkbox-Liste:

### 1. RDS Debug Overlay
Zeigt live RDS-Daten:
| Feld | Beschreibung | Beispiel |
|------|--------------|----------|
| PS | Program Service (Sendername) | `OE3` |
| PI | Program Identification Code | `A201` |
| PTY | Program Type | `10` (Pop Music) |
| RT | Radio Text | `Nickelback - How You Remind Me` |
| RSSI | Signalstärke | `-45 dBm` |
| Freq | Aktuelle Frequenz | `98.10 MHz` |

### 2. Layout Info Overlay
Zeigt Layoutinformationen:
| Feld | Beschreibung |
|------|--------------|
| Layout | Aktives Layout (layout, layout-land, etc.) |
| Screen | Bildschirmauflösung in Pixel |
| Density | Display-Density + DPI |

---

## Frequenz-Persistenz

Die zuletzt eingestellte Frequenz wird automatisch gespeichert und beim nächsten Start wiederhergestellt.

**SharedPreferences:**
- Datei: `fytfm_prefs`
- Key: `last_frequency`
- Default: `90.4` MHz

```kotlin
// Speichern
val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
prefs.edit().putFloat("last_frequency", frequency).apply()

// Laden
val lastFreq = prefs.getFloat("last_frequency", 90.4f)
```

---

## Quick Reference

### Radio testen (One-Liner)
```bash
adb shell am broadcast -p at.planqton.fytfm -a at.planqton.fytfm.DEBUG --es cmd "test_all" --es freq "90.4" && adb logcat -s fytFM_DEBUG:I -d | tail -50
```

### Nur RDS checken
```bash
adb shell am broadcast -p at.planqton.fytfm -a at.planqton.fytfm.DEBUG --es cmd "rds" && adb logcat -s fytFM_DEBUG:I -d | tail -30
```

### Live-Log während Test
```bash
# Terminal 1:
adb logcat -s fytFM_DEBUG:I

# Terminal 2:
adb shell am broadcast -p at.planqton.fytfm -a at.planqton.fytfm.DEBUG --es cmd "test_all"
```

---

## RDS Caching-Verhalten

RDS-Daten werden auf mehreren Ebenen gecacht:

| Ebene | Cache | Wird gelöscht bei |
|-------|-------|-------------------|
| **sqlfmserver** | HW-Level Cache | Tuning (automatisch) |
| **RdsManager** | `currentPs`, `currentRt` | `tune()` / `clearRds()` |
| **UI** | Display-Strings | Frequenzwechsel |

**Hinweis:** Nach Frequenzwechsel kann es kurz dauern, bis neue RDS-Daten erscheinen.
Der sqlfmserver cached RDS-Daten auf Hardware-Ebene - das ist normales Verhalten.

---

## Bekannte Einschränkungen

| Problem | Ursache | Status |
|---------|---------|--------|
| TWUtil nicht verfügbar | Unisoc UMS512 hat keine TWUtil API | Workaround: sqlfmservice |
| Klassische RDS-Methoden leer | Firmware-Implementierung | Workaround: fmsyu_jni() |
| RDS erscheint verzögert | Normale RDS-Übertragung (~2-4s) | Normal |
| Audio-Routing | Nicht implementiert ohne TWUtil | ToDo |
