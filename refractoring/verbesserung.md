# FytFM - Architektur-Verbesserungsplan

## Aktuelle Probleme

### 1. MainActivity "God Class" (10.761 Zeilen)
- Enthält UI-Logik, Business-Logik, Navigation, Dialoge
- Schwer zu testen, warten und erweitern
- Verletzt Single Responsibility Principle

### 2. Fehlende Architektur-Schicht
- Kein MVVM/MVI Pattern
- Direkte UI-Manipulation statt reaktiver State
- Tight Coupling zwischen Komponenten

### 3. Inline Dialoge
- Settings-Dialog: ~600 Zeilen direkt in MainActivity
- Scan-Dialog, Editor-Dialog etc. alle inline
- Code-Duplikation bei ähnlichen Dialogen

---

## Phase 1: ViewModels einführen (Priorität: Hoch)

### 1.1 RadioViewModel erstellen
```kotlin
// app/src/main/java/at/planqton/fytfm/viewmodel/RadioViewModel.kt
class RadioViewModel(
    private val presetRepository: PresetRepository,
    private val radioController: RadioController
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    // Events
    private val _events = Channel<RadioEvent>()
    val events = _events.receiveAsFlow()

    fun onFrequencyChange(freq: Int) { ... }
    fun onModeChange(mode: RadioMode) { ... }
    fun onStationSelect(station: RadioStation) { ... }
}
```

### 1.2 RadioUiState definieren
```kotlin
// app/src/main/java/at/planqton/fytfm/viewmodel/RadioUiState.kt
data class RadioUiState(
    val currentFrequency: Int = 8750,
    val currentMode: RadioMode = RadioMode.FM,
    val isPlaying: Boolean = false,
    val currentStation: RadioStation? = null,
    val rdsData: RdsData? = null,
    val signalStrength: Int = 0,
    val favorites: List<RadioStation> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
```

### 1.3 SettingsViewModel erstellen
```kotlin
// app/src/main/java/at/planqton/fytfm/viewmodel/SettingsViewModel.kt
class SettingsViewModel(
    private val presetRepository: PresetRepository
) : ViewModel() {

    val darkMode: StateFlow<Int> = presetRepository.darkModeFlow
    val autoplay: StateFlow<Boolean> = presetRepository.autoplayFlow
    // ... weitere Settings als Flows

    fun setDarkMode(mode: Int) { ... }
    fun setAutoplay(enabled: Boolean) { ... }
}
```

**Aufwand:** ~2-3 Tage
**Dateien:** 4-5 neue Kotlin-Dateien

---

## Phase 2: Dialoge extrahieren (Priorität: Hoch)

### 2.1 SettingsDialogFragment erstellen
```kotlin
// app/src/main/java/at/planqton/fytfm/ui/settings/SettingsDialogFragment.kt
class SettingsDialogFragment : DialogFragment() {

    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var binding: DialogSettingsBinding

    override fun onCreateView(...): View {
        binding = DialogSettingsBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(...) {
        setupSearch()
        setupFmSettings()
        setupDabSettings()
        setupGeneralSettings()
        observeViewModel()
    }
}
```

### 2.2 Weitere Dialoge extrahieren
| Dialog | Neue Datei | Zeilen aus MainActivity |
|--------|-----------|------------------------|
| Settings | `SettingsDialogFragment.kt` | ~600 |
| Radio Editor | `RadioEditorDialogFragment.kt` | ~400 |
| DAB Scan | `DabScanDialogFragment.kt` | ~200 |
| Logo Template | `LogoTemplateDialogFragment.kt` | ~150 |
| Corrections Viewer | `CorrectionsDialogFragment.kt` | ~100 |

**Aufwand:** ~3-4 Tage
**Ersparnis:** ~1.500 Zeilen aus MainActivity

---

## Phase 3: UI State Management (Priorität: Mittel)

### 3.1 StateFlow für alle UI-Zustände
```kotlin
// Statt:
private var isRadioOn = false
private var currentFrequency = 8750

// Besser:
private val _radioState = MutableStateFlow(RadioState())
val radioState: StateFlow<RadioState> = _radioState.asStateFlow()
```

