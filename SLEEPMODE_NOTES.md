# SleepMode - Notizen für spätere Fehlerbehebung

## Aktueller Stand (funktioniert)
- `android:persistent="true"` im FytFMMediaService (Manifest)
- Radio wird in `onDestroy()` NICHT mehr ausgeschaltet
- Radio läuft im Sleep weiter

## Was geändert wurde

### AndroidManifest.xml (Zeile ~127)
```xml
<service
    android:name=".media.FytFMMediaService"
    android:foregroundServiceType="mediaPlayback"
    android:persistent="true"   <!-- HINZUGEFÜGT -->
    ...
```

### MainActivity.kt (onDestroy ~Zeile 5420)
```kotlin
// Radio NICHT ausschalten - läuft im MediaService weiter (auch im Sleep)
// fmNative.powerOff() wird nur vom User manuell ausgelöst
```

## Falls Probleme auftreten - mögliche Lösungen

### 1. WakeLock hinzufügen (falls CPU trotzdem schläft)
```kotlin
// In FytFMMediaService.kt
private var wakeLock: PowerManager.WakeLock? = null

override fun onCreate() {
    val pm = getSystemService(POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fytFM::Radio")
    wakeLock?.acquire()
}

override fun onDestroy() {
    wakeLock?.release()
}
```
Plus Permission in Manifest: `<uses-permission android:name="android.permission.WAKE_LOCK"/>`

### 2. START_STICKY (falls Service gekillt wird)
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // ...
    return START_STICKY
}
```

### 3. Audio Focus Recovery
Falls Audio nach Sleep unterbrochen wird - AudioManager.requestAudioFocus() erneut aufrufen.

## NavRadio+ Referenz
- Manifest: `/home/planqton/Schreibtisch/apktool/NavRadio+/AndroidManifest.xml`
- RadioService: `/home/planqton/Schreibtisch/apktool/NavRadio+/smali/com/navimods/radio/RadioService.smali`
- Nutzt `android:persistent="true"` (Zeile 40)
- Hat WAKE_LOCK Permission (Zeile 16)
