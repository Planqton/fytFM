# fytFM — Umstrukturierung & Refaktorierung (Stand 2026-04-22)

> Frischer Review nach Status `refractoring/REFACTORING_STATUS.md`. Ergänzt vorhandene Dokumente, ersetzt sie nicht.
> Alle Pfade relativ zu `/home/planqton/Schreibtisch/fytFM/`.

## Schon erledigt (Stand 2026-04-22)

Damit klar ist, wo wir stehen — **vor** dieser Bestandsaufnahme war bereits Vieles refactored. Quelle: `refractoring/REFACTORING_STATUS.md`.

### Vor dem 22.04.2026 (Vorgeschichte)
- ✅ **MainActivity geschrumpft**: 10.761 → 7.865 Zeilen (–27 %)
- ✅ **Controller-Schicht extrahiert**: `RadioController`, `DabController`, `FmAmController` (`controller/*`)
- ✅ **`DebugManager` extrahiert** (382 Z., 17 Funktionen aus MainActivity raus)
- ✅ **ViewBinding-Migration zu 83 %**: `findViewById` 258 → 45 (Rest = PiP- und Archive-Include-Layouts + Dialog-Views)
- ✅ **DialogFragment-Migration begonnen**: 8 von 15 Dialogen konvertiert (Confirmation, SingleChoice, CrashReport, Input, ImportStations, TextPreview)
- ✅ **`fmNative` komplett zu `RadioController` migriert** (0 verbleibende direkte Aufrufe)
- ✅ **Toast-Helper**: 72 von 74 Toast-Aufrufen über `toast()`-Helper (97 %)
- ✅ **TAG-Konstanten** in allen Dateien (`companion object { private const val TAG = "fytFM" }`)
- ✅ **Große Funktionen aufgeteilt**:
  - `setRadioMode()`: 160 → 35 Z. (`cleanupOldRadioMode`, `initializeNewRadioMode`, `restoreViewModeForRadioMode`, `updateUiAfterModeChange`)
  - `onCreate()`: 228 → 91 Z. (`initRepositories`, `initRadioController`, `initExternalServices`, `setupDabModeOnStartup`, `updateInitialMediaSession`)
  - `saveCurrentCoverAsStationLogo()`: 172 → 35 Z.
  - `updateDeezerDebugInfo()`: 139 → 55 Z.
  - `startRdsPolling`: 197 → 29 Z.
- ✅ **Dark-Mode** implementiert (System/Hell/Dunkel)
- ✅ **Hard-coded Strings** in `strings.xml` (EN + DE)
- ✅ **DeezerClient mit `sealed class`-Errors + Retry-Logik**
- ✅ **Unit-Tests**: `PresetRepositoryTest` (29 Tests), `RtCombinerTest` (10 Tests)

### Diese Session (begonnen 22.04.2026)
- 🟡 **RadioController Multi-Listener-Schicht**: `RadioEvent` sealed class + `MutableSharedFlow<RadioEvent>` als `events: SharedFlow<RadioEvent>` exponiert. Bestehende 17 Lambda-Callbacks bleiben funktional; jeder Aufruf emittiert zusätzlich in den Flow. Damit können ViewModel **und** MainActivity parallel mithören, ohne sich gegenseitig zu überschreiben. (Datei: `controller/RadioEvent.kt` neu, `controller/RadioController.kt` modifiziert.) **Build noch nicht verifiziert** wegen offener `plugin-api`-Frage.
- ⏳ **Nächste Schritte (offen)**: RadioViewModel auf Flow-Collection umstellen · ViewModelFactory + Instanzierung in MainActivity · Build & Install + Smoke-Test.

---

## 0. TL;DR

