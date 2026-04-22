# PiP- & Archive-Include-Layouts auf ViewBinding — 22.04.2026

## Ziel
`findViewById`-Aufrufe für die beiden Include-Layouts `pip_layout.xml` und
`overlay_archive.xml` entfernen und über ViewBinding (`binding.pipLayout.*` /
`binding.archiveOverlay.*`) ansprechen. Damit bleiben nur noch die
`dialogView.findViewById`-Aufrufe in Dialog-Code übrig, die korrekt sind.

## Ergebnis

| Metrik | Vorher | Nachher |
|--------|-------:|--------:|
| `findViewById` in MainActivity | 45 | 21 |
| davon „Nicht-Dialog" (PiP + Archive) | 24 | 0 |
| davon Dialog (`dialogView.findViewById` / `progressView.findViewById`) | 21 | 21 |
| MainActivity Zeilen | 7 883 | 7 865 |
| Entfernte Properties (`private var`) | — | 6 (`pipLayout`, `pipCoverImage`, `pipTitle`, `pipArtist`, `pipBtnPlayPause`, `pipRawRt`) |

Build: `./gradlew assembleDebug` erfolgreich, nur Vorgänger-Warnungen (deprecated API).

## Geänderte Dateien

### Layouts (IDs auf `<include>`-Tag ziehen)
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout-land/activity_main.xml` ← **wichtig**, musste auch mitziehen
- `app/src/main/res/layout-sw600dp/activity_main.xml` ← dito
- `app/src/main/res/layout-v26/activity_main.xml` ← dito
- `app/src/main/res/layout/pip_layout.xml` (root-ID entfernt, damit sie nicht mit der Include-ID kollidiert)
- `app/src/main/res/layout/overlay_archive.xml` (dito)

### Code
- `app/src/main/java/at/planqton/fytfm/MainActivity.kt`

## Vorgehen

1. **Include-Tag bekommt eine ID** (in allen vier `activity_main.xml`-Varianten):
   ```xml
   <include
       android:id="@+id/pipLayout"
       layout="@layout/pip_layout"
       android:layout_width="match_parent"
       android:layout_height="match_parent" />
   ```
   Analog für `archiveOverlay`.

2. **Alter Root-ID aus dem Include-Layout entfernt**, um doppelte IDs zu
   vermeiden — `<FrameLayout android:id="@+id/pipLayout" …>` →
   `<FrameLayout …>` in `pip_layout.xml` / `overlay_archive.xml`.

3. **Kotlin-Seite umgebaut**:
   - PiP-Property-Block (`private var pipLayout … pipRawRt`) ersatzlos gelöscht.
   - Init-Block in `initViews()` (`pipLayout = findViewById(…)` usw.) entfernt.
   - Alle Zugriffe ersetzt: `pipTitle?.text = …` → `binding.pipLayout.pipTitle.text = …`.
   - `findViewById<View>(R.id.pipLayout).visibility = …` →
     `binding.pipLayout.root.visibility = …`.
   - Archive: analog — 13 Fundstellen in `showArchiveOverlay`, `hideArchiveOverlay`,
     `setupArchiveUI`, `loadArchiveData`, `updateFilterChipSelection`.

## Stolperstein — und die Lehre

Nach dem ersten Anlauf meldete der Compiler `Unresolved reference 'pipLayout'`
auf `binding.pipLayout.*`. Ursache: ViewBinding generiert für Includes nur dann
ein Property, wenn **alle** Layout-Varianten denselben `<include>`-Tag inklusive
`android:id` tragen. Die drei Varianten unter `layout-land/`, `layout-sw600dp/`
und `layout-v26/` hatten die alten Includes ohne ID — ViewBinding blieb bei der
Generierung stumm (keine Fehlermeldung, nur kein Property).

**Merke für nächstes Mal:** bei jeder View-Binding-Änderung an einem Layout mit
Alternativ-Ressourcen alle Varianten mit `find app/src/main/res/layout*` prüfen.

## Was als Nächstes sinnvoll wäre (laut `verbesserung.md` und `todo.md`)

- **MVVM-Schritt**: `SettingsViewModel` / reaktives `PresetRepository` (StateFlow
  für Stationslisten). Infrastruktur (`RadioViewModel`, `RadioUiState`) existiert
  bereits und wird teilweise genutzt.
- **Restliche komplexe Dialoge** zu DialogFragments (Radio Area, Logo Search,
  Logo Save, Area Template Selection, Download Progress). Die Frequenz-/Chip-
  Layouts dort sind aber XML-artig ähnlich — jeweils eigenes Layout-File nötig.
- **Accessibility**: `contentDescription` für PiP-Buttons ist teils noch
  hart-englisch (`"Previous"`, `"Next"`, `"Play/Pause"`) — beim nächsten
  Dark-/Light-Durchgang in `strings.xml` umziehen.
