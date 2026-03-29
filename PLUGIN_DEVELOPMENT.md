# fytFM Plugin Development Guide

## Overview

fytFM uses a plugin architecture for radio tuners. Each plugin represents a radio technology (FM, AM, DAB+, SDR, etc.) and can be loaded dynamically at runtime.

**Built-in plugins:** FYT FM, FYT AM, Hikity DAB+
**Custom plugins:** Can be imported as `.zip` files via the Plugin Manager in Settings.

## Architecture

```
Plugin (.zip)                    fytFM App
  plugin.properties               TunerPluginRegistry
  classes.dex                      TunerManager (user-created tuner instances)
  implements TunerPlugin           TunerHostApi (hardware access)
  uses TunerHostApi                PluginLoader (loads .zip plugins)
```

- A **Plugin** implements the `TunerPlugin` interface from the `:plugin-api` module
- The **App** provides hardware access via the `TunerHostApi` interface
- **Tuner instances** are user-created configurations that reference a plugin by `pluginId`
- Each tuner instance has its own station list and settings

## Creating a Plugin

### 1. Set up a project

Create a Kotlin/Android project and add the `:plugin-api` dependency:

```kotlin
dependencies {
    compileOnly("at.planqton.fytfm:plugin-api:1.0")
}
```

The `compileOnly` scope ensures the API classes are available at compile time but NOT bundled in your plugin (they come from the host app).

### 2. Implement `TunerPlugin`

```kotlin
package com.example.myplugin

import android.content.Context
import at.planqton.fytfm.plugin.api.*

class MySdrFmPlugin : TunerPlugin {

    // === Identity ===
    override val pluginId = "my_sdr_fm"           // unique ID
    override val type = TunerType.FM               // determines UI (FM/AM/DAB)
    override val displayName = "My SDR FM"         // shown in Plugin Manager
    override val hardwareGroup = HardwareGroup.FM_CHIP
    override val frequencyRange = 87.5f..108.0f    // null for DAB
    override val frequencyStep = 0.1f              // null for DAB

    private var hostApi: TunerHostApi? = null
    private var callback: TunerPluginCallback? = null
    private var currentFrequency = 98.4f
    override var isActive = false; private set
    override var isPoweredOn = false; private set
    override var isScanning = false; private set

    // === Initialization ===

    override fun initialize(hostApi: TunerHostApi) {
        // Called by the host after instantiation
        // Store the hostApi reference for hardware access
        this.hostApi = hostApi
    }

    override fun isHardwareAvailable(): Boolean {
        // Check if your hardware is connected
        // Example: check for a specific USB device
        return true
    }

    override fun setCallback(callback: TunerPluginCallback?) {
        this.callback = callback
    }

    // === Lifecycle ===

    override fun activate(context: Context, tunerId: String) {
        isActive = true
    }

    override fun deactivate() {
        isActive = false
    }

    // === Power ===

    override fun powerOn(): Boolean {
        // Initialize your hardware here
        isPoweredOn = true
        callback?.onPlaybackStateChanged(true)
        return true
    }

    override fun powerOff() {
        // Shut down your hardware
        isPoweredOn = false
        callback?.onPlaybackStateChanged(false)
    }

    // === Tuning (FM/AM only) ===

    override fun tune(frequency: Float) {
        currentFrequency = frequency
        // Tune your hardware to this frequency
    }

    override fun getCurrentFrequency(): Float = currentFrequency

    // === Audio ===

    override fun mute() {
        callback?.onPlaybackStateChanged(false)
    }

    override fun unmute() {
        callback?.onPlaybackStateChanged(true)
    }

    // === Seek ===

    override fun seekToStation(
        seekUp: Boolean,
        onProgress: ((Float) -> Unit)?,
        onResult: ((Float?) -> Unit)?
    ) {
        // Implement station seeking (or return null if not supported)
        onResult?.invoke(null)
    }

    // === Scanning ===

    override fun startScan(config: ScanConfig?) {
        isScanning = true
        callback?.onScanStarted()
        // Implement your scan logic
    }

    override fun stopScan() {
        isScanning = false
    }

    // === Settings ===

    override fun getSettings(): List<PluginSetting> = listOf(
        PluginSetting(
            key = "antenna_gain",
            label = "Antenna Gain",
            type = SettingType.TOGGLE,
            defaultValue = false
        ),
        PluginSetting(
            key = "gain",
            label = "Gain",
            type = SettingType.SLIDER,
            defaultValue = 25,
            min = 0,
            max = 50
        ),
        PluginSetting(
            key = "sample_rate",
            label = "Sample Rate",
            type = SettingType.DROPDOWN,
            defaultValue = "2.4 MHz",
            options = listOf("1.0 MHz", "2.0 MHz", "2.4 MHz", "3.2 MHz")
        )
    )

    override fun applySettings(settings: Map<String, Any>) {
        val antennaGain = settings["antenna_gain"] as? Boolean ?: false
        val gain = settings["gain"] as? Int ?: 25
        val sampleRate = settings["sample_rate"] as? String ?: "2.4 MHz"
        // Apply to your hardware
    }
}
```

