# FytFM - TODO / Verbesserungen

## Hohe Priorität

- [ ] **MainActivity Refactoring** - 7.500+ Zeilen aufteilen
  - DAB-Logik in eigene Klasse extrahieren
  - FM/AM-Logik in eigene Klasse extrahieren
  - MVVM Pattern implementieren (ViewModel)
  - UI-State in StateFlow auslagern

## Mittlere Priorität

- [ ] **Unit Tests hinzufügen**
  - RtCombiner testen
  - PresetRepository testen
  - RadioScanner testen

- [ ] **Error Handling verbessern**
  - DeezerClient Netzwerk-Fehler besser abfangen
  - Fallbacks für fehlgeschlagene API-Calls

- [ ] **DAB+ UI verbessern**
  - Eigenes DAB+ Icon statt FM-Icon
  - DLS (Dynamic Label) in Now-Playing anzeigen

## Niedrige Priorität

- [ ] **Dark Mode** implementieren

- [ ] **Hard-coded Strings** in strings.xml verschieben

- [ ] **Accessibility** verbessern
  - Content Descriptions hinzufügen
  - Screen Reader Support

- [ ] **Coroutine Lifecycle** - Explizite Cancellation bei Activity Destroy

---

## Erledigte Features

- [x] DAB+ USB Dongle Support
- [x] FM/AM Radio mit RDS
- [x] Deezer Track-Erkennung
- [x] Android Auto Integration
- [x] Lenkradtasten (FYT Syu)
- [x] Plugin-System
- [x] Station Carousel
- [x] Auto-Background Timer
- [x] Overlay bei Senderwechsel
