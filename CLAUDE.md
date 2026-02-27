# fytFM - Claude Code Notizen

**WICHTIG: App immer mit `/upload/bai.sh` starten!**

## Build & Deploy

**Immer `bai.sh` verwenden:**
```bash
/home/planqton/Schreibtisch/fytFM/upload/bai.sh
```

Das Script macht:
1. Build (assembleRelease)
2. Sign mit Platform-Key
3. ADB Install
4. App starten

## Signing

- **Keystore:** `upload/platform.jks`
- **Alias:** `android`
- **Passwort:** `android`

## Debugging

- Debug-Funktionen einbauen um selbstständig zu testen
- Selbstständig installieren und prüfen ob Funktionen gehen
- Bei Crashes: Logcat checken mit `adb logcat -s fytFM` oder `adb logcat | grep -E "(fytFM|AndroidRuntime)"`
- 
## ADB Befehle

```bash
# Logcat filtern
adb logcat -s FytFMMediaService:* FmRadioPlayer:* MainActivity:*

# App starten
- Checke dabei immer ob die app crasht
adb shell am start -n at.planqton.fytfm/.MainActivity

# App stoppen
adb shell am force-stop at.planqton.fytfm

# Debug Intents
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es command "status"
```

## Projekt-Struktur

- `app/src/main/java/at/planqton/fytfm/` - Kotlin/Java Quellcode
- `app/src/main/res/` - Ressourcen (Layouts, Strings, etc.)
- `upload/` - Build-Scripts und Signing-Keys