### 3. Plugin Type (`TunerType`)

The `type` field determines which UI the app shows:

| TunerType | UI |
|-----------|-----|
| `FM` | Frequency scale (87.5-108 MHz), step buttons, seek, favorites |
| `AM` | Frequency scale (custom range), step buttons, favorites |
| `DAB` | Station list, no frequency dial, DAB scan dialog |

You set `type` based on what your plugin outputs, not what hardware it uses. An SDR plugin that demodulates FM would use `TunerType.FM`.

### 4. Settings (`PluginSetting`)

Plugins define their settings declaratively. The app renders the UI automatically.

**Available setting types:**

| SettingType | UI Widget | Value Type | Extra Fields |
|-------------|-----------|------------|--------------|
| `TOGGLE` | Switch | `Boolean` | - |
| `SLIDER` | SeekBar | `Int` | `min`, `max` |
| `DROPDOWN` | Spinner | `String` | `options: List<String>` |

Settings are stored per tuner instance - two tuners using the same plugin can have different settings.

### 5. Hardware Detection

```kotlin
override fun isHardwareAvailable(): Boolean {
    // Use hostApi to check, or implement your own detection
    // Examples:
    //   hostApi?.isFmChipAvailable()     // FYT FM chip
    //   hostApi?.isDabDeviceAvailable()   // DAB USB stick
    //   Check UsbManager for your device's vendor/product ID
    return true
}
```

### 6. TunerHostApi

The host provides these operations via `TunerHostApi`:

**Hardware Detection:**
- `isFmChipAvailable()` - FYT FM/AM chip loaded
- `isDabDeviceAvailable()` - DAB USB stick connected
- `isMcuAvailable()` - FYT MCU available

**FM/AM Chip:**
- `openDevice()`, `closeDevice()`, `powerUp(freq)`, `powerOff()`
- `tune(freq)`, `getSignalStrength()`, `setMute(mute)`
- `setLocalMode(local)`, `setMonoMode(mono)`, `setRadioArea(area)`

**RDS:**
- `enableRds()`, `clearRds()`
- `startRdsPolling(callback)`, `stopRdsPolling()`

**Audio Routing / MCU:**
- `initRadioSequence()`, `radioOn()`, `radioOff()`
- `setAudioSourceFm()`, `unmuteMcu()`, `muteAmplifier(mute)`

**DAB:**
- `initializeDab()`, `shutdownDab()`
- `startDabScan()`, `stopDabScan()`
- `startDabPlayback(serviceIndex)`, `stopDabPlayback()`
- `setDabCallback(callback)`

**System:**
- `sendBroadcast(action)`, `startFmService()`
- `setSystemProperty(key, value)`

Not all methods apply to every plugin. Use what you need.

### 7. Sending metadata to the app

Use the `TunerPluginCallback` to send data back:

```kotlin
// Station metadata (RDS, DAB Dynamic Label, etc.)
callback?.onMetadataUpdate(TunerMetadata(
    stationName = "Radio FM",
    radioText = "Now Playing: Artist - Title",
    frequency = 98.4f,
    rssi = 180,
    pi = 0x1234,
    isAM = false
))

// Playback state
callback?.onPlaybackStateChanged(true)

// Station list changes
callback?.onStationsChanged(listOf(
    RadioStation(frequency = 98.4f, name = "Radio FM"),
    RadioStation(frequency = 102.1f, name = "Jazz FM")
))

// Scan progress
callback?.onScanStarted()
callback?.onScanProgress(50)
callback?.onScanFinished(12)
```

## Packaging as .zip

### plugin.properties

Create a file `plugin.properties` in the root:

```properties
pluginId=my_sdr_fm
pluginClass=com.example.myplugin.MySdrFmPlugin
displayName=My SDR FM Plugin
```

### Build the .zip

The `.zip` must contain:
```
plugin.properties
classes.dex          <- your compiled plugin classes (DEX format)
```

To create `classes.dex` from your compiled `.class` files:

```bash
# Compile your Kotlin/Java code
kotlinc -cp plugin-api.jar MySdrFmPlugin.kt -d output/

# Convert to DEX
d8 output/*.class --output .

# Package
zip my_sdr_fm.zip plugin.properties classes.dex
```

### Install

1. Copy the `.zip` to the device
2. Open fytFM Settings -> Plugin Manager
3. Tap "Plugin importieren"
4. Select the `.zip` file
5. The plugin appears in the list and can be assigned to tuners