- **MainActivity.kt** ist trotz vorigem Refactoring noch 7.865 Zeilen mit 117 mutable Feldern und 17 Handler/Runnable-Paaren — die nächste Stufe muss Zustand wegtragen, nicht nur Funktionen aufteilen.
- **`RadioViewModel` und `SettingsViewModel` sind toter Code** (null Aufrufe in MainActivity, aber komplette StateFlow-Architektur + Tests vorhanden). Sie zu *aktivieren* ist das größte Hebel-Verhältnis im Projekt.
- **`PresetRepository.kt` (919 Zeilen)** ist eine Gott-Klasse: Stations-CRUD + 50+ Settings + Plugin-Configs + UI-State (Carousel-Modus). Threadsicherheit unklar, Merge-Logik 3-fach dupliziert.
- **5 konkrete Bugs**, davon einer ein Hard-Crash beim Importieren des Deezer-Cache-ZIPs.
- **3 Hardcodes** auf FYT-Headunit (`com.syu.music`, `com.android.fmradio.FmService`, Action-Strings) blockieren Portierung.
- Test-Abdeckung dünn: nur `PresetRepository` (29 Tests) und `RtCombiner` (10 Tests) substanziell; `DeezerCache`, `DeezerClient`, `DlsParser`, `ClassicalMusicNormalizer`, `RadioLogoRepository`, `LogoSearchService`, `UpdateRepository`, `RadioScanner` haben **null** Tests.

---

## 1. Konkrete Bugs (priorisiert)

### 1.1 ⛔ HARD-CRASH: `DeezerCache.importFromZip` schließt DB ohne Reopen
**Datei:** `app/src/main/java/at/planqton/fytfm/deezer/DeezerCache.kt` (~Zeile 290–373)
Beim Import wird `db.close()` (~Z.334) aufgerufen, um vor dem Überschreiben der `.db`-Datei zu flushen. Anschließend wird die DB-Datei ersetzt, aber der `SQLiteOpenHelper` öffnet sie nicht neu. Jeder folgende Aufruf (`searchLocal`, `cacheTrack`) läuft auf einem geschlossenen Handle und wirft `IllegalStateException: database not open`.

**Fix-Skizze:** nach `replace`-Operation `writableDatabase` neu anfordern oder den `SQLiteOpenHelper` verwerfen und neu instantiieren. Entscheidung: zweiteres ist robuster, weil eine evtl. Schema-Migration sauber durchläuft.

### 1.2 🔥 LEAK: `MockDabTunerManager` Timer wird nie gecancelt
**Datei:** `app/src/main/java/at/planqton/fytfm/dab/MockDabTunerManager.kt` (~Z.270)
Anonymer `Timer().scheduleAtFixedRate(...)` ohne gespeicherte Referenz. `stopMetadataSimulation` / `stopSlideshowSimulation` cancelt seine eigenen Timer, aber dieser hier läuft bis zum Prozessende.

**Fix-Skizze:** Timer in Feld speichern, in `deinitialize()` `cancel()` aufrufen.

### 1.3 ⚠️ STATE-DUALITÄT: `currentMode` lebt doppelt in `RadioController` & `FmAmController`
**Dateien:** `controller/RadioController.kt:35` und `controller/FmAmController.kt:46`
`RadioController.setMode()` ändert nur seinen eigenen `currentMode`. `FmAmController.loadLastMode()` (Z.545–551) liest aus SharedPrefs. Nach App-Restart in AM kann `RadioController` auf FM stehen, während `FmAmController` AM denkt → falsche Frequenzgrenzen, falsche RDS-Erwartungen.

**Fix-Skizze:** nur eine Quelle für `currentMode` — entweder im RadioController halten und FmAmController via Methodenparameter füttern, oder umgekehrt. Persistenz **eindeutig** einer Schicht zuordnen.

### 1.4 ⚠️ HARDCODE: FYT-headunit Klassennamen blockieren Portierung
**Datei:** `controller/FmAmController.kt:170, 178`
- `Intent` mit `className("com.syu.music", …)`
- Action `"com.action.ACTION_OPEN_RADIO"`
- `"com.android.fmradio.FmService"`

Auf Nicht-FYT-Geräten (oder bei FYT-Firmware-Update mit anderen Paketnamen) bricht das stillschweigend, weil `try { … } catch { Log.e } ` schluckt.

**Fix-Skizze:** in `at.planqton.fytfm.platform.FytPlatform`-Konstanten extrahieren, im Repo überschreibbar (für Mock-Builds & Tests).

### 1.5 ⚠️ RACE: `DabRecorder.stopRecording` schließt Stream während Encoder läuft
**Datei:** `dab/DabRecorder.kt:135 (close) vs. 233 (synchronized write)`
`stopRecording` setzt `outputStream = null` und schließt die Datei. Der Encoder-Executor schreibt aber asynchron weiter; sobald er den `synchronized(os)`-Block betritt **nachdem** `stop` lief, wirft `OutputStream.write()` ein `IllegalStateException`.

