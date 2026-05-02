# fytFM

FM/AM radio app for FYT-based Android head units (UI7862, UI7870 and compatible). Replaces the stock radio app and adds DAB+ support via USB stick.

## What it does

- FM and AM tuning through the head unit's built-in radio chip (`libfmjni.so`)
- DAB+ via USB DAB+ dongles (VID `0x16C0` / PID `0x05DC`, e.g. XTRONS / Joying / Pumpkin), driven through the OMRI Radio API
- RDS (PS, RT, PI, PTY, TP/TA, AF) and DAB+ DLS / slideshow / EPG
- Optional cover art and track metadata via Deezer (looked up from RT/DLS)
- Station scan, favorites, custom names, region presets (Europe / USA-Korea / LatAm / Russia / Japan)
- Station logos (template packs, matched by PI / PS / frequency)
- Two main views: classic frequency scale, or a station carousel
- Steering-wheel buttons via the FYT `com.syu.radio` broadcast and standard MediaButton intents
- Android Auto / Car Launcher integration through Media3 `MediaLibraryService`
- DAB+ recording to MP3 (LAME)

## Requirements

- Android 10+
- FYT head unit (UI7862 / UI7870 or compatible Allwinner UIS7870 / DUDU7) for FM/AM
- USB DAB+ stick for DAB+ (optional)
- Internet for Deezer covers, logos and update checks

Some variants (e.g. DUDU7) need root for the `sqlfmservice` fallback.

## Install

Grab the latest APK from [Releases](../../releases) and sideload it on the head unit. On first start, pick a region and run a station scan. Connect the DAB+ stick if you have one.

## Build

```bash
./gradlew :app:assembleDebug
```

Requires JDK 17 and Android SDK 36. Release builds need a signing config; CI builds tagged versions automatically (see `.github/workflows/`).

## License

Private project. All rights reserved.
