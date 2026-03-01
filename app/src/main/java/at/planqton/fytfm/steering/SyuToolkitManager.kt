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
 * Based on reverse engineering of navradio (com.navimods.radio_free).
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

        // Module IDs
        private const val MODULE_ID_MS = 0  // com.syu.ms module
        private const val MODULE_ID_SS = 1  // com.syu.ss module (if needed)

        // Event types for registration
        private const val EVENT_TYPE_KEY = 0x22  // Key events (34 decimal)
        private const val EVENT_TYPE_RADIO = 0x100  // Radio events (possible)

        // Registration flags
        private const val REGISTER_FLAG_DEFAULT = 1

        // Key codes from FYT system
        private const val KEYCODE_MEDIA_NEXT = 87
        private const val KEYCODE_MEDIA_PREVIOUS = 88
        private const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        private const val KEYCODE_MEDIA_PLAY = 126
        private const val KEYCODE_MEDIA_PAUSE = 127
        private const val KEYCODE_VOLUME_UP = 24
        private const val KEYCODE_VOLUME_DOWN = 25
    }

    interface KeyEventListener {
        fun onNextPressed()
        fun onPrevPressed()
        fun onPlayPausePressed()
        fun onVolumeUp()
        fun onVolumeDown()
        fun onKeyEvent(keyCode: Int, intData: IntArray?)
        fun onFrequencyUpdate(frequencyKhz: Int)  // e.g., 10490 = 104.9 MHz
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val handlerThread = HandlerThread("SyuToolkitThread").apply { start() }
    private val serviceHandler = Handler(handlerThread.looper)

    private var toolkit: IRemoteToolkit? = null
    private var msModule: IRemoteModule? = null
    private var ssModule: IRemoteModule? = null
    private var isConnected = false
    private var isRegistered = false

    // Module callback to receive events
    private val moduleCallback = object : IModuleCallback.Stub() {
        override fun update(type: Int, intData: IntArray?, floatData: FloatArray?, stringData: Array<String>?) {
            Log.d(TAG, "=== update: type=$type, intData=${intData?.toList()}, floatData=${floatData?.toList()}, stringData=${stringData?.toList()} ===")

            val value = intData?.firstOrNull() ?: return

            // Type 1 = Frequency update from com.syu.music
            // Value is frequency in 10kHz units (e.g., 9880 = 98.8 MHz, 10490 = 104.9 MHz)
            if (type == 1 && value > 8700 && value < 10900) {
                Log.i(TAG, "Frequency update received: ${value / 100.0} MHz")
                mainHandler.post {
                    listener?.onFrequencyUpdate(value)
                }
                return
            }

            // Type 0 = Key events or other notifications
            Log.i(TAG, "Event received: type=$type, value=$value")

            mainHandler.post {
                handleKeyCode(value)
                listener?.onKeyEvent(value, intData)
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

                    // Register for key events
                    registerForKeyEvents()

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
            msModule = null
            ssModule = null

            // Try to reconnect after a delay
            serviceHandler.postDelayed({
                connectToService()
            }, 2000)
        }
    }

    private fun registerForKeyEvents() {
        try {
            // Get MS module (module 0) - Main System events
            msModule = toolkit?.getRemoteModule(MODULE_ID_MS)

            if (msModule == null) {
                Log.e(TAG, "Failed to get MS module")
            } else {
                Log.d(TAG, "Got MS module, registering for key events...")

                // Register for key events (type 0x22)
                msModule?.register(moduleCallback, EVENT_TYPE_KEY, REGISTER_FLAG_DEFAULT)
                Log.i(TAG, "MS module: registered for key events (eventType=$EVENT_TYPE_KEY)")

                // Also try to register for other event types that might contain key events
                val eventTypes = listOf(0, 1, 2, 3, 4, 5, 10, 20, 32, 0x100)
                for (eventType in eventTypes) {
                    try {
                        msModule?.register(moduleCallback, eventType, REGISTER_FLAG_DEFAULT)
                        Log.d(TAG, "MS module: registered for eventType=$eventType")
                    } catch (e: Exception) {
                        Log.d(TAG, "MS module: could not register for eventType=$eventType: ${e.message}")
                    }
                }
            }

            // Get SS module (module 1) - System Settings events
            ssModule = toolkit?.getRemoteModule(MODULE_ID_SS)

            if (ssModule == null) {
                Log.w(TAG, "Failed to get SS module")
            } else {
                Log.d(TAG, "Got SS module, registering for events...")

                // Register for key events
                val eventTypes = listOf(0, EVENT_TYPE_KEY, 1, 2, 3, 4, 5, 10, 20, 32, 0x100)
                for (eventType in eventTypes) {
                    try {
                        ssModule?.register(moduleCallback, eventType, REGISTER_FLAG_DEFAULT)
                        Log.d(TAG, "SS module: registered for eventType=$eventType")
                    } catch (e: Exception) {
                        Log.d(TAG, "SS module: could not register for eventType=$eventType: ${e.message}")
                    }
                }
            }

            isRegistered = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register for key events", e)
        }
    }

    private fun handleKeyCode(keyCode: Int) {
        Log.d(TAG, "Handling keyCode: $keyCode")

        when (keyCode) {
            KEYCODE_MEDIA_NEXT -> {
                Log.i(TAG, "NEXT pressed")
                listener?.onNextPressed()
            }
            KEYCODE_MEDIA_PREVIOUS -> {
                Log.i(TAG, "PREV pressed")
                listener?.onPrevPressed()
            }
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE -> {
                Log.i(TAG, "PLAY/PAUSE pressed")
                listener?.onPlayPausePressed()
            }
            KEYCODE_VOLUME_UP -> {
                Log.d(TAG, "VOL UP pressed")
                listener?.onVolumeUp()
            }
            KEYCODE_VOLUME_DOWN -> {
                Log.d(TAG, "VOL DOWN pressed")
                listener?.onVolumeDown()
            }
            else -> {
                Log.d(TAG, "Unknown keyCode: $keyCode")
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
            if (isRegistered && msModule != null) {
                msModule?.unregister(moduleCallback, EVENT_TYPE_KEY)
                Log.d(TAG, "Unregistered from key events")
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
        msModule = null
        ssModule = null
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
