# FytFM - TODO / Verbesserungen

## Hohe Priorität

- [x] **MainActivity Refactoring** - 7.500+ Zeilen aufteilen
  - [x] DAB-Logik in eigene Klasse extrahieren (DabController)
  - [x] FM/AM-Logik in eigene Klasse extrahieren (FmAmController)
  - [x] RadioController als Koordinator erstellt
  - [ ] MVVM Pattern implementieren (ViewModel)
  - [ ] UI-State in StateFlow auslagern

## Mittlere Priorität

- [x] **Unit Tests hinzufügen**
  - [x] RtCombiner testen (RtCombinerTest.kt)
  - [x] PresetRepository testen (PresetRepositoryTest.kt)
  - [ ] RadioScanner testen

- [x] **Error Handling verbessern**
  - [x] DeezerClient Netzwerk-Fehler besser abfangen (Retry-Logik, DeezerError sealed class)
  - [x] Fallbacks für fehlgeschlagene API-Calls

- [ ] **DAB+ UI verbessern**
  - Eigenes DAB+ Icon statt FM-Icon
  - DLS (Dynamic Label) in Now-Playing anzeigen

## Niedrige Priorität

- [x] **Dark Mode** implementieren (System/Hell/Dunkel in Settings)

- [x] **Hard-coded Strings** in strings.xml verschieben (EN + DE)

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
- [x] Dark Mode Support
- [x] Controller-Architektur (DabController, FmAmController, RadioController)
- [x] Unit Tests für Core-Komponenten
