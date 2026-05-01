# fytFM

Advanced FM / AM / DAB+ radio for Android car head units, built for FYT-based infotainment systems. Replaces the stock radio app with native tuner control, full RDS handling, USB DAB+ support, online cover art via Deezer, and a Material UI tuned for in-car use.

![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-brightgreen)
![Target](https://img.shields.io/badge/Target-FYT%20Head%20Units-blue)
![Version](https://img.shields.io/badge/Version-1.5.4-orange)

---

## What it is

fytFM talks directly to the radio hardware on FYT Android head units (and compatible variants such as UIS7870 / DUDU7) and gives you a modern radio experience on top of it:

- A native FM/AM tuner with real RDS, signal strength and station scanning
- A DAB+ tuner driven by a USB dongle, with EPG, slideshow and recording
- Online metadata (artist, title, album cover) pulled from Deezer using Radio Text / DLS
- A car-friendly UI with several view modes, Picture-in-Picture, and full Android Auto / Car Launcher integration
- OEM steering-wheel button support, including a popup overlay on station change

It runs as a normal app but uses platform-level interfaces (JNI, system services, root fallback when needed) to drive the tuner that the OEM radio app would normally control.

---

## Features

### FM / AM radio

- FM 87.5 – 108.0 MHz, AM 522 – 1620 kHz with configurable step sizes
- Region presets: Europe, USA / Korea, Latin America, Russia, Japan
- LOC / DX sensitivity for weak stations
- Mono / stereo with noise reduction switch
- Station scan with RSSI threshold and optional RDS verification
- Quick scan and full scan modes; rescan keeps or overwrites existing favorites
- Manual fine-tuning, seek, and direct frequency entry
- Per-station favorites, custom names, drag-to-reorder

### DAB+ (USB)

- Works with XTRONS / Joying / Pumpkin USB DAB+ dongles (VID 0x16C0 / PID 0x05DC)
- Built on the OMRI Radio API plus native `libirtdab.so` / `libfec.so`
- Full ensemble scan, station list with bitrate / signal info
- DLS (Dynamic Label) parsing including segmented messages
- MOT slideshow display
- EPG ("now / up next") view per station
- DAB+ recording to MP3 via the bundled LAME encoder
- Mock backend for development without hardware

### RDS

- PS (program service), RT (radio text), PI, PTY, TP / TA, AF list
- Live RSSI / signal-quality readout
- RT fragment combiner: stitches split radio-text messages back together intelligently
- RDS archive: every change is logged to a Room database with a configurable retention window
- Searchable archive view with per-station filter
- RT correction rules — user-defined text transformations applied before searching online metadata

### Deezer integration (cover art / metadata)

- Automatic track lookup for the currently playing song using Radio Text / DLS
- High-resolution album cover shown in the now-playing UI and on the car display
- Local cache (Room) so covers stay available offline and across reboots
- Multiple search strategies, classical-music normalization, query builder
- Corrections system to flag wrong matches and skip specific tracks
- Cache export / import and a dedicated "Deezer Bug Report" capture for diagnosing misses
- Can be toggled per band; "Deezer / Local blocked" mode falls back to RDS only

### Radio logos

- Template-based logo packs, region-specific
- Multi-level matching: PI code → PS name → frequency
- Auto-download and local caching
- Logo Template Manager for installing, switching and creating custom packs
- Optional logos in the favorites list

### User interface

- **Equalizer mode** — classic horizontal frequency scale
- **Carousel mode** — large cover-art cards, swipe between favorites
- **Now Playing bar** — artist, title and cover always visible
- **Picture-in-Picture** — compact floating view when the app goes to background
- **Station-change overlay** — full-screen popup briefly shown when steering-wheel buttons skip stations
- Light / dark theme with customizable accent colors
- Landscape-locked, designed for 1024×600 / 1280×720 / 1920×720 head-unit displays
- Debug overlays for RDS, Deezer and layout work

### Android Auto / Car Launcher

- Media3 `MediaLibraryService` so the head unit's media widget shows the current station and cover
- Browseable station tree (FM / AM / DAB+, favorites)
- Full transport controls: play / pause, next / previous station, skip
- Cover art delivered via a local HTTP server when the launcher refuses content URIs

### Steering wheel buttons

- Catches the FYT `com.syu.radio` broadcast that the stock radio app would normally consume
- Custom `MediaButton` receiver covering FYT and BYD intents
- Optional popup overlay on each station change so the driver gets visual feedback
- Works alongside MediaSession key events

### Updates & diagnostics

- Built-in updater that checks GitHub Releases and downloads / installs the new APK
- In-app bug reporter that captures app state, current RDS / Deezer data and logs
- Crash handler with persistent crash reports
- ADB debug receiver for scripted control (`am broadcast -a at.planqton.fytfm.DEBUG …`)

### Localization

- System default, English and German UI

---

## Requirements

- Android 10 (API 29) or newer
- FYT-based head unit (or compatible Allwinner UIS7870 / DUDU7) for FM / AM
- For DAB+: a supported USB DAB+ dongle (VID 0x16C0 / PID 0x05DC)
- Platform signing or root access on locked-down variants (DUDU7 needs the root fallback to talk to `sqlfmservice`)
- Internet connection for Deezer metadata, logos and updates

---

## Installation

1. Download the latest `app-release.apk` from the [Releases](../../releases) page
2. Install it on the head unit (sideload or via the auto-updater of an older fytFM build)
3. On first launch: pick a region, run a station scan, and optionally connect a DAB+ dongle

The app declares `MANAGE_EXTERNAL_STORAGE`, `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW` and `RECEIVE_BOOT_COMPLETED`. Grant the prompts so DAB+ recording, the station-change overlay and autostart work.

---

## Building from source

Requirements:

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 36

```bash
./gradlew :app:assembleDebug      # debug build
./gradlew :app:assembleRelease    # release build (requires signing config)
```

GitHub Actions builds and publishes a release APK automatically on every `v*` tag (`.github/workflows/`).

Tech stack:

| Component | Purpose |
|-----------|---------|
| Kotlin + Coroutines | Language and async |
| Room | RDS archive, Deezer cache, settings |
| Media3 (Session + ExoPlayer) | Car Launcher / Android Auto |
| OkHttp | Deezer API, GitHub release checks, logo downloads |
| Coil | Image loading |
| LAME (TAndroidLame) | DAB+ MP3 recording |
| OMRI Radio API + `libirtdab.so` | DAB+ tuner |
| `libfmjni.so` (FmNative) | FM / AM tuner |

---

## License

Private project. All rights reserved.