**Fix-Skizze:** `volatile`-Stop-Flag + Encoder prüft das Flag innerhalb des `synchronized`-Blocks vor dem `write`. Alternativ: `Executor.shutdownNow()` + `awaitTermination` vor dem `close`.

### 1.6 ⚠️ Race: `RadioScanner.foundStations` ohne Synchronisation
**Datei:** `scanner/RadioScanner.kt:183-184, 282`
`foundStations` ist eine `mutableListOf` auf dem Scan-Thread. Phase 2 mutiert sie weiter (clear+add), während `onProgress()` an Main postet. Wenn UI in der Zwischenzeit liest → `ConcurrentModificationException`.

**Fix-Skizze:** `Collections.synchronizedList`, oder besser: `MutableStateFlow<List<…>>` (immutable Snapshots).

### 1.7 ℹ️ Silent swallow: 11+ Stellen mit nacktem `catch (e: Exception) { Log.e(…) }`
- `FmAmController` (Z.131-135 powerOn, 352-355 seek, 451-456 setMute, 462-469 getRssi)
- `LogoSearchService` (Z.138-141, 173-176, 216-219)
- `DeezerCache.cacheTrack` (Z.126-128)
- `PresetRepository.loadStations` (Z.266-268) — `printStackTrace()` nur

Konsequenz: Defekte sind im Logcat unsichtbar, weil die App unauffällig in einen halbtoten Zustand übergeht (z. B. `isRadioOn=false` ohne UI-Reaktion).

**Fix-Skizze:** `onError`-Callback der Controller wirklich befeuern, in ViewModel zu Snackbar/Toast routen.

---

## 2. Quick Wins (≤ 1 Tag pro Punkt, große Wirkung)

### QW-1 — Toten ViewModel-Code aktivieren oder löschen (Entscheidungspunkt)
`viewmodel/RadioViewModel.kt` (467 Zeilen) und `viewmodel/SettingsViewModel.kt` (276 Zeilen) inkl. `RadioUiState.kt` und Tests existieren, werden aber nirgends instanziert. **Heute** kostet das Tests-Pflege, ohne Nutzen zu bringen.

→ **Empfehlung**: aktivieren (Phase A des Architekturplans, siehe §6). Wenn das nicht passieren soll: löschen, statt rotten zu lassen.

### QW-2 — Duplizierte `mergeXxxScannedStations` zusammenfassen
`PresetRepository`: `mergeScannedStations` (FM/AM, Z.600-654), `mergeDabScannedStations` (660-693), `mergeDabDevScannedStations` (698-731) sind quasi identisch. → eine generische `<T : Station> merge(target, scanned, prefs, key, …)`-Methode.

### QW-3 — `loadStation`-Cover Coil-Helper
~24 fast identische Stellen in `MainActivity` (`updateDabCoverDisplay`, `updateNowPlaying`, `updatePipDisplay`):
```kotlin
view.load(File(path)) { crossfade(true); placeholder(...); error(...) }
```
→ `ImageView.loadCover(path: String?, fallback: Int = R.drawable.ic_radio)` Extension. Spart ~150 Zeilen.

### QW-4 — `StationOverlayRenderer` extrahieren
`showStationChangeOverlay` / `showDabStationChangeOverlay` / `showPermanentStationOverlay` (3 × ~42 Zeilen, `MainActivity` Z.6527-6654) teilen `binding.stationBar.*`. → eine Klasse mit drei Modi (Mode enum: `FM_TRANSIENT`, `DAB_TRANSIENT`, `PERMANENT`).

### QW-5 — `View-Mode/-State`-Flags konsolidieren
5 Bool-Flags in MainActivity (`isCarouselMode`, `isAppInForeground`, `isPipMode`, `showFavoritesOnly`, `suppressSpinnerCallback`) — schon eine kleine `data class UiVisibilityState` würde Lesbarkeit + atomare Updates verbessern.

### QW-6 — `Handler/Runnable`-Paare auf Coroutines
17 Paare in MainActivity (Polling, Blink, Auto-Background, Carousel-Center, …). Jedes ist 6+ Zeilen Boilerplate (`postDelayed` + `removeCallbacks` in `onPause`/`onDestroy`). → `lifecycleScope.launch { while (isActive) { … delay(…) } }`. Reduziert Lifecycle-Bugs (vergessenes `removeCallbacks`).

