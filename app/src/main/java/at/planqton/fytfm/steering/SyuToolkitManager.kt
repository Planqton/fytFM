package at.planqton.fytfm.steering

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.syu.ipc.IModuleCallback
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit

/**
 * Manager for connecting to the FYT syu service and receiving key events.
 * This is the correct way to receive steering wheel button events on FYT head units.
 *
 * Based on reverse engineering of NavRadio+ (com.navimods.radio_free) and com.syu.ms.
 *
 * Key findings:
 * - NavRadio+ uses Module ID 1 (com.syu.ms.radio)
 * - It registers for ALL event types 0-99 on that module
 * - Key events arrive with callback type = 60 (0x3C)
 * - Frequency updates arrive with callback type = 1
 * - FYT key codes are 0-6, NOT Android standard keycodes (87, 88, etc.)
 */
class SyuToolkitManager(
    private val context: Context,
    private val listener: KeyEventListener? = null
) {
    companion object {
        private const val TAG = "SyuToolkitManager"

        // Service connection info
        private const val SYU_MS_PACKAGE = "com.syu.ms"
        private const val TOOLKIT_SERVICE_CLASS = "app.ToolkitService"

        // Module IDs from com.syu.ms/ModuleService
        // NavRadio+ uses MODULE_ID_RADIO (1) for key events!
        private const val MODULE_ID_MAIN = 0     // com.syu.ms.main
        private const val MODULE_ID_RADIO = 1    // com.syu.ms.radio - NavRadio+ uses this!
        private const val MODULE_ID_BT = 2       // com.syu.ms.bt
        private const val MODULE_ID_DVD = 3      // com.syu.ms.dvd
        private const val MODULE_ID_SOUND = 4    // com.syu.ms.sound
        private const val MODULE_ID_IPOD = 5     // com.syu.ms.ipod
        private const val MODULE_ID_TV = 6       // com.syu.ms.tv
        private const val MODULE_ID_CANBUS = 7   // com.syu.ms.canbus
        private const val MODULE_ID_TPMS = 8     // com.syu.ms.tpms
        private const val MODULE_ID_DVR = 9      // com.syu.ms.dvr
        private const val MODULE_ID_STEER = 10   // com.syu.ms.steer

        // Callback event types (the 'type' parameter in update callback)
        private const val CALLBACK_TYPE_FREQUENCY = 1    // Frequency update
        private const val CALLBACK_TYPE_PRESET_FREQ = 4  // 0x04 - Preset frequencies (index, freq)
        private const val CALLBACK_TYPE_PRESET_NAME = 14 // 0x0E - Preset names (index in intData, name in stringData)
        private const val CALLBACK_TYPE_KEY_EVENT_FYT = 28  // 0x1C - Key events on FYT devices (NEXT/PREV)
        private const val CALLBACK_TYPE_KEY_EVENT_NAVRADIO = 60   // 0x3C - Key events (NavRadio+ style)
        private const val CALLBACK_TYPE_UNKNOWN_61 = 61  // 0x3D - Unknown

        // Preset index offset for AM (FM: 0-11, AM: 65536+)
        private const val AM_PRESET_OFFSET = 65536

        // Registration flags
        private const val REGISTER_FLAG_DEFAULT = 1

        // Max event types to register for (NavRadio+ registers 0-99)
        private const val MAX_EVENT_TYPES = 100

        // FYT-specific key codes (0-6 range, need to be discovered experimentally)
        // These are NOT Android standard keycodes!
        private const val FYT_KEY_0 = 0  // Possibly NEXT or MODE
        private const val FYT_KEY_1 = 1  // Possibly PREV
        private const val FYT_KEY_2 = 2  // Possibly VOL+
        private const val FYT_KEY_3 = 3  // Possibly VOL-
        private const val FYT_KEY_4 = 4  // Possibly MUTE
        private const val FYT_KEY_5 = 5  // Possibly ANSWER
        private const val FYT_KEY_6 = 6  // Possibly HANGUP
    }

    interface KeyEventListener {
        fun onNextPressed()
        fun onPrevPressed()
        fun onPlayPausePressed()
        fun onVolumeUp()
        fun onVolumeDown()
        fun onKeyEvent(keyCode: Int, intData: IntArray?)
        fun onFrequencyUpdate(frequencyKhz: Int)  // e.g., 10490 = 104.9 MHz
        fun onRawCallback(type: Int, intData: IntArray?, floatData: FloatArray?, stringData: Array<String>?)

        // Preset callbacks for station import
        fun onPresetReceived(index: Int, frequencyMhz: Float, isAM: Boolean) {}
        fun onPresetNameReceived(index: Int, name: String, isAM: Boolean) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val handlerThread = HandlerThread("SyuToolkitThread").apply { start() }
    private val serviceHandler = Handler(handlerThread.looper)

    private var toolkit: IRemoteToolkit? = null
    private var radioModule: IRemoteModule? = null
    private var isConnected = false
    private var isRegistered = false

    // Track registered event types for proper unregistration
    private val registeredEventTypes = mutableListOf<Int>()

    // Module callback to receive events
    private val moduleCallback = object : IModuleCallback.Stub() {
        override fun update(type: Int, intData: IntArray?, floatData: FloatArray?, stringData: Array<String>?) {
            Log.d(TAG, "=== Callback: type=$type (0x${type.toString(16)}), intData=${intData?.toList()}, floatData=${floatData?.toList()}, stringData=${stringData?.toList()} ===")

            // Always notify raw callback for debugging
            mainHandler.post {
                listener?.onRawCallback(type, intData, floatData, stringData)
            }

            when (type) {
                CALLBACK_TYPE_FREQUENCY -> {
                    // Type 1 = Frequency update
                    // Value is frequency in 10kHz units (e.g., 9880 = 98.8 MHz, 10490 = 104.9 MHz)
                    val value = intData?.firstOrNull() ?: return
                    if (value > 8700 && value < 10900) {
                        Log.i(TAG, "Frequency update: ${value / 100.0} MHz")
                        mainHandler.post {
                            listener?.onFrequencyUpdate(value)
                        }
                    }
                }

                CALLBACK_TYPE_PRESET_FREQ -> {
                    // Type 4 = Preset frequencies
                    // intData[0] = Preset index (0-11 for FM, 65536+ for AM)
                    // intData[1] = Frequency in 10kHz (e.g., 9040 = 90.4 MHz)
                    val presetIndex = intData?.getOrNull(0) ?: return
                    val frequency = intData.getOrNull(1) ?: 0
                    if (frequency > 0) {
                        val freqMhz = frequency / 100f
                        val isAM = presetIndex >= AM_PRESET_OFFSET
                        val actualIndex = if (isAM) presetIndex - AM_PRESET_OFFSET else presetIndex
                        Log.i(TAG, "Preset received: index=$actualIndex, freq=$freqMhz MHz, isAM=$isAM")
                        mainHandler.post {
                            listener?.onPresetReceived(actualIndex, freqMhz, isAM)
                        }
                    }
                }

                CALLBACK_TYPE_PRESET_NAME -> {
                    // Type 14 = Preset names
                    // intData[0] = Preset index
                    // stringData[0] = Station name
                    val presetIndex = intData?.getOrNull(0) ?: return
                    val name = stringData?.getOrNull(0)
                    if (!name.isNullOrBlank()) {
                        val isAM = presetIndex >= AM_PRESET_OFFSET
                        val actualIndex = if (isAM) presetIndex - AM_PRESET_OFFSET else presetIndex
                        Log.i(TAG, "Preset name received: index=$actualIndex, name='$name', isAM=$isAM")
                        mainHandler.post {
                            listener?.onPresetNameReceived(actualIndex, name, isAM)
                        }
                    }
                }

                CALLBACK_TYPE_KEY_EVENT_FYT -> {
                    // Type 28 (0x1C) = Key events on FYT devices
                    // intData[0]: 1 = NEXT, 0 = PREV (based on observation)
                    val keyCode = intData?.firstOrNull() ?: return
                    Log.i(TAG, "FYT KEY EVENT: keyCode=$keyCode (${if (keyCode == 1) "NEXT" else "PREV"})")
                    mainHandler.post {
                        when (keyCode) {
                            1 -> listener?.onNextPressed()
                            0 -> listener?.onPrevPressed()
                            else -> Log.d(TAG, "Unknown FYT keyCode: $keyCode")
                        }
                        listener?.onKeyEvent(keyCode, intData)
                    }
                }

                CALLBACK_TYPE_KEY_EVENT_NAVRADIO -> {
                    // Type 60 (0x3C) = Key events (NavRadio+ style, may not be used on all devices)
                    val keyCode = intData?.firstOrNull() ?: return
                    Log.i(TAG, "NAVRADIO KEY EVENT: keyCode=$keyCode")
                    mainHandler.post {
                        handleFytKeyCode(keyCode)
                        listener?.onKeyEvent(keyCode, intData)
                    }
                }

                else -> {
                    // Log unknown types for discovery
                    Log.d(TAG, "Unknown callback type: $type (0x${type.toString(16)})")
                }
            }
        }
    }

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Service connected: $name")

            if (service == null) {
                Log.e(TAG, "Service binder is null")
                return
            }

            serviceHandler.post {
                try {
                    // Get IRemoteToolkit from binder
                    val localInterface = service.queryLocalInterface("com.syu.ipc.IRemoteToolkit")
                    toolkit = if (localInterface is IRemoteToolkit) {
                        Log.d(TAG, "Got local IRemoteToolkit interface")
                        localInterface
                    } else {
                        Log.d(TAG, "Creating proxy for IRemoteToolkit")
                        IRemoteToolkit.Stub.asInterface(service)
                    }

                    isConnected = true
                    Log.i(TAG, "IRemoteToolkit obtained successfully")

                    // Register for events like NavRadio+ does
                    registerForAllEvents()

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize toolkit", e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected: $name")
            isConnected = false
            isRegistered = false
            toolkit = null
            radioModule = null
            registeredEventTypes.clear()

            // Try to reconnect after a delay
            serviceHandler.postDelayed({
                connectToService()
            }, 2000)
        }
    }

    /**
     * Register for all events like NavRadio+ does.
     * NavRadio+ uses Module ID 1 (radio) and registers for event types 0-99.
     */
    private fun registerForAllEvents() {
        try {
            // Get Radio module (module 1) - this is what NavRadio+ uses!
            radioModule = toolkit?.getRemoteModule(MODULE_ID_RADIO)

            if (radioModule == null) {
                Log.e(TAG, "Failed to get Radio module (ID=$MODULE_ID_RADIO)")
                return
            }

            Log.d(TAG, "Got Radio module, registering for ALL event types 0-${MAX_EVENT_TYPES - 1}...")

            // Register for ALL event types 0-99 like NavRadio+ does
            var successCount = 0
            for (eventType in 0 until MAX_EVENT_TYPES) {
                try {
                    radioModule?.register(moduleCallback, eventType, REGISTER_FLAG_DEFAULT)
                    registeredEventTypes.add(eventType)
                    successCount++
                } catch (e: Exception) {
                    // Some event types may not be supported, that's OK
                    Log.v(TAG, "Could not register for eventType=$eventType: ${e.message}")
                }
            }

            Log.i(TAG, "Registered for $successCount event types on Radio module")
            isRegistered = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register for events", e)
        }
    }

    /**
     * Handle FYT-specific key codes.
     * These are 0-6 range, NOT Android standard keycodes.
     * The exact mapping needs to be discovered experimentally on your device.
     */
    private fun handleFytKeyCode(keyCode: Int) {
        Log.d(TAG, "Handling FYT keyCode: $keyCode")

        // TODO: These mappings need to be verified on actual device!
        // Press each steering wheel button and check the logs to find the correct mapping.
        when (keyCode) {
            FYT_KEY_0 -> {
                Log.i(TAG, "FYT_KEY_0 pressed - assuming NEXT")
                listener?.onNextPressed()
            }
            FYT_KEY_1 -> {
                Log.i(TAG, "FYT_KEY_1 pressed - assuming PREV")
                listener?.onPrevPressed()
            }
            FYT_KEY_2 -> {
                Log.i(TAG, "FYT_KEY_2 pressed - assuming VOL+")
                listener?.onVolumeUp()
            }
            FYT_KEY_3 -> {
                Log.i(TAG, "FYT_KEY_3 pressed - assuming VOL-")
                listener?.onVolumeDown()
            }
            FYT_KEY_4 -> {
                Log.i(TAG, "FYT_KEY_4 pressed - assuming PLAY/PAUSE")
                listener?.onPlayPausePressed()
            }
            FYT_KEY_5, FYT_KEY_6 -> {
                Log.i(TAG, "FYT_KEY_$keyCode pressed - unknown function")
            }
            else -> {
                Log.d(TAG, "Unknown FYT keyCode: $keyCode")
            }
        }
    }

    /**
     * Connect to the syu toolkit service.
     */
    fun connect() {
        if (isConnected) {
            Log.d(TAG, "Already connected")
            return
        }

        connectToService()
    }

    private fun connectToService() {
        try {
            val intent = Intent().apply {
                setClassName(SYU_MS_PACKAGE, TOOLKIT_SERVICE_CLASS)
            }

            Log.d(TAG, "Binding to $SYU_MS_PACKAGE/$TOOLKIT_SERVICE_CLASS...")

            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            if (!bound) {
                Log.e(TAG, "Failed to bind to service")

                // Retry after delay
                serviceHandler.postDelayed({
                    connectToService()
                }, 3000)
            } else {
                Log.d(TAG, "Bind request sent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error binding to service", e)
        }
    }

    /**
     * Disconnect from the service.
     */
    fun disconnect() {
        try {
            // Unregister from all registered event types
            if (isRegistered && radioModule != null) {
                for (eventType in registeredEventTypes) {
                    try {
                        radioModule?.unregister(moduleCallback, eventType)
                    } catch (e: Exception) {
                        Log.v(TAG, "Could not unregister eventType=$eventType")
                    }
                }
                registeredEventTypes.clear()
                Log.d(TAG, "Unregistered from all event types")
            }

            if (isConnected) {
                context.unbindService(serviceConnection)
                Log.d(TAG, "Unbound from service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }

        isConnected = false
        isRegistered = false
        toolkit = null
        radioModule = null
    }

    /**
     * Check if connected to the service.
     */
    fun isServiceConnected(): Boolean = isConnected

    /**
     * Check if registered for key events.
     */
    fun isKeyEventsRegistered(): Boolean = isRegistered

    /**
     * Call a module method (for testing/debugging).
     */
    fun callModule(moduleId: Int, type: Int, intData: IntArray?, floatData: FloatArray?, stringData: Array<String>?) {
        try {
            val module = toolkit?.getRemoteModule(moduleId)
            module?.call(type, intData, floatData, stringData)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling module", e)
        }
    }

    /**
     * Get data from a module (for testing/debugging).
     */
    fun getModuleData(moduleId: Int, type: Int, intData: IntArray?, floatData: FloatArray?, stringData: Array<String>?): Any? {
        return try {
            val module = toolkit?.getRemoteModule(moduleId)
            module?.get(type, intData, floatData, stringData)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting module data", e)
            null
        }
    }
}
