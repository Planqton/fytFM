# Experimental Logo Search Feature

**Status:** Experimental
**Added:** 2026-04-02
**Can be removed:** Yes

## Beschreibung

Diese Feature ermöglicht das automatische Suchen von Senderlogos für FM, AM und DAB Sender.

## Quellen

1. **RadioDNS** (nur DAB)
   - DNS-Lookup mit DAB-Parametern: `<scId>.<sId>.<eId>.<gcc>.dab.radiodns.org`
   - Holt SI.xml vom Broadcaster-Server
   - Extrahiert Logo-URLs aus dem XML

2. **radio-browser.info** (FM/AM/DAB Fallback)
   - Suche nach Sendername
   - Gibt `favicon` URL zurück

## Betroffene Dateien

### Neue Dateien (können komplett gelöscht werden):
- `app/src/main/java/at/planqton/fytfm/data/logo/LogoSearchService.kt`
- `docs/EXPERIMENTAL_LOGO_SEARCH.md` (diese Datei)

### Modifizierte Dateien:

#### `app/src/main/res/layout/dialog_radio_editor.xml`
- Button `btnLogoSearch` hinzugefügt im Header
- **Zum Entfernen:** RelativeLayout Header durch einfaches TextView ersetzen

#### `app/src/main/java/at/planqton/fytfm/MainActivity.kt`
- `showRadioEditorDialog()`: Button Click Handler für Logo-Suche
- `showLogoSearchDialog()`: Neuer Dialog für Suchergebnisse
- `performLogoSearch()`: Führt die Suche durch
- **Zum Entfernen:** Suche nach `// EXPERIMENTAL_LOGO_SEARCH` Kommentaren

#### `app/build.gradle` (falls dnsjava hinzugefügt):
- `implementation("dnsjava:dnsjava:3.5.3")`
- **Zum Entfernen:** Diese Zeile löschen

## Zum Vollständigen Entfernen

1. Lösche `LogoSearchService.kt`
2. Lösche diese Dokumentation
3. In `dialog_radio_editor.xml`: Ersetze den RelativeLayout Header durch:
   ```xml
   <TextView
       android:layout_width="match_parent"
       android:layout_height="wrap_content"
       android:gravity="center"
       android:paddingTop="24dp"
       android:paddingBottom="16dp"
       android:text="Radio Editor"
       android:textColor="#333333"
       android:textSize="20sp"
       android:textStyle="bold" />
   ```
4. In `MainActivity.kt`: Entferne alle Blöcke mit `// EXPERIMENTAL_LOGO_SEARCH`
5. In `build.gradle`: Entferne dnsjava Dependency

## Bekannte Einschränkungen

- RadioDNS funktioniert nur für Sender, die RadioDNS eingerichtet haben
- Österreichische Sender haben oft kein RadioDNS
- radio-browser.info ist primär für Internet-Radio, nicht alle DAB/FM Sender sind gelistet
- Logo-Qualität variiert stark