### QW-7 — `RadioMode`-Enum dedupliziert
`FrequencyScaleView.RadioMode` und `RadioController.RadioMode` sind zwei Enums mit identischem Inhalt. → einer in `at.planqton.fytfm.model.RadioMode`, beide importieren.

### QW-8 — PiP/Archive Include-Layouts mit eigenen ViewBinding-Klassen
44 von 45 verbleibenden `findViewById`s sind PiP (11) + Archive (13). Wenn die `<include>`-Layouts ein `android:id` mit `layout/...`-Reference haben, generiert AGP automatisch eine Sub-Binding-Klasse → `binding.pipLayout.binding.…` statt `findViewById`.

---

## 3. MainActivity — die 7.865 Zeilen aufdröseln

### 3.0 Kurzrezept „MainActivity dünn machen" (in dieser Reihenfolge)

Jeder Schritt ist einzeln mergebar; die Reihenfolge ist wichtig, weil spätere Schritte auf früheren aufbauen.

| # | Schritt | Aufwand | MainActivity-Reduktion (geschätzt) |
|---|---------|---------|-------------------------------------|
| 1 | **ViewModel anschalten** — `RadioViewModel` + `SettingsViewModel` instanzieren, `radioVm.uiState.collect { renderRadioState(it) }`, direkte `radioController.*`-Calls auf `radioVm.…` umstellen. Danach ~30 mutable Felder + 49 Direct-Calls entfernbar. Siehe §6 Phase A. | 1–2 Tage | ~1.500 Z. |
| 2 | **Reine UI-Brocken extrahieren** (parallelisierbar): `CoverDisplayController`, `StationOverlayRenderer`, `ArchiveOverlayController`, `DabListViewBinder`. Siehe §3.2 #1–4. | ~4 Tage | ~875 Z. |
| 3 | **Letzte 7 `AlertDialog.Builder` → DialogFragment**: Edit-Station, Radio-Area, Templates, Logo-Search. Schema (`ConfirmationDialogFragment` & Co.) steht schon. Siehe §3.2 #6–7. | ~1 Tag | ~345 Z. |
| 4 | **Handler/Runnable → Coroutines** — 17 Paare durch `lifecycleScope.launch { while (isActive) { … delay(…) } }` ersetzen. Siehe QW-6. | ~0.5 Tag | ~120 Z. |
| 5 | **Cover-Coil-Helper** — Extension `ImageView.loadCover(path)` statt 24× das gleiche `load(File(path)) { … }`. Siehe QW-3. | ~1 h | ~150 Z. |

**Realistisches Ziel:** 7.865 → **~3.500 Zeilen**. ~40 % davon kommen aus Schritt 1, ~40 % aus Schritt 2, der Rest aus 3–5.

**Warum die Reihenfolge so ist:** Wenn Schritt 2 vor Schritt 1 läuft, müssen die neuen Controller-Klassen sich State direkt aus MainActivity-Feldern holen — nach Schritt 1 leben sie sauber vom ViewModel-State und sind sofort testbar.

### 3.1 Status-Quo (verifiziert)
- 11 thematische Regionen (Init/Setup, RDS-Polling, DAB-Callbacks, UI-Listener, Debug-Overlays, Deezer/Cover/Logo, DAB-List-View, Carousel, Archive, Dialog-Glue, Lifecycle/Persistenz)
- 117 mutable Felder, davon:
  - 17 Handler/Runnable-Paare
  - 7 DAB-Service-Felder + 3 DAB-Deezer-Felder + 3 Logging-Caches
  - 4 Cover-Source-Felder + 5 Deezer-Tracking-Felder
  - 5 UI-Mode-Flags + 3 UI-Cache-Maps + 15+ Adapter/View-Refs
- 7 verbleibende `AlertDialog.Builder` (Radio-Area, Templates, Logo-Download-Progress, Logo-Download-Error, Manual-Logo-Search, Edit-Station, Edit-DAB-Station)
- 49+ direkte `radioController.*`-Calls; 11 Lambda-Callbacks werden inline zugewiesen, nicht über ein Interface
- 8 verbleibende große Funktionen (101–138 Zeilen): `parseRawRtToTrackInfo`+`updateNowPlaying`, `searchImagesWithDuckDuckGo`, `updateSlideshowIndicators`, `initDeezerIntegration`, `setupDabListMode`, `updateDabCoverDisplay`, `setupRadioControllerDabCallbacks`, `setupListeners`

