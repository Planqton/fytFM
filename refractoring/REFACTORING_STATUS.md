# FytFM Refactoring Status - 22.04.2026

## Aktueller Stand

**MainActivity:** 7,883 Zeilen (von ursprünglich 10,761 = **27% Reduktion**)
**Funktionen:** 337 + setupDialogListeners()
**Properties:** 156
**findViewById:** 45 verbleibend (von 258 = **83% migriert**)
  - 21 in Dialogen (korrekt für dialogView.findViewById)
  - 13 Archive-Include-Layout
  - 11 PiP-Include-Layout
**AlertDialog.Builder:** 7 verbleibend (von 15 = **53% zu DialogFragments migriert**)
**fmNative:** 0 verbleibend (100% zu RadioController migriert)
**Log-Tags:** TAG-Konstante in allen Dateien
**Debug-Funktionen:** Extrahiert zu DebugManager
**Toast-Aufrufe:** 72 von 74 konvertiert (97%)

## Erledigte Optimierungen

### 1. Helper-Properties (Zeilen 75-92)
```kotlin
private val currentMode: FrequencyScaleView.RadioMode
    get() = binding.frequencyScale.getMode()
private val isAmMode/isFmMode/isDabMode/isDabDevMode/isAnyDabMode
private val dabStationsForCurrentMode
```

### 2. fmNative zu RadioController migriert
- `closeApp()`, `setRadioMode()`, `setRadioMuteState()`, `toggleRadioPower()`
- Seek-Operationen mit radioController.tuneRaw()

### 3. startRdsPolling refactored
Von 197 auf 29 Zeilen durch 5 Handler-Methoden

### 4. ViewBinding Migration (83% erledigt)
**Migrierte Views:**
- Haupt-UI: tvFrequency, frequencyScale, controlBar, stationBar, stationRecycler, tvAllList
- Buttons: btnPrevStation, btnNextStation, btnFavorite, btnPlayPause, btnPower, spinnerRadioMode
- Debug Overlays: 11 Overlays (RDS, Layout, Build, Deezer, Buttons, SWC, Carousel, Station, Tuner, Parser)
- Debug TextViews: 24 TextViews
- Debug CheckBoxes: 11 CheckBoxes
- Debug Labels: 6 Labels
- Now Playing Views: nowPlayingBar, nowPlayingCover, nowPlayingPs, nowPlayingArtist, nowPlayingTitle, nowPlayingRawRt, nowPlayingIgnoredIndicator, nowPlayingDeezerWatermark, nowPlayingCorrectionButtons
- Carousel Views: carouselNowPlayingBar, carouselNowPlayingCover, carouselNowPlayingPs, carouselNowPlayingArtist, carouselNowPlayingTitle, carouselNowPlayingRawRt, carouselIgnoredIndicator, carouselDeezerWatermark, carouselCorrectionButtons
- Correction Buttons: btnCorrectionRefresh, btnCorrectionTrash, btnCarouselCorrectionRefresh, btnCarouselCorrectionTrash, btnDeezerToggle, btnCarouselDeezerToggle
- Content Areas: mainContentArea, carouselContentArea, dabListContentArea
- View Mode: btnViewModeEqualizer, btnViewModeImage, stationCarousel, carouselFrequencyLabel
- DAB List: alle dabList* Views (15 Views)
- Sonstige: updateBadge, btnFolder, debugDeezerCoverImage, nowPlayingCoverDots, carouselCoverDots

**Verbleibend (45 findViewById):**
- 21 dialogView.findViewById (korrekt für Dialog-Layouts)
- 11 PiP-Include-Layout (pipLayout, pipCoverImage, pipTitle, etc.)
- 13 Archive-Include-Layout (archiveOverlay, archiveRecycler, etc.)

### 5. Vereinfachte Funktionen
- `updateFrequencyDisplay()`, `updateCarouselSelection()`, `populateCarousel()`
- `updateFavoriteButton()`, `saveLastFrequency/loadLastFrequency`
- `loadStationsForCurrentMode()`, `skipToPreviousStation/doSkipToNext`
- `updatePowerButton()`, `toggleCurrentStationFavorite()`

