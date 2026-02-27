# To Finish - fytFM

## Aktueller Stand (27.02.2026)

### Zuletzt bearbeitet: PiP-Layout Problem nach Sleep-Mode

**Problem:** Wenn das Android Radio in den Sleep-Mode geht und wieder aufwacht, passt das PiP-Layout nicht mehr.

**Bereits implementierte Fixes:**
- `onResume()` - checkt PiP-Mode wenn App zurückkommt
- `onWindowFocusChanged()` - checkt PiP-Mode wenn Display nach Sleep angeht
- `recheckPipMode()` - gemeinsame Funktion für beide Checks
- Initial check in `setupPipDetection()` mit `post{}`

**Status:** Muss getestet werden ob es nach Sleep-Mode jetzt funktioniert.

---

### Andere Fixes in dieser Session:

1. **EditString Leerzeichen-Bug** - Leerzeichen werden nicht mehr getrimmt
   - `EditString.kt`: Zeile 76 und 61 - `.trim()` entfernt
   - `RtCombiner.kt`: Zeile 101 - `.trim()` entfernt
   - User muss alte Regeln neu anlegen (z.B. ` mit ` → ` - `)

2. **RT Korrekturen Dialog UI** - Item-Layout auf ConstraintLayout umgestellt
   - `item_edit_string.xml` - Switch, Text, Buttons jetzt in einer Zeile
   - `dialog_corrections_viewer.xml` - `fillViewport="false"` gegen riesige Abstände

3. **Spotify Debug Anzeige**
   - Zeigt jetzt immer "Orig:" und "Suche:" Zeilen
   - LOKAL/SPOTIFY Source wird jetzt richtig aktualisiert (auch bei gleichem Track)
   - `maxLines` von 2 auf 4 erhöht für vollständige Anzeige

---

### Dateien die geändert wurden:
- `MainActivity.kt` - PiP detection, Spotify Debug
- `EditString.kt` - trim() Bug fix
- `RtCombiner.kt` - trim() Bug fix
- `EditStringsAdapter.kt` - Display fix
- `item_edit_string.xml` - ConstraintLayout
- `dialog_corrections_viewer.xml` - fillViewport fix
- `activity_main.xml` (alle Varianten) - maxLines für debugSpotifyRtInput