### 3.2 Collect in Activity
```kotlin
// MainActivity wird dünn:
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    lifecycleScope.launch {
        viewModel.uiState.collect { state ->
            updateUI(state)
        }
    }
}

private fun updateUI(state: RadioUiState) {
    binding.frequencyText.text = formatFrequency(state.currentFrequency)
    binding.stationName.text = state.currentStation?.name ?: ""
    // ...
}
```

**Aufwand:** ~2 Tage

---

## Phase 4: Dependency Injection (Priorität: Mittel)

### 4.1 Hilt einführen
```kotlin
// build.gradle
implementation "com.google.dagger:hilt-android:2.48"
kapt "com.google.dagger:hilt-compiler:2.48"

// Application
@HiltAndroidApp
class FytFMApplication : Application()

// Module
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun providePresetRepository(@ApplicationContext context: Context): PresetRepository {
        return PresetRepository(context)
    }
}
```

### 4.2 ViewModels mit Hilt
```kotlin
@HiltViewModel
class RadioViewModel @Inject constructor(
    private val presetRepository: PresetRepository,
    private val radioController: RadioController
) : ViewModel()
```

**Aufwand:** ~1-2 Tage

---

## Phase 5: Testing verbessern (Priorität: Niedrig)

### 5.1 ViewModel Tests
```kotlin
@Test
fun `frequency change updates state`() = runTest {
    val viewModel = RadioViewModel(mockRepository, mockController)

    viewModel.onFrequencyChange(10000)

    assertEquals(10000, viewModel.uiState.value.currentFrequency)
}
```

### 5.2 Repository Tests erweitern
- PresetRepository: SharedPreferences mocken
- RadioController: Hardware-Abstraction testen

**Aufwand:** ~2 Tage

---

## Ziel-Architektur

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │
│  │ MainActivity│  │DialogFragm.│  │ Custom Views    │  │
│  │   (dünn)    │  │ (Settings..)│  │ (FrequencyScale)│  │
│  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘  │
└─────────┼────────────────┼──────────────────┼───────────┘
          │                │                  │
          ▼                ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                   ViewModel Layer                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │
│  │RadioViewModel│ │SettingsVM  │  │  ScanViewModel  │  │
│  │  (StateFlow) │  │            │  │                 │  │
│  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘  │
└─────────┼────────────────┼──────────────────┼───────────┘
          │                │                  │
          ▼                ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                  Domain/Controller Layer                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │
│  │RadioControll│  │DabController│  │ FmAmController  │  │
│  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘  │
└─────────┼────────────────┼──────────────────┼───────────┘
          │                │                  │
          ▼                ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                    Data Layer                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │
│  │PresetRepo   │  │RdsLogRepo   │  │  DeezerClient   │  │
│  └─────────────┘  └─────────────┘  └─────────────────┘  │
│  ┌─────────────┐  ┌─────────────┐                       │
│  │RadioLogoRepo│  │UpdateRepo   │                       │
│  └─────────────┘  └─────────────┘                       │
└─────────────────────────────────────────────────────────┘
```

---

## Reihenfolge der Umsetzung

| Phase | Beschreibung | Aufwand | Priorität |
|-------|--------------|---------|-----------|
| 1 | ViewModels einführen | 2-3 Tage | Hoch |
| 2 | Dialoge extrahieren | 3-4 Tage | Hoch |
| 3 | StateFlow für UI | 2 Tage | Mittel |
| 4 | Hilt DI | 1-2 Tage | Mittel |
| 5 | Tests erweitern | 2 Tage | Niedrig |

**Gesamtaufwand:** ~10-13 Tage

---

## Quick Wins (sofort umsetzbar)

1. **SettingsDialogFragment** - größter Einzelgewinn (~600 Zeilen)
2. **RadioUiState** - einfache Datenklasse, verbessert Lesbarkeit
3. **Event-basierte Navigation** - statt direkter Dialog-Aufrufe

---

## Metriken nach Refactoring

| Metrik | Vorher | Nachher (Ziel) |
|--------|--------|----------------|
| MainActivity Zeilen | 10.761 | ~2.000 |
| Testabdeckung | ~10% | ~40% |
| Cyclomatic Complexity | Hoch | Mittel |
| Coupling | Tight | Loose |