### 3.2 Vorgeschlagene Extraktion (in Reihenfolge der Hebelwirkung)

| # | Neue Klasse | Was rauskommt | Geschätzte Zeilen aus MainActivity |
|---|-------------|---------------|------------------------------------|
| 1 | `ui/cover/CoverDisplayController` | `updateDabCoverDisplay`, `updateSlideshowIndicators`, `updateNowPlaying` (Cover-Teil), 4-Quellen-Cycling, MediaSession-Sync | ~350 |
| 2 | `ui/dab/DabListViewBinder` | `setupDabListMode`, Swipe-Gesten, EPG-Integration, Info-Bar, Such-Overlay | ~250 |
| 3 | `ui/overlay/StationOverlayRenderer` | drei `show…StationChangeOverlay`-Varianten | ~125 |
| 4 | `ui/archive/ArchiveOverlayController` | `showArchiveOverlay`, `setupArchiveUI`, `loadArchiveData` | ~150 |
| 5 | `ui/logosearch/ManualLogoSearchActivity` (oder DialogFragment) | `searchImagesWithDuckDuckGo` + Grid-Adapter | ~140 |
| 6 | `ui/dialogs/RadioAreaDialogFragment` + `AreaTemplateDialogFragment` | letzte `AlertDialog.Builder` für Radio-Area + Templates + Download-Progress | ~250 |
| 7 | `ui/edit/EditStationDialogFragment` | `showEditStationDialogInternal` + `showEditDabStationDialogInternal` | ~95 |
| 8 | `ui/pip/PipController` | Inflation, Visibility, Update | ~120 |
| 9 | `lifecycle/AutoBackgroundController` | Auto-Background-Timer + Toast-Cancellation-Sonderfall | ~80 |
| 10 | `state/MainActivityState` (data class) | 5 UI-Mode-Flags + 5 Deezer-Tracking-Felder + 4 Cover-Source-Felder | (-14 Felder, +1 Datenklasse) |

**Geschätzte MainActivity-Zielgröße nach allen 10 Schritten:** ~5.500 Zeilen — danach ist der Rest reine Glue zu ViewModel & Repos und kann durch Aktivierung von ViewModel weiter halbiert werden (siehe §6).

### 3.3 Sortierung nach Risiko/Nutzen
- **Niedriges Risiko, hoher Nutzen:** #3, #4, #6, #7 (reine UI-Klassen, klare Grenzen)
- **Mittleres Risiko, hoher Nutzen:** #1 (3-View-Sync ist heikel), #2 (RecyclerView-Adapter-Lifecycle)
- **Höher Risiko:** #10 (Felder-Konsolidierung berührt 30+ Funktionen) — sinnvoll erst nach ViewModel-Aktivierung (§6), dort gehört der State eh hin

---

## 4. PresetRepository aufspalten

### 4.1 Was drinsteckt (919 Zeilen, gemischt)

| Bucket | Zeilen ca. | Inhalt |
|--------|-----------|--------|
| FM/AM/DAB Stations CRUD | 40–281 | `loadFmStations`, `saveFmStations`, …, `mergeXxxScannedStations` (3×), Favoriten-Toggle |
| Per-Tuner-Presets & Plugin-Settings | 54–175 | Tuner-ID-skopierte Listen, beliebige `Map<String,Any>` für Plugins |
| 50+ App-Settings | 385–918 | autoplay, Deezer-Toggles, dark mode, carousel mode, debug-fenster, tick-sounds, classical lock, per-mode-carousel-mode |
| StateFlows (4 Stations-Listen) | 40–50 | `fmStations`, `amStations`, `dabStations`, `dabDevStations` |

**Persistenz:** 5 separate `SharedPreferences`-Files (fm, am, dab, dab_dev, settings). Stations sind JSON-Blobs, Settings primitiv.

