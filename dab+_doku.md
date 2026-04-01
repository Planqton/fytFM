# DAB+ Integration - fytFM

## Überblick

fytFM unterstützt DAB+ (Digital Audio Broadcasting) über USB-DAB-Dongles (XTRONS, Joying, Pumpkin) mit VID `0x16C0` / PID `0x05DC`. Die Implementierung nutzt die OMRI Radio API mit der nativen `libirtdab.so` Bibliothek aus dem DAB-Z Projekt.

---

## Architektur

```
┌─────────────────────────────────────────────┐
│  MainActivity                               │
│  ┌──────────────┐  ┌─────────────────────┐  │
│  │ Spinner       │  │ Power Button        │  │
│  │ FM/AM/DAB+    │  │ toggleDabPower()    │  │
│  └──────┬───────┘  └─────────┬───────────┘  │
│         │                    │               │
│  ┌──────▼────────────────────▼────────────┐  │
│  │        DabTunerManager                 │  │
│  │  initialize() / startScan() / tune()   │  │
│  └──────────────────┬─────────────────────┘  │
│                     │                        │
├─────────────────────┼────────────────────────┤
│  OMRI Radio API     │                        │
│  ┌──────────────────▼─────────────────────┐  │
│  │  Radio (Singleton)                     │  │
│  │  └─ UsbHelper (JNI Bridge)            │  │
│  │     └─ TunerUsbImpl (Callbacks)       │  │
│  │        └─ RadioServiceDabImpl (Data)   │  │
│  └────────────────────────────────────────┘  │
│                     │                        │
├─────────────────────┼────────────────────────┤
│  Native (C++)       │                        │
│  ┌──────────────────▼─────────────────────┐  │
│  │  libirtdab.so  +  libfec.so            │  │
│  │  USB-Kommunikation + DAB-Demodulation  │  │
│  └────────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

---

## Dateien

### App-Code (at.planqton.fytfm)

| Datei | Beschreibung |
|-------|-------------|
| `dab/DabTunerManager.kt` | Zentrale Steuerung: Init, Scan, Tune, Power |
| `dab/DabStation.kt` | Datenklasse für DAB-Sender (serviceId, ensembleId, Label) |
| `dab/DabScanListener.kt` | Interface für Scan-Callbacks |
| `ui/DabScanDialog.kt` | Dialog mit Fortschrittsbalken + Senderliste |
| `data/RadioStation.kt` | Erweitert um `isDab`, `serviceId`, `ensembleId`, `ensembleLabel` |
| `data/PresetRepository.kt` | DAB-Speicherung in eigenen SharedPreferences (`dab_presets`) |
| `FrequencyScaleView.kt` | `RadioMode.DAB` hinzugefügt |
| `ui/StationCarouselAdapter.kt` | DAB-Anzeige: "DAB+" Label, Selection per serviceId |
| `MainActivity.kt` | Spinner, Power, Scan, Navigation, View-Mode-Restrictions |

### OMRI Radio API (org.omri)

| Paket | Dateien | Zweck |
|-------|---------|-------|
| `org.omri.radio` | Radio.java, RadioStatusListener.java, RadioErrorCode.java, RadioStatus.java | API-Einstieg, USB-Erkennung |
| `org.omri.radio.impl` | UsbHelper.java, TunerUsbImpl.java, RadioServiceDabImpl.java, ... (17 Dateien) | JNI-Bridge, Native Callbacks |
| `org.omri.tuner` | Tuner.java, TunerListener.java, TunerStatus.java, TunerType.java, ReceptionQuality.java | Tuner-Interfaces |
| `org.omri.radioservice` | RadioService.java, RadioServiceDab.java, RadioServiceType.java, ... (12 Dateien) | Service-Datenmodell |
| `org.omri.radioservice.metadata` | Textual, Visual, TermId, ... (13 Dateien) | Metadaten (DLS, Slideshow) |

### Native Libraries (jniLibs/arm64-v8a)

| Library | Größe | Funktion |
|---------|-------|----------|
| `libirtdab.so` | 1.5 MB | DAB-Demodulation + USB-Kommunikation |
| `libfec.so` | 8 KB | Forward Error Correction |
| `libmpg123.so` | 228 KB | MPEG Audio Decoding (DAB) |
| `libmpg123plug.so` | 15 KB | mpg123 Plugin |
| `libc++_shared.so` | 1.3 MB | C++ Runtime |

### Ressourcen

| Datei | Beschreibung |
|-------|-------------|
| `res/layout/dialog_dab_scan.xml` | Scan-Dialog Layout |
| `res/layout/item_radio_mode_spinner.xml` | Spinner-Item Darstellung |
| `res/layout/item_radio_mode_dropdown.xml` | Dropdown-Item Darstellung |
| `res/xml/usb_dab_device_filter.xml` | USB-Gerätefilter (VID/PID) |
| `res/values/strings.xml` | `radio_modes` Array: FM, AM, DAB+ |

---

## Datenmodell

### RadioStation (erweitert für DAB)

```kotlin
data class RadioStation(
    val frequency: Float,          // DAB: Ensemble-Frequenz in MHz
    val name: String?,             // DAB: Service Label
    val rssi: Int = 0,
    val isAM: Boolean = false,
    val isDab: Boolean = false,    // NEU
    val isFavorite: Boolean = false,
    val syncName: Boolean = true,
    val serviceId: Int = 0,        // NEU: DAB Service ID
    val ensembleId: Int = 0,       // NEU: DAB Ensemble ID
    val ensembleLabel: String?     // NEU: DAB Ensemble Name
)
```

**Wichtig:** DAB-Sender werden per `serviceId` identifiziert, NICHT per Frequenz.

### DabStation

```kotlin
data class DabStation(
    val serviceId: Int,
    val ensembleId: Int,
    val serviceLabel: String,      // z.B. "Ö3"
    val ensembleLabel: String,     // z.B. "ORF DAB+"
    val ensembleFrequencyKHz: Int  // z.B. 222064 (= 222.064 MHz, Block 11D)
)
```

Konvertierung: `DabStation.toRadioStation()` erzeugt eine `RadioStation` mit `isDab=true`.

### Speicherung (PresetRepository)

DAB-Sender werden in eigenen SharedPreferences (`dab_presets`) als JSON gespeichert:

```json
[
  {
    "frequency": 222.064,
    "name": "Ö3",
    "isDab": true,
    "serviceId": 12345,
    "ensembleId": 67890,
    "ensembleLabel": "ORF DAB+",
    "isFavorite": true
  }
]
```

**DAB-spezifische Methoden:**
- `saveDabStations()` / `loadDabStations()` / `clearDabStations()`
- `toggleDabFavorite(serviceId)` / `isDabFavorite(serviceId)`
- `mergeDabScannedStations()` - Merge mit Favoriten-Schutz (Key = serviceId)
- `isShowFavoritesOnlyDab()` / `setShowFavoritesOnlyDab()`

---

## Ablauf: DAB einschalten

```
1. User wählt "DAB+" im Spinner
   → setRadioMode(RadioMode.DAB)
   → Erzwingt Carousel-Ansicht (Seekbar nicht verfügbar)
   → Versteckt View-Mode-Toggle

