# Reaktive Stationslisten in PresetRepository — 22.04.2026

## Ziel
Stationslisten (FM / AM / DAB / DAB-dev) als `StateFlow` aussetzen, damit die UI
sich später automatisch aktualisieren kann. Aus `verbesserung.md` Phase 1 und
`todo.md` („PresetRepository reaktiv machen") — der erste konkrete Schritt
Richtung MVVM.

Diese Session bleibt **bewusst klein**: nur die Infrastruktur in
`PresetRepository` (+ Test), kein MainActivity-Umbau. Das hält den Blast-Radius
klein und erlaubt später schrittweises Umstellen der Callsites.

## Design-Entscheidung: single source of truth *nicht* angefasst

Kurz abgewogen:

| Ansatz | Pro | Contra |
|---|---|---|
| **A) StateFlow als Single Source of Truth, `load*Stations()` liest StateFlow.value** | konsequent, keine Doppelung | bricht bestehende Tests (Mocks setzen Rückgabewerte *nach* dem Ctor-Aufruf, StateFlow würde mit leeren Listen initialisiert); bricht Verhalten, wenn SharedPrefs extern verändert werden |
| **B) StateFlow mirror'd die SharedPrefs, `load*Stations()` liest weiter aus prefs** | null Risiko für Altcode & Tests, kein semantischer Wechsel | minimale Doppelung (ein extra JSON-Parse pro save) |

Entschieden für **B**. Prefs bleiben autoritativ, StateFlow ist ein zusätzlicher
Reaktivitäts-Kanal.

## Änderungen

### `app/src/main/java/at/planqton/fytfm/data/PresetRepository.kt`
- Imports: `MutableStateFlow`, `StateFlow`, `asStateFlow`.
- Vier interne `MutableStateFlow<List<RadioStation>>` direkt nach den
  `SharedPreferences`-Feldern, initialisiert aus den Prefs:
  ```kotlin
  private val _fmStations = MutableStateFlow(loadStations(fmPrefs, isAM = false))
  val fmStations: StateFlow<List<RadioStation>> = _fmStations.asStateFlow()
  // analog für amStations, dabStations, dabDevStations
  ```
- `saveFmStations`, `saveAmStations`, `saveDabStations`, `saveDabDevStations`
  schreiben wie bisher in die Prefs und emittieren anschließend die frisch
  geladene Liste (damit die Sortier-Regeln aus `loadStations` konsistent sind).
- `clearFmStations`, `clearAmStations`, `clearDabStations`, `clearDabDevStations`
  emittieren `emptyList()`.
- Alle bestehenden Pfade (`toggleFavorite`, `toggleDabFavorite`,
  `toggleDabDevFavorite`, `mergeScannedStations`, `mergeDabScannedStations`,
  `mergeDabDevScannedStations`) laufen durch `save*Stations()` und aktualisieren
  damit automatisch den StateFlow — **keine zusätzlichen Emitter nötig**.

### `app/src/test/java/at/planqton/fytfm/data/PresetRepositoryTest.kt`
Drei neue Tests:
- `fmStations flow emits list after saveFmStations` — slot-basierter
  Capture-Trick: `putString` capturet das JSON, anschließend liefert `getString`
  genau dieses JSON. Damit greift `_fmStations.value = loadStations(…)` auf das
  tatsächlich gespeicherte JSON zu.
- `fmStations flow resets to empty after clearFmStations` — speichert erst,
  ruft dann `clearFmStations()`.
- `dabStations flow emits after saveDabStations` — analog für DAB, prüft, dass
  `serviceId` etc. erhalten bleiben.

## Ergebnis

| | |
|---|---|
| Dateien geändert | 2 (Repository + Test) |
| Neue öffentliche API | `fmStations`, `amStations`, `dabStations`, `dabDevStations` |
| Unit-Tests | vorher 21, jetzt 24, **alle grün** |
| `assembleDebug` | grün |

## Nächste Schritte (wenn MainActivity umgestellt wird)

Einige Callsites in MainActivity, die sich für den Umstieg auf die Flows
anbieten (grob, nach Wichtigkeit):

1. **`loadStationsForCurrentMode()`** — aktuell ein manuelles Neu-Laden nach
   Favoriten-Änderungen/Scans. Durch `collect` eines der Flows würde das
   ersatzlos wegfallen.
2. **`toggleFavoritesFilter()`-Flow** — liest aktuell im Anschluss neu; auch
   hier kann `collect` die manuelle Aktualisierung ablösen.
3. **Station-Carousel / DAB-Liste** — beziehen Daten via `loadFmStations()`
   bzw. `loadDabStations()`. Die Listen-Adapter könnten direkt aus dem Flow
   gefüttert werden.

Reihenfolge-Empfehlung: erst eine einzelne Callsite umstellen, Verhalten
verifizieren, dann die restlichen. *Nicht* in einem Rutsch — manche Aufrufe
stehen in heiklen Power-On/Power-Off-Sequenzen und brauchen ein kontrolliertes
lifecycle-scope.

## Was **nicht** gemacht wurde (und warum)

- **Per-Tuner-Storage** (`saveStationsForTuner` etc.) — nicht reaktiv gemacht,
  da die tuner-IDs dynamisch sind (keine stabilen Flow-Felder möglich). Wird
  erst relevant, wenn das Plugin-System mehrere Tuner parallel nutzt.
- **Settings-Keys** (autoplay, darkMode, localMode, …) — auch nicht reaktiv,
  das passt besser in einen künftigen `SettingsViewModel`-Schritt (ist laut
  `17-04-26.md` teilweise bereits vorbereitet).
- **MainActivity-Callsites** — keine Änderung in dieser Session.