### 4.2 Probleme
- **Threadsicherheit unklar**: `.value`-Updates der StateFlows passieren auf der Caller-Thread. Wer von Background spart, mutiert StateFlow von Background — unter Compose/View-Observer ggf. Crash.
- **StateFlow ist NICHT die Quelle der Wahrheit** — Reads gehen weiter direkt an SharedPreferences. Doppelquelle = Inkonsistenz-Risiko.
- **Settings-Methoden sind reine Snapshot-Getter** — keine Flow-Form für reaktive UI.
- **Plugin-Map** untypisiert (`Map<String, Any>`) — JSON-Roundtrip kann Typen verlieren.
- **3-fache Merge-Duplikation** (siehe QW-2).

### 4.3 Vorgeschlagene Aufspaltung

```
data/
├── stations/
│   ├── StationRepository.kt          ← FM/AM/DAB/DABdev CRUD + Favoriten + StateFlows
│   ├── StationMerger.kt              ← die EINE merge-Logik (statt 3×)
│   └── PluginSettingsRepository.kt   ← getrennt; typisiertes API
├── settings/
│   ├── AppSettingsRepository.kt      ← 50+ Settings, jeweils als Flow + suspend setter
│   └── SettingsKeys.kt               ← Konstanten
└── (bestehend) UpdateRepository.kt, logo/…
```

- **Migration**: `PresetRepository` bleibt initial als Fassade bestehen, leitet an die drei neuen Klassen weiter — danach Call-Site-Migration in zwei Schritten.
- **Threadsafety**: alle `set*`/`save*` als `suspend` mit `withContext(Dispatchers.IO)`; StateFlows werden in `init` einmal aus den Prefs gefüllt und ab da als Single-Source-of-Truth verwendet.
- **Settings-Flows**: `Flow<Boolean>` über `SharedPreferences.OnSharedPreferenceChangeListener` (oder gleich auf `DataStore` migrieren — Entscheidung: erst nur Flow-API einziehen, DataStore-Migration als getrenntes Vorhaben).

---

## 5. Controller- und Tuner-Schicht

### 5.1 Status
- `RadioController` (401 Z.) ist ein dünner Koordinator + 18 Callback-Properties + duplizierte Conditional-Trees
- `DabController` (300 Z.) hält 7 DAB-Felder + bridged 12 Callbacks an `DabTunerManager`
- `FmAmController` (582 Z.) hält 6 RDS-Felder + komplexe FYT-MCU-Power-Sequenz + setMode mit Reload
- `DabTunerManager` (714 Z.) implementiert 4 Listener-Interfaces, postet alle Callbacks via `Handler(mainLooper)`

### 5.2 Probleme & Vorschläge

#### 5.2.1 Callback-Wildwuchs → Listener-Registry
12+12+18 nullable Lambda-Properties = man weiß nie, ob ein Listener noch dranhängt oder schon überschrieben wurde (siehe DabController.startRecording-Bug, Z.250–252: überschreibt onRecordingStarted statt zu chainen).

→ Vorschlag: pro Controller ein klar typisiertes `interface XyzListener` mit `addListener(l)` / `removeListener(l)`. Innenliegend `CopyOnWriteArrayList` (oder besser: `MutableSharedFlow<Event>` und Subscriber).

#### 5.2.2 Hardcoded Plattform → `FytPlatform`-Abstraktion
(siehe Bug 1.4) — Trennung: `interface RadioPlatform { fun bringToForeground(); val intentAction: String; … }` mit `FytPlatform`-Default und `NoopPlatform` für Tests/Mocks.

#### 5.2.3 Reaktivität — von Callbacks zu StateFlow
Aktuell rufen Controller Callbacks. ViewModel könnte stattdessen `controller.stateFlow.collect { … }`. Vorteil: kein Listener-Leak, automatische Lifecycle-Bindung via `repeatOnLifecycle`. Erfordert §6 (ViewModel-Aktivierung).

#### 5.2.4 Mode-Persistenz
Eine Schicht ist verantwortlich (siehe Bug 1.3). Empfehlung: `RadioController` ist Owner, `FmAmController.loadLastMode` entfällt; `RadioController` injiziert Mode beim `init`/`setMode`.

#### 5.2.5 `FrequencyScaleView` als Source-of-Truth
Hält `radioMode` und `currentFrequency`. → reine View machen: `fun bind(state: FrequencyState)`. Aktuelles `currentMode`-Property in MainActivity (`binding.frequencyScale.getMode()`) entfällt.