2. User drückt Power-Button
   → toggleRadioPower() erkennt DAB-Modus
   → toggleDabPower()
   → dabTunerManager.initialize(context)
     → Radio.getInstance().initialize()
       → UsbHelper.create() lädt libirtdab.so
       → Sucht USB-Geräte (VID 0x16C0 / PID 0x05DC)
       → Erstellt TunerUsbImpl für gefundenes Gerät
       → Callback: tunerAttached(tuner)
     → DabTunerManager.tunerAttached()
       → tuner.subscribe(this)
       → tuner.initializeTuner()
         → UsbHelper.attachDevice() (native JNI)
         → Native: Geräteverbindung aufbauen
       → Callback: TUNER_READY
       → Callback: onTunerReady
   → isDabOn = true

3. User drückt Sendersuche
   → showDabScanDialog()
   → DabScanDialog erstellt
   → dabTunerManager.startScan(listener)
     → tuner.startRadioServiceScan()
       → UsbHelper.startEnsembleScan(deviceName)
         → Native: Scannt Band III (174-240 MHz, ~38 Blöcke)
       → Callbacks: scanProgressCallback(progress, total)
       → Callbacks: serviceFound(RadioServiceDab)
         → DabTunerManager erstellt DabStation
         → Dialog zeigt Sender in Liste
       → Callback: SERVICELIST_READY → tunerScanFinished
     → Dialog: mergeDabScannedStations() → speichert Sender
     → populateCarousel() mit DAB-Sendern

4. User klickt Sender im Carousel
   → dabTunerManager.tuneService(serviceId, ensembleId)
     → tuner.startRadioService(RadioServiceDab)
       → UsbHelper.startService(deviceName, service)
         → Native: Tune + Audio-Dekodierung
     → Callback: radioServiceStarted
     → currentDabServiceId/ensembleId aktualisiert
```

---

## Ablauf: Senderwechsel (Skip)

```
User drückt Prev/Next im DAB-Modus
  → doSkipToPrevious() / doSkipToNext()
  → Erkennt DAB-Modus → skipDabStation(forward)
  → Lädt DAB-Senderliste aus PresetRepository
  → Berechnet nächsten/vorherigen Index (zyklisch)
  → dabTunerManager.tuneService(serviceId, ensembleId)
  → updateCarouselSelection()
