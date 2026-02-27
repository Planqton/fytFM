# fytFM - Advanced FM/AM Radio for Android

A feature-rich FM/AM radio application for Android head units, specifically designed for FYT-based car infotainment systems. Combines native FM radio hardware control with modern features like Spotify track identification, album art display, and Android Auto integration.

![Platform](https://img.shields.io/badge/Platform-Android%2010+-brightgreen)
![License](https://img.shields.io/badge/License-Private-blue)
![Version](https://img.shields.io/badge/Version-1.4.0-orange)

---

## Features

### Radio Core
- **FM Radio** (87.5 - 108.0 MHz, 0.1 MHz steps)
- **AM Radio** (522 - 1620 kHz, 9 kHz steps)
- **Station Management** - Save, organize, and favorite stations
- **Smart Scanning** - RSSI-based detection with optional RDS verification
- **Region Support** - Configurable radio areas (Europe, USA, Japan, etc.)
- **LOC/DX Mode** - Adjustable sensitivity for weak stations
- **Mono/Stereo** - Manual audio mode switching

### RDS (Radio Data System)
- **PS** - Program Service (station name)
- **RT** - Radio Text (current song/info)
- **PI** - Program Identification code
- **PTY** - Program Type
- **AF** - Alternative Frequencies
- **TP/TA** - Traffic Program/Announcement
- **RSSI** - Signal strength monitoring
- **RDS Logging** - Complete history database

### Spotify Integration
- **Automatic Track Identification** - Searches Spotify using Radio Text
- **Album Art Display** - High-quality cover images
- **Smart Caching** - Offline fallback with local database
- **RT Fragment Handling** - Combines split messages intelligently
- **EditStrings** - Custom text transformations before search
- **Corrections System** - Mark false results, skip specific tracks
- **Cache Export/Import** - Backup your track database

### Radio Logos
- **Template System** - Region-specific logo sets
- **Multi-Level Matching**:
  1. PI Code (most accurate)
  2. PS Name
  3. Frequency-based
- **Auto-Download** - Logos cached locally
- **Custom Templates** - Create your own logo sets

### User Interface
- **Equalizer Mode** - Classic frequency scale view
- **Carousel Mode** - Large cover art cards
- **Now Playing Bar** - Artist, title & cover display
- **Picture-in-Picture** - Compact floating view
- **Debug Overlays** - RDS, Spotify, layout info
- **Dark/Light Theme** - Material Design

### Android Auto / Car Launcher
- **MediaSession Support** - Full car launcher integration
- **Station Browsing** - Navigate FM/AM stations
- **Playback Controls** - Play, pause, next, previous
- **Metadata Display** - Cover art in car display
- **HTTP Cover Server** - Local image serving for compatibility

### Additional Features
- **Auto-Update** - Check GitHub for new releases
- **Bug Reporting** - Capture app state & logs
- **Multi-Language** - Locale support

---

## Screenshots

| Equalizer Mode | Carousel Mode | Settings |
|:-:|:-:|:-:|
| *Classic view* | *Cover art view* | *Configuration* |

---

## Requirements

### Hardware
- FYT-based Android head unit (UIS7862, UIS7870, etc.)
- Built-in FM radio hardware with `libfmjni.so` support

### Software
- Android 10+ (API 29+)
- Target: Android 15 (API 36)

---

## Installation

### Quick Install
```bash
# Build, sign and install via ADB
./upload/bai.sh
```

### Manual Build
```bash
# Build release APK
./gradlew assembleRelease

# Sign with platform key
apksigner sign --ks upload/platform.jks \
  --ks-key-alias android \
  --ks-pass pass:android \
  app/build/outputs/apk/release/app-release-unsigned.apk

# Install
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Configuration

### Spotify Setup
1. Create a [Spotify Developer App](https://developer.spotify.com/dashboard)
2. Open fytFM Settings
3. Enter Client ID and Client Secret
4. Enable Spotify integration

### Radio Logos
1. Settings → Logo Templates
2. Select your region template
3. Download logos (automatic)
4. Logos appear in station list & Now Playing

### Radio Area
Configure for your region:
- **0** - Europe (87.5-108, 50kHz)
- **1** - Japan (76-90, 100kHz)
- **2** - World (87.5-108, 100kHz)
- **3** - USA (87.5-108, 200kHz)

---

## Architecture

```
fytFM/
├── app/src/main/java/at/planqton/fytfm/
│   ├── MainActivity.kt          # Main UI & orchestration
│   ├── RdsManager.java          # RDS polling & JNI commands
│   ├── FrequencyScaleView.kt    # Custom frequency widget
│   │
│   ├── media/
│   │   ├── FytFMMediaService.kt # MediaSession for Car Launcher
│   │   ├── FmRadioPlayer.kt     # Media3 player implementation
│   │   └── CoverHttpServer.kt   # NanoHTTPD cover server
│   │
│   ├── data/
│   │   ├── PresetRepository.kt  # Station persistence
│   │   ├── rdslog/              # RDS database (Room)
│   │   └── logo/                # Logo template system
│   │
│   ├── spotify/
│   │   ├── SpotifyClient.kt     # API client
│   │   ├── RtCombiner.kt        # RT buffering & search
│   │   └── SpotifyCache.kt      # SQLite track cache
│   │
│   ├── scanner/
│   │   └── RadioScanner.kt      # Frequency scanning
│   │
│   └── ui/
│       ├── StationAdapter.kt    # Station list
│       └── StationCarouselAdapter.kt  # Carousel view
│
├── app/src/main/java/com/android/fmradio/
│   └── FmNative.java            # JNI wrapper for libfmjni.so
│
└── upload/
    ├── bai.sh                   # Build & install script
    └── platform.jks             # Signing keystore
```

---

## Data Storage

| Data | Location | Format |
|------|----------|--------|
| FM/AM Presets | SharedPreferences | JSON |
| RDS History | Room Database | SQLite |
| Spotify Cache | SQLiteOpenHelper | SQLite |
| Station Logos | Internal Storage | PNG/WEBP |
| Spotify Covers | Internal Storage | JPEG |
| Bug Reports | Internal Storage | JSON |

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Kotlin | 2.0.21 | Language |
| Coroutines | 1.7.3 | Async operations |
| Room | 2.6.1 | RDS database |
| Media3 | 1.5.1 | Car Launcher support |
| OkHttp | 4.12.0 | Spotify API |
| Coil | 2.7.0 | Image loading |
| NanoHTTPD | 2.3.1 | Cover HTTP server |
| Material | 1.13.0 | UI components |

---

## ADB Commands

```bash
# View logs
adb logcat -s fytFM:* FytFMMediaService:* RdsManager:*

# Start app
adb shell am start -n at.planqton.fytfm/.MainActivity

# Force stop
adb shell am force-stop at.planqton.fytfm

# Send debug command
adb shell am broadcast -a at.planqton.fytfm.DEBUG --es command "status"
```

---

## Hardware Integration

### JNI Commands (FmNative)
```java
openDev() / closeDev()      // Device control
powerUp(freq) / powerDown() // Power management
tune(freq)                  // Frequency tuning
seek(freq, isUp)            // Auto-seek
getPs() / getLrText()       // RDS data
setRds(enable) / readRds()  // RDS control
setMute(mute)               // Audio mute
fmsyu_jni(cmd, in, out)     // Extended commands
```

### Extended Command Codes
| Code | Function |
|------|----------|
| 0x0b | Get RSSI |
| 0x0e | Set sensitivity (LOC) |
| 0x14 | Set radio area |
| 0x15 | RDS on/off |
| 0x18 | RDS AF config |
| 0x1e | Get PS |
| 0x1f | Get RT |
| 0x20 | Get PI code |

---

## Troubleshooting

### Radio not working
1. Check if `libfmjni.so` exists on device
2. Verify app has system permissions
3. Check logcat for JNI errors

### Spotify not finding tracks
1. Verify Client ID/Secret
2. Check internet connection
3. Try EditStrings to clean RT text
4. Check Corrections for ignored tracks

### No album art in car launcher
1. Enable HTTP Cover Server in settings
2. Check port 8765 is accessible
3. Verify MediaSession is active

### Logos not showing
1. Download template in settings
2. Check matching mode (PI/PS/Freq)
3. Verify logo files exist in storage

---

## Building

### Requirements
- Android Studio Hedgehog+
- JDK 11+
- Android SDK 36

### Build Variants
- **Debug**: Development with logging
- **Release**: Production build (requires signing)

### Signing
Platform key required for system-level features:
```
Keystore: upload/platform.jks
Alias: android
Password: android
```

---

## License

Private software. All rights reserved.

---

## Credits

- **Developer**: planqton
- **Hardware**: FYT Android head units
- **APIs**: Spotify Web API
- **Icons**: Material Design Icons

---

## Changelog

### v1.4.0
- Carousel view mode with cover art cards
- Now Playing bar in carousel mode
- View mode persistence
- Improved station selection on startup
- Favorite icon fix in carousel

### v1.3.0
- Radio logo template system
- Multi-region support
- Logo download & caching

### v1.2.0
- Spotify integration
- RT fragment combining
- Track caching system

### v1.1.0
- MediaSession support
- Android Auto integration
- HTTP cover server

### v1.0.0
- Initial release
- FM/AM radio control
- RDS support
- Station management