---

## 6. ViewModel aktivieren — die größte Hebelwirkung

`RadioViewModel.kt` (467 Z.) hat schon:
- `RadioUiState` sealed (FmAm | Dab | Off)
- StateFlow-Architektur (`_uiState`, `_stationListState`, `_lastError`)
- 15+ Methoden: `togglePower`, `setMode`, `tune`, `tuneStation`, `skipStation`, …
- Eigene Tests

`SettingsViewModel.kt` (276 Z.) wraps `PresetRepository`-Setter mit StateFlow-State.

### 6.1 Migrationsweg (vorgeschlagen)

**Phase A — Anbindung (1–2 Tage)**
1. In `MainActivity`: `private val radioVm: RadioViewModel by viewModels { RadioViewModelFactory(...) }` + `settingsVm`.
2. `lifecycleScope.launch { repeatOnLifecycle(STARTED) { radioVm.uiState.collect { renderRadioState(it) } } }`.
3. `renderRadioState(state)` ersetzt zunächst nur die offensichtlichsten Direct-Field-Updates (Frequenz-Anzeige, Power-Button, Mode-Spinner).
4. Direkte `radioController.setMode/tune/togglePower`-Calls in MainActivity → `radioVm.…`.

**Phase B — State-Felder retiren (2–3 Tage)**
- 7 DAB-Service-Felder + 6 RDS/FM-Felder bewegen in `RadioUiState`. MainActivity liest nur noch über State-Snapshot.
- Cover-Source-State (4 Felder) → eigener `CoverState` (gehört zu `CoverDisplayController` aus §3.2 #1).

**Phase C — Settings entkoppeln (1 Tag)**
- Settings-Dialog (bereits `SettingsDialogFragment`) holt sich `SettingsViewModel`. MainActivity-Settings-Callbacks (`settingsLanguageCallback` etc.) entfallen.

**Phase D — DI optional (1–2 Tage)**
- Hilt einführen, dann die manuellen Factories durch `@HiltViewModel + @Inject` ersetzen. Reines Komfort-Upgrade, nicht zwingend.

### 6.2 Definition-of-Done für „MainActivity ist dünn"
- Keine Repository-Aufrufe in MainActivity (nur ViewModel)
- `mutable var`-Felder ≤ 30 (von 117)
- Keine `Handler/Runnable`-Paare mehr (alles `lifecycleScope`)
- Datei < 3.500 Zeilen

---

## 7. Tests

### 7.1 Heutige Lücken

| Klasse | Tests | Status |
|--------|-------|--------|
| `PresetRepository` | 29 (PresetRepositoryTest.kt) | gut, aber **kein** Concurrency- oder Merge-Test |
| `RtCombiner` | 10 (RtCombinerTest.kt) | grundlegend; **kein** Buffer-Combine-Test, **kein** Classical-Pfad |
| `DeezerCache` | 0 | — (hat den Hard-Crash aus 1.1) |
| `DeezerClient` | 0 | — (7-Strategie-Suche unmetricated) |
| `DlsParser` | 0 | — (komplex genug für mind. 15 Tests) |
| `ClassicalMusicNormalizer` | 0 | — (80+ Composer-Mappings, keine Validierung) |
| `RadioLogoRepository` | 0 | — |
| `LogoSearchService` | 0 | — (XML-Regex-Parsing, fragil) |
| `UpdateRepository` | 0 | — (Network on executor) |
| `RadioScanner` | 0 | — (Threading + Cancel-Logic) |
| `RadioController` / `DabController` / `FmAmController` | 0 | — |

### 7.2 Vorschlag — Test-Reihenfolge nach Schadensvermeidung
1. **`DeezerCache`** zuerst — wegen Hard-Crash 1.1, plus Suchverhalten (O(n)-Scan, kein WHERE) ist regressionsanfällig.
2. **`DlsParser`** — die Heuristiken sind so spezifisch, dass jede Code-Änderung was kaputt machen kann. Comment-Beispiele (Z.8–12) als Test-Cases einlesen.
3. **`ClassicalMusicNormalizer`** — Regex-Edge-Cases (z. B. „BACH" allein) systematisch.
4. **`PresetRepository.merge*`** — vor Refaktorierung (QW-2) absichern.
5. **`RadioScanner`** — Cancel-Verhalten und Phase-2-Race.
6. **`DeezerClient`** — Strategy-Reihenfolge mit MockServer.
7. **Controller** — erst nach Listener-Registry-Umbau (§5.2.1), vorher zu schmerzhaft.

### 7.3 Infrastruktur
- `MockK` ist vermutlich bereits da (PresetRepositoryTest nutzt Mocks). Falls nein: `mockk` + `kotlinx-coroutines-test` für `runTest`.
- Für `DeezerCache`: `Robolectric` oder `androidx.test.ext.junit` für In-Memory-SQLite.

---

## 8. Roadmap (priorisiert)

| Prio | Block | Aufwand | Abhängigkeiten | Hebel |
|------|-------|---------|----------------|-------|
| 🔴 P0 | Bugs 1.1 (DB-Reopen), 1.2 (Timer-Leak), 1.3 (Mode-Dualität) | 1 Tag | – | verhindert Crashes / Falschverhalten |
| 🔴 P0 | Bug-Tests für 1.1, 1.5, 1.6 | 1 Tag | – | Regressionsschutz |
| 🟠 P1 | QW-1: ViewModel-Entscheidung (aktivieren / löschen) | 0.5 Tag | – | Fundamentale Richtung |
| 🟠 P1 | §6 Phase A: ViewModel-Anbindung | 1–2 Tage | QW-1 | erlaubt §3.2 #10 |
| 🟠 P1 | QW-2 + QW-3 + QW-7 | 1 Tag | – | sofortige Lesbarkeit |
| 🟠 P1 | §4 PresetRepository-Aufspaltung (Phase 1: Stations heraus) | 2 Tage | QW-2 | entlastet Settings + ermöglicht reaktive Settings |
| 🟡 P2 | §3.2 #1–4 (CoverDisplay, DabList, Overlay, Archive extrahieren) | 4 Tage | – (parallelisierbar) | MainActivity → ~6.500 Z. |
| 🟡 P2 | §6 Phase B (State-Felder retiren) | 2–3 Tage | Phase A + §3.2 #1 | MainActivity dünn machen |
| 🟡 P2 | §5.2.1 Listener-Registry + §5.2.2 Plattform-Abstraktion | 2 Tage | – | Test-Bar machen |
| 🟢 P3 | §3.2 #5–9 (LogoSearch, Dialoge, PiP, AutoBg) | 3 Tage | – | Restliche AlertDialog.Builder weg |
| 🟢 P3 | §7.2 Tests #1–4 | 3 Tage | parallel | Coverage 10 % → 35 % |
| 🟢 P3 | §6 Phase D (Hilt) | 1–2 Tage | Phase A–C | Komfort, optional |
| 🔵 P4 | DataStore-Migration | 2 Tage | §4 abgeschlossen | langfristige Persistenz-Hygiene |
| 🔵 P4 | Accessibility (TODO bestehend) | 2 Tage | – | – |

**Gesamt-Aufwand „bis dünn":** ca. 18–22 Personentage (P0–P2). Restliches P3/P4 nach Bedarf.

---

## 9. Was ich NICHT empfehle (bewusst)

- **Compose-Migration jetzt**: Die Custom-View `FrequencyScaleView` ist nicht trivial portierbar. Erst MVVM stabilisieren, dann ggf. neuer Bildschirm pro Bildschirm in Compose — aber nicht in einem Rutsch.
- **Kotlin Multiplatform**: keine erkennbare Cross-Plattform-Anforderung.
- **Room statt SharedPreferences für Settings**: SharedPreferences ist für 50 Keys angemessen; DataStore (siehe P4) wäre ausreichend.
- **Big-Bang-Refactoring**: jede der oben genannten Phasen ist einzeln mergebar; keine sollte > 1 Woche brauchen.

---

## 10. Quellen / Referenzen
- `refractoring/REFACTORING_STATUS.md` — was bisher passiert ist
- `refractoring/verbesserung.md` — älterer Architekturplan (deckt sich teilweise; §6 hier ist die Umsetzungs-Konkretion davon)
- `refractoring/todo.md` — offene Items (MVVM, RadioScanner-Tests, Accessibility)
- Eigene Code-Inspektion 2026-04-22 mit Explore-Agents (MainActivity, Data-Layer, Controller-Layer)