```

---

## UI-Besonderheiten im DAB-Modus

| Feature | FM/AM | DAB+ |
|---------|-------|------|
| Frequenz-Anzeige | "FM 98.40" / "AM 522" | Service-Label z.B. "Ö3" |
| Seekbar (FrequencyScaleView) | Verfügbar | **Nicht verfügbar** |
| Carousel | Optional | **Erzwungen** |
| View-Mode-Toggle | Sichtbar | **Versteckt** |
| Power-Button | FYT FM-Chip | USB DAB-Dongle |
| Sendersuche | RadioScanner (FM/AM) | DabScanDialog (Ensemble-Scan) |
| Favoriten-Matching | Per Frequenz (±0.05) | Per serviceId |
| Senderwechsel | Frequenzschritt | Listenwechsel |
| Band-Label (Carousel) | "FM" / "AM" | "DAB+" |

---

## USB-Konfiguration

### AndroidManifest.xml

```xml
<!-- USB Host Feature (optional) -->
<uses-feature android:name="android.hardware.usb.host" android:required="false" />

<!-- Auto-Launch bei DAB-Dongle-Anschluss -->
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/usb_dab_device_filter" />
```

### usb_dab_device_filter.xml

```xml
<resources>
    <usb-device vendor-id="5824" product-id="1500" />
    <!-- 5824 = 0x16C0, 1500 = 0x05DC -->
</resources>
```

### Unterstützte Geräte

| Hersteller | Modell | VID | PID |
|------------|--------|-----|-----|
| XTRONS | USBDAB01 | 0x16C0 | 0x05DC |
| Joying | DAB+ Dongle | 0x16C0 | 0x05DC |
| Pumpkin | Y0251 | 0x16C0 | 0x05DC |

---

## OMRI Radio API - Wichtige Klassen

### Radio (Singleton)

```java
Radio.getInstance()
  .initialize(context, opts)     // Initialisiert API + USB-Monitoring
  .getAvailableTuners(type)      // Gibt Tuner-Liste zurück
  .registerRadioStatusListener() // USB-Attach/Detach Callbacks
  .deInitialize()                // Ressourcen freigeben
```

### UsbHelper (JNI Bridge)

```java
UsbHelper.create(context, callback, verbose, rawPath)  // Singleton erstellen
UsbHelper.getInstance()
  .attachDevice(TunerUsb)        // native: Gerät initialisieren
  .startEnsembleScan(deviceName) // native: DAB-Scan starten
  .stopEnsembleScan(deviceName)  // native: Scan stoppen
  .startService(deviceName, svc) // native: Sender abspielen
  .stopService(deviceName)       // native: Wiedergabe stoppen
  .scanForSpecificDevices(list)  // USB-Geräte suchen
  .requestPermission(device)     // USB-Berechtigung anfordern
```

### TunerUsbImpl (Native Callbacks)

Von der nativen Library aufgerufene Methoden:

```java
callBack(int type)                    // TUNER_READY, TUNER_FAILED, SCAN_IN_PROGRESS, etc.
scanProgressCallback(int prog, int total)  // Scan-Fortschritt
serviceFound(RadioServiceDab service)      // Sender gefunden
serviceStarted(RadioServiceDab service)    // Wiedergabe gestartet
serviceStopped(RadioServiceDab service)    // Wiedergabe gestoppt
receptionStatistics(sync, quality, snr)    // Empfangsqualität
```

### TunerUsbCallbackTypes

| Typ | Wert | Bedeutung |
|-----|------|-----------|
| TUNER_READY | 0 | Tuner bereit / Scan beendet |
| TUNER_FAILED | 1 | Initialisierung fehlgeschlagen |
| TUNER_SCAN_IN_PROGRESS | 4 | Scan läuft |
| SERVICELIST_READY | 5 | Senderliste verfügbar |
| VISUALLIST_READY | 6 | Logos/Visuals verfügbar |

### RadioServiceDabImpl

Von der nativen Library erstellt. Wichtige Felder (per JNI gesetzt):

```java
int mServiceId          // Eindeutige Service-ID
int mEnsembleId         // Ensemble-ID
int mEnsembleFrequency  // Frequenz in kHz
String mServiceLabel    // Sendername
String mEnsembleLabel   // Ensemble-Name
boolean mIsProgrammeService  // true = Audio, false = Daten
```

---

## DAB-Scan: Technische Details

- **Band III:** 174.928 - 239.200 MHz
- **~38 Blöcke** (5A bis 13F), jeweils 1.536 MHz breit
- **Pro Block:** 4-16 Sekunden (OFDM-Sync + FIC-Dekodierung)
- **Gesamtdauer:** ca. 2.5 - 10 Minuten
- **Pro Block werden gefunden:** 0-N Services (Sender)
- **Ein Ensemble** (Block) enthält typisch 6-18 DAB+ Services

---

## Bekannte Einschränkungen

1. **Audio-Routing:** DAB-Audio wird von der nativen Library als PCM ausgegeben. Die Integration in Androids Audio-System (AudioTrack) muss noch verifiziert werden.
2. **Kein DAB-Placeholder-Icon:** DAB-Sender im Carousel nutzen aktuell das FM-Placeholder-Icon.
3. **Kein RDS-Äquivalent:** DAB DLS (Dynamic Label Segment) Metadaten werden von den OMRI-Stubs empfangen aber noch nicht in der Now-Playing-Bar angezeigt.
4. **Nur arm64-v8a:** Native Libraries nur für 64-bit ARM. Kein armeabi-v7a Support.