### 6. Große Funktionen aufgeteilt

**setRadioMode() - von 160 auf 35 Zeilen:**
- `cleanupOldRadioMode()` - Alten Modus aufräumen
- `initializeNewRadioMode()` - Neuen Modus initialisieren
- `restoreViewModeForRadioMode()` - View-Einstellung wiederherstellen
- `updateUiAfterModeChange()` - UI aktualisieren

**onCreate() - von 228 auf 91 Zeilen:**
- `initRepositories()` - Alle Repositories initialisieren
- `initRadioController()` - RadioController Setup
- `initExternalServices()` - Deezer, TWUtil, Overlay Service
- `setupDabModeOnStartup()` - DAB-spezifische Initialisierung
- `updateInitialMediaSession()` - MediaSession Update

**saveCurrentCoverAsStationLogo() - von 172 auf 35 Zeilen:**
- `animateCoverSaveGesture()` - Cover-Animation beim Speichern
- `getSelectedCoverSource()` - Aktuelle Cover-Quelle ermitteln
- `saveCoverSourceToFile()` - Cover zu Datei speichern
- `updateLogoInRepository()` - Template-Repository aktualisieren
- `refreshUiAfterLogoSave()` - UI nach Speichern aktualisieren

**updateDeezerDebugInfo() - von 139 auf 55 Zeilen:**
- Duplizierte Cover-Reset-Logik zu `resetCoverToFallback()` extrahiert
- Debug-Updates an DebugManager delegiert

### 7. Code-Qualität Verbesserungen

**TAG-Konstanten:**
```kotlin
companion object {
    private const val TAG = "fytFM"
}
```
- MainActivity: 202 Log-Aufrufe → TAG (100%)
- StationChangeOverlayService: 9 Log-Aufrufe → TAG
- LogoTemplateDialogFragment: 1 Log-Aufruf → TAG (nutzt eigenen TAG)

**toast() Helper-Funktionen:**
```kotlin
private fun toast(message: String, long: Boolean = false)
private fun toast(@StringRes resId: Int, long: Boolean = false)
```
- 72 von 74 Toast-Aufrufen konvertiert (97%)
- 2 Sonderfälle: toast() Helper selbst, autoBackgroundToast (braucht cancel())

### 8. DebugManager Extraktion

**Neue Klasse:** `at.planqton.fytfm.debug.DebugManager` (382 Zeilen)
- Verwaltet Debug-Overlay UI-Aktualisierungen
- 17 Funktionen aus MainActivity extrahiert:
  - `updateBuildDebugInfo()` - Build-Informationen
  - `updateTunerDebugInfo()` - Tuner-Verfügbarkeit
  - `resetDabDebugInfo()` - DAB-Debug zurücksetzen
  - `updateDebugInfo()` - RDS Debug-Informationen
  - `updateDabDebugInfo()` - DAB Debug-Informationen
  - `updateDabReceptionStats()` - DAB Empfangsstatistiken
  - `resetDebugToRds()` - Debug zurück auf RDS
  - `updateDlsTimestampLabel()` - DLS Timestamp Label
  - `updateLayoutDebugInfo()` - Layout Debug-Informationen
  - `frequencyToChannelBlock()` - DAB Frequenz zu Block-Name
  - `clearDeezerDebugFields()` - Deezer Debug-Felder zurücksetzen
  - `formatDuration()` - Dauer-Formatierung
  - `updateCarouselDebugInfo()` - Carousel Debug-Informationen
  - `updateDeezerStatusAndInput()` - Deezer Status und RT Input
  - `updateDeezerSource()` - Deezer Source Anzeige
  - `updateDeezerNotFound()` - Deezer Not Found Status
  - `updateDeezerTrackInfo()` - Deezer Track-Informationen

### 9. DialogFragment Migration (NEU - 22.04.2026)

