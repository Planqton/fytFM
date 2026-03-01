# fytFM Knowledge Base

## RT Buffer Logic (RtCombiner)

### Problem
Manche Sender (z.B. Kronehit) senden Artist und Title als **separate RDS-Nachrichten**:
- RT 1: "Beyoncé" (nur Artist)
- RT 2: "Dance for You" (nur Title)

Ohne Buffer würde die erste Nachricht sofort gesucht und ein falsches/unvollständiges Ergebnis liefern.

### Lösung: Automatische Buffer-Erkennung

**Heuristik in `RtCombiner.shouldBufferFirst()`:**
- RT ist **kürzer als 25 Zeichen**
- RT enthält **keinen Separator** (" - ", " – ", " — ", " / ", " | ")

Wenn beide Bedingungen zutreffen:
1. RT wird im Buffer gesammelt (nicht sofort gesucht)
2. Erst bei 2+ Buffer-Einträgen wird mit Kombinationen gesucht
3. Buffer-Timeout: 15 Sekunden

**Code:** `app/src/main/java/at/planqton/fytfm/spotify/RtCombiner.kt`

```kotlin
companion object {
    private const val SHORT_RT_THRESHOLD = 25
    private val SEPARATORS = listOf(" - ", " – ", " — ", " / ", " | ")
}

private fun shouldBufferFirst(rt: String): Boolean {
    if (SEPARATORS.any { rt.contains(it) }) return false
    return rt.length < SHORT_RT_THRESHOLD
}
```

### Beispiel-Flow

**Kronehit (separate RTs):**
1. RT "Beyoncé" → kurz, kein Separator → Buffer (size=1)
2. RT "Dance for You" → kurz, kein Separator → Buffer (size=2)
3. Kombinationen werden gesucht: "Beyoncé Dance for You"
4. Spotify findet: "Beyoncé - Dance for You"

**Normaler Sender (vollständiger RT):**
1. RT "Beyoncé - Dance for You" → hat Separator → sofort suchen
2. Spotify findet direkt den Track

---

## Spotify/Lokal-Suche pro Sender

### Einstellung
Die Spotify/Lokal-Suche kann **pro Frequenz** aktiviert/deaktiviert werden.

**Standard:** Aktiviert für alle Sender

**Wenn deaktiviert:**
- Nur Raw RDS wird angezeigt
- Radio-Icon statt Album-Cover
- Keine Spotify/Lokal-Suche

**Speicherung:** `PresetRepository.isSpotifyEnabledForFrequency(frequency)`
- Speichert Set von deaktivierten Frequenzen
- Format: "%.1f" (z.B. "105.8")

**UI:**
- Toggle-Button in Now Playing Bar
- Switch im Radio Editor (Sender bearbeiten)

---

## Now Playing Bar

### Verhalten
- **Permanent sichtbar** (wird nie versteckt)
- Zeigt je nach Datenlage:

| Priorität | Datenquelle | Cover | Title | Artist |
|-----------|-------------|-------|-------|--------|
| 1 | Spotify/Lokal Match | Album-Cover | Song Title | Artist |
| 2 | Raw RDS | Radio-Icon | RDS Text | - |
| 3 | Preset | Station-Logo | Sendername | Frequenz |
| 4 | Keine Daten | Radio-Icon | Frequenz | - |

### Bei Frequenzwechsel
1. `resetNowPlayingBarForStation()` wird aufgerufen
2. Zeigt sofort: Station-Logo + Sendername + Frequenz (falls Preset vorhanden)
3. Dann: RDS-Daten (wenn empfangen)
4. Dann: Spotify/Lokal-Daten (falls aktiviert und gefunden)