**Neue DialogFragment-Klassen:** `at.planqton.fytfm.ui.dialogs/`
```
├── ConfirmationDialogFragment.kt    - Generische Bestätigungs-Dialoge
├── SingleChoiceDialogFragment.kt    - Auswahl-Listen mit Callback
├── CrashReportDialogFragment.kt     - Crash-Benachrichtigung (3 Aktionen)
├── InputDialogFragment.kt           - Text-Eingabe mit Callback
├── ImportStationsDialogFragment.kt  - Sender-Import Bestätigung
└── TextPreviewDialogFragment.kt     - Bug-Report/Crash-Log Vorschau
```

**Konvertierte Dialoge (8 von 15):**
- ✅ Clear Archive Confirmation → ConfirmationDialogFragment
- ✅ Language Selection → SingleChoiceDialogFragment
- ✅ Now Playing Animation Selection → SingleChoiceDialogFragment
- ✅ RDS Retention Selection → SingleChoiceDialogFragment
- ✅ Import Stations → ImportStationsDialogFragment
- ✅ Crash Report Action → CrashReportDialogFragment
- ✅ Crash Bug Report Input → InputDialogFragment
- ✅ Bug Report Preview → TextPreviewDialogFragment

**Verbleibende komplexe Dialoge (7):**
- Radio Area Dialog (custom layout mit Checkmarks/Chevrons)
- Area Template Selection (custom Logik mit Download)
- Download Progress Dialog (Fortschrittsanzeige)
- Logo Download Error Dialog
- Manual Logo Search Dialog (Image Grid)
- Logo Save Dialogs (2x mit Progress)

**Neue Funktion:** `setupDialogListeners()` in MainActivity
- FragmentResultListener für alle DialogFragments
- Callbacks für Settings-Dialoge (Language, Animation, Retention)

## Nächste Schritte (Optional)

### Niedrige Priorität
1. **Verbleibende komplexe Dialoge:**
   - Radio Area Dialog zu DialogFragment
   - Logo Search Dialog zu DialogFragment

2. **Include-Layout ViewBinding:**
   - PiP-Layout separat binden
   - Archive-Layout separat binden

### Größte verbleibende Funktionen (Referenz)
- `setViewMode()` - 47 Zeilen
- `searchImagesWithDuckDuckGo()` - 47 Zeilen
- `setPipLayout()` - 46 Zeilen
- `handleDabServiceStartedFull()` - 46 Zeilen
- `handleDabDeezerResult()` - 46 Zeilen

*Alle großen Funktionen wurden erfolgreich aufgeteilt!*

## Statistiken

| Metrik | Vorher | Nachher | Änderung |
|--------|--------|---------|----------|
| MainActivity Zeilen | 10,761 | 7,883 | -27% |
| MainActivity Funktionen | ~380 | 337 | -11% |
| DebugManager Zeilen | 0 | 382 | neu |
| DebugManager Funktionen | 0 | 17 | extrahiert |
| DialogFragment Klassen | 7 | 13 | +6 neu |
| AlertDialog.Builder | 15 | 7 | -53% |
| saveCurrentCoverAsStationLogo | 172 | 35 | -80% |
| updateDeezerDebugInfo | 139 | 55 | -60% |
| findViewById Aufrufe | 258 | 45 | -83% |
| Toast-Aufrufe konvertiert | 0 | 72/74 | 97% |
| fmNative Aufrufe | viele | 0 | -100% |
| setRadioMode() Zeilen | 160 | 35 | -78% |
| onCreate() Zeilen | 228 | 91 | -60% |

## Build-Befehl
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew assembleDebug
```

## Session-Historie

### 22.04.2026
- Toast-Konvertierung abgeschlossen (72/74 = 97%)
- 30 weitere Views zu ViewBinding migriert
- 6 neue DialogFragment-Klassen erstellt
- 8 Dialoge zu DialogFragments konvertiert
- setupDialogListeners() hinzugefügt

### 21.04.2026
- DebugManager mit 17 Funktionen extrahiert
- saveCurrentCoverAsStationLogo refactored (-80%)
- updateDeezerDebugInfo refactored (-60%)
- Toast-Helper eingeführt
- TAG-Konstanten vereinheitlicht
