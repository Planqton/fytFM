package at.planqton.fytfm.steering

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.qf.util.QFKeyEventInfo
import android.qf.util.UtilEventListener
import android.qf.util.UtilEventManager
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Manager for receiving steering wheel key events on FYT head units.
 * Uses multiple methods to receive key events:
 * 1. UtilEventManager system service
 * 2. McuManager for direct MCU key events (wie navradio)
 * 3. Broadcast fallback for various FYT systems
 */
class SteeringWheelKeyManager(
    private val context: Context,
    private val listener: KeyEventListener? = null
) {
    companion object {
        private const val TAG = "SteeringWheelKey"
        private const val UTIL_SERVICE_NAME = "util_service"
        private const val UTIL_EVENT_SERVICE_NAME = "UTIL_EVENT_SERVICE"
        private const val MCU_SERVICE_NAME = "mcu"
        private const val SYU_SERVICE_NAME = "syu"

        // Key codes
        private const val KEYCODE_MEDIA_NEXT = 87
        private const val KEYCODE_MEDIA_PREVIOUS = 88
        private const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        private const val KEYCODE_MEDIA_PLAY = 126
        private const val KEYCODE_MEDIA_PAUSE = 127
        private const val KEYCODE_HEADSETHOOK = 79
        private const val KEYCODE_VOLUME_UP = 24
        private const val KEYCODE_VOLUME_DOWN = 25

        // NWD Broadcast Actions (Fallback wenn util_service nicht verfügbar)
        private const val ACTION_KEY_VALUE = "com.nwd.action.ACTION_KEY_VALUE"
        private const val ACTION_SET_RADIO_KEYCMD = "com.nwd.ACTION_SET_RADIO_KEYCMD"
        private const val ACTION_RADIO_FREQUENCY_FROM_MCU = "com.nwd.ACTION_RADIO_FREQUENCY_FROM_MCU"

        // Customize Radio Broadcasts (FYT-spezifisch, von navradio verwendet)
        private const val ACTION_RADIO_PRE = "/customize/radio/pre"
        private const val ACTION_RADIO_NEXT = "/customize/radio/next"
        private const val ACTION_RADIO_SEEK_UP = "/customize/radio/seek_up"
        private const val ACTION_RADIO_SEEK_DOWN = "/customize/radio/seek_down"
        private const val ACTION_RADIO_STATION = "/customize/radio/station"
        private const val ACTION_RADIO_BAND = "/customize/radio/band"
        private const val ACTION_RADIO_CLOSE = "/customize/radio/close"

        // Android Media Button Action
        private const val ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"

        // BYD/FYT spezifische Media Button Actions
        private const val ACTION_BYD_MEDIA_BUTTON = "byd.intent.action.MEDIA_BUTTON"
        private const val ACTION_BYD_MEDIA_MODE = "byd.intent.action.MEDIA_MODE"

        // HCT Radio Broadcasts (Alternative für manche Head Units)
        private const val ACTION_HCT_CHANNEL_NEXT = "hct.radio.channel.next"
        private const val ACTION_HCT_CHANNEL_PREV = "hct.radio.channel.prev"
        private const val ACTION_HCT_POWER_SWITCH = "hct.radio.power.switch"
        private const val ACTION_HCT_BAND_NEXT = "hct.radio.band.next"
        private const val ACTION_HCT_BAND_PREV = "hct.radio.band.prev"
        private const val ACTION_HCT_REQUEST_DATA = "hct.radio.request.data"

        // Microntek Broadcasts
        private const val ACTION_MICRONTEK_REQUEST = "com.microntek.request.radio"
        private const val ACTION_MICRONTEK_CANBUS = "com.microntek.canbusdatareport"

        // SYU/FYT System Broadcasts
        private const val ACTION_SYU_RADIO = "com.syu.radio.broadcast"
        private const val ACTION_QF_KEY_EVENT = "com.qf.action.KEY_EVENT"

        // Debug/Test Broadcasts
        private const val ACTION_UARTDEBUG_CMD = "at.planqton.uartdebug.CMD"

        // Cached reflection
        private var getServiceMethod: Method? = null
        private var serviceManagerClass: Class<*>? = null

        init {
            try {
                serviceManagerClass = Class.forName("android.os.ServiceManager")
                getServiceMethod = serviceManagerClass?.getMethod("getService", String::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Could not find ServiceManager class", e)
            }
        }

        /**
         * Get a system service using reflection (for hidden APIs)
         */
        private fun getServiceViaReflection(serviceName: String): IBinder? {
            return try {
                getServiceMethod?.invoke(null, serviceName) as? IBinder
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get service via reflection: $serviceName", e)
                null
            }
        }
    }

    interface KeyEventListener {
        fun onNextPressed()
        fun onPrevPressed()
        fun onPlayPausePressed()
        fun onVolumeUp()
        fun onVolumeDown()
        fun onRawKeyEvent(keyCode: Int, keyName: String?) {}  // Default empty implementation
    }

    private val handler = Handler(Looper.getMainLooper())
    private var utilEventManager: UtilEventManager? = null
    private var mcuManagerInstance: Any? = null
    private var mcuListenerProxy: Any? = null
    private var isRegistered = false
    private var isMcuRegistered = false
    private var broadcastReceiverRegistered = false

    // Broadcast Receiver als Fallback für NWD Key Events
    private val keyBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            Log.d(TAG, "Broadcast received: ${intent.action}")

            when (intent.action) {
                ACTION_KEY_VALUE -> {
                    val keyCode = intent.getIntExtra("keyCode", -1)
                    val keyValue = intent.getIntExtra("keyValue", -1)
                    Log.d(TAG, "ACTION_KEY_VALUE: keyCode=$keyCode, keyValue=$keyValue")
                    if (keyCode > 0) {
                        handler.post { handleKeyCode(keyCode) }
                    }
                }
                ACTION_SET_RADIO_KEYCMD -> {
                    val cmd = intent.getIntExtra("cmd", -1)
                    val keyCode = intent.getIntExtra("keyCode", -1)
                    Log.d(TAG, "ACTION_SET_RADIO_KEYCMD: cmd=$cmd, keyCode=$keyCode")
                    // cmd könnte NEXT/PREV/etc sein
                    when (cmd) {
                        1 -> handler.post { handleKeyCode(KEYCODE_MEDIA_PREVIOUS) }  // PREV
                        2 -> handler.post { handleKeyCode(KEYCODE_MEDIA_NEXT) }      // NEXT
                        3 -> handler.post { handleKeyCode(KEYCODE_MEDIA_PLAY_PAUSE) } // PLAY/PAUSE
                    }
                    if (keyCode > 0) {
                        handler.post { handleKeyCode(keyCode) }
                    }
                }
                // FYT /customize/radio/* Broadcasts (wie navradio)
                ACTION_RADIO_PRE -> {
                    Log.d(TAG, "Radio PRE (Previous Station)")
                    handler.post { handleKeyCode(KEYCODE_MEDIA_PREVIOUS) }
                }
                ACTION_RADIO_NEXT -> {
                    Log.d(TAG, "Radio NEXT (Next Station)")
                    handler.post { handleKeyCode(KEYCODE_MEDIA_NEXT) }
                }
                ACTION_RADIO_SEEK_UP -> {
                    Log.d(TAG, "Radio SEEK UP")
                    handler.post { handleKeyCode(KEYCODE_MEDIA_PREVIOUS) }
                }
                ACTION_RADIO_SEEK_DOWN -> {
                    Log.d(TAG, "Radio SEEK DOWN")
                    handler.post { handleKeyCode(KEYCODE_MEDIA_NEXT) }
                }
                ACTION_MEDIA_BUTTON, ACTION_BYD_MEDIA_BUTTON -> {
                    val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    Log.d(TAG, "MEDIA_BUTTON: keyEvent=$keyEvent, action=${intent.action}")
                    keyEvent?.let {
                        if (it.action == KeyEvent.ACTION_UP || it.isLongPress) {
                            handler.post { handleKeyCode(it.keyCode) }
                        }
                    }
                    // Auch Extras loggen für Debugging
                    intent.extras?.keySet()?.forEach { key ->
                        Log.d(TAG, "  Extra: $key = ${intent.extras?.get(key)}")
                    }
                }
                ACTION_BYD_MEDIA_MODE -> {
                    Log.d(TAG, "BYD_MEDIA_MODE received")
                    intent.extras?.keySet()?.forEach { key ->
                        Log.d(TAG, "  Extra: $key = ${intent.extras?.get(key)}")
                    }
                }
                // HCT Radio Broadcasts
                ACTION_HCT_CHANNEL_NEXT -> {
                    Log.i(TAG, "HCT: channel.next")
                    handler.post { handleKeyCode(KEYCODE_MEDIA_NEXT) }
                }
                ACTION_HCT_CHANNEL_PREV -> {
                    Log.i(TAG, "HCT: channel.prev")
                    handler.post { handleKeyCode(KEYCODE_MEDIA_PREVIOUS) }
                }
                ACTION_HCT_POWER_SWITCH -> {
                    Log.i(TAG, "HCT: power.switch")
                    handler.post { handleKeyCode(KEYCODE_MEDIA_PLAY_PAUSE) }
                }
                // Microntek Broadcasts
                ACTION_MICRONTEK_REQUEST, ACTION_MICRONTEK_CANBUS -> {
                    val cmd = intent.getStringExtra("cmd") ?: intent.getStringExtra("command")
                    val keyCode = intent.getIntExtra("keyCode", -1)
                    Log.d(TAG, "Microntek: cmd=$cmd, keyCode=$keyCode")
                    when (cmd) {
                        "next", "seekup" -> handler.post { handleKeyCode(KEYCODE_MEDIA_NEXT) }
                        "prev", "seekdown" -> handler.post { handleKeyCode(KEYCODE_MEDIA_PREVIOUS) }
                        "pause", "play", "playpause" -> handler.post { handleKeyCode(KEYCODE_MEDIA_PLAY_PAUSE) }
                    }
                    if (keyCode > 0) {
                        handler.post { handleKeyCode(keyCode) }
                    }
                }
                // SYU/QF Broadcasts
                ACTION_SYU_RADIO, ACTION_QF_KEY_EVENT -> {
                    val keyCode = intent.getIntExtra("keyCode", -1)
                    val cmd = intent.getIntExtra("cmd", -1)
                    Log.d(TAG, "SYU/QF: keyCode=$keyCode, cmd=$cmd")
                    if (keyCode > 0) {
                        handler.post { handleKeyCode(keyCode) }
                    }
                    when (cmd) {
                        1 -> handler.post { handleKeyCode(KEYCODE_MEDIA_PREVIOUS) }
                        2 -> handler.post { handleKeyCode(KEYCODE_MEDIA_NEXT) }
                        3 -> handler.post { handleKeyCode(KEYCODE_MEDIA_PLAY_PAUSE) }
                    }
                }
                // Debug/Test Broadcasts
                ACTION_UARTDEBUG_CMD -> {
                    val cmd = intent.getStringExtra("cmd")
                    Log.i(TAG, "UartDebug: cmd=$cmd")
                    when (cmd) {
                        "next" -> handler.post { handleKeyCode(KEYCODE_MEDIA_NEXT) }
                        "prev" -> handler.post { handleKeyCode(KEYCODE_MEDIA_PREVIOUS) }
                        "play", "pause", "playpause" -> handler.post { handleKeyCode(KEYCODE_MEDIA_PLAY_PAUSE) }
                    }
                }
                else -> {
                    // Log unbekannte Actions für Debugging
                    Log.d(TAG, "Unknown action: ${intent.action}, extras: ${intent.extras}")
                }
            }
        }
    }

    private val keyEventListener = object : UtilEventListener {
        override fun onReceived(type: Int, keyEventInfo: QFKeyEventInfo?) {
            if (keyEventInfo == null) return

            val keyEvent = keyEventInfo.getKeyEventInfo()
            val keyCode = keyEvent?.keyCode ?: keyEventInfo.getKeyCode()

            Log.d(TAG, "Key received: keyCode=$keyCode, type=$type")

            handler.post {
                handleKeyCode(keyCode)
            }
        }
    }

    private fun handleKeyCode(keyCode: Int) {
        Log.d(TAG, "Handling keyCode: $keyCode")

        // Determine key name for debug display
        val keyName = when (keyCode) {
            KEYCODE_MEDIA_NEXT -> "NEXT"
            KEYCODE_MEDIA_PREVIOUS -> "PREV"
            KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY/PAUSE"
            KEYCODE_MEDIA_PLAY -> "PLAY"
            KEYCODE_MEDIA_PAUSE -> "PAUSE"
            KEYCODE_HEADSETHOOK -> "HOOK"
            KEYCODE_VOLUME_UP -> "VOL+"
            KEYCODE_VOLUME_DOWN -> "VOL-"
            else -> null
        }

        // Always notify raw key event (for debug display)
        listener?.onRawKeyEvent(keyCode, keyName)

        when (keyCode) {
            KEYCODE_MEDIA_NEXT -> {
                Log.d(TAG, "NEXT pressed")
                listener?.onNextPressed()
            }
            KEYCODE_MEDIA_PREVIOUS -> {
                Log.d(TAG, "PREV pressed")
                listener?.onPrevPressed()
            }
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE, KEYCODE_HEADSETHOOK -> {
                Log.d(TAG, "PLAY/PAUSE pressed")
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

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Initialize and register for key events.
     * Call this in onCreate or onStart.
     */
    fun register() {
        if (isRegistered && isMcuRegistered) {
            Log.d(TAG, "Already registered")
            return
        }

        // Method 1: Try McuManager first (wie navradio)
        tryRegisterMcuManager()

        // Method 2: Try UtilEventManager
        if (!isRegistered) {
            tryRegisterUtilEventManager()
        }

        // Method 3: Always register broadcast fallback as additional listener
        registerBroadcastFallback()
    }

    /**
     * Try to register via McuManager (wie navradio verwendet)
     */
    private fun tryRegisterMcuManager() {
        try {
            // Try to get McuManager class
            val mcuManagerClass = try {
                Class.forName("android.qf.mcu.McuManager")
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "McuManager class not found in system")
                null
            }

            if (mcuManagerClass != null) {
                Log.d(TAG, "Found McuManager class: ${mcuManagerClass.name}")

                // Try getInstance or get via context
                val getInstanceMethod = try {
                    mcuManagerClass.getMethod("getInstance", Context::class.java)
                } catch (e: NoSuchMethodException) {
                    try {
                        mcuManagerClass.getMethod("getInstance")
                    } catch (e2: NoSuchMethodException) {
                        null
                    }
                }

                val mcuManager = if (getInstanceMethod != null) {
                    if (getInstanceMethod.parameterCount == 1) {
                        getInstanceMethod.invoke(null, context)
                    } else {
                        getInstanceMethod.invoke(null)
                    }
                } else {
                    // Try via system service
                    context.getSystemService(MCU_SERVICE_NAME)
                        ?: context.getSystemService("mcu_service")
                }

                if (mcuManager != null) {
                    Log.d(TAG, "Got McuManager instance: ${mcuManager.javaClass.name}")
                    mcuManagerInstance = mcuManager
                    registerMcuKeyListener(mcuManager)
                } else {
                    Log.d(TAG, "McuManager instance is null")
                }
            }

            // Also try via SYU service (com.syu.ipc.IRemoteToolkit)
            tryRegisterViaSyuService()

        } catch (e: Exception) {
            Log.w(TAG, "Failed to register via McuManager", e)
        }
    }

    /**
     * Try to register via SYU IRemoteToolkit service using pure reflection
     */
    private fun tryRegisterViaSyuService() {
        try {
            val syuBinder = getServiceViaReflection(SYU_SERVICE_NAME)
            if (syuBinder == null) {
                Log.d(TAG, "SYU service binder not found")
                return
            }

            Log.d(TAG, "Found SYU service binder: ${syuBinder.javaClass.name}")

            // Get the interface descriptor to find the real class
            val descriptor = try {
                val getDescriptorMethod = syuBinder.javaClass.getMethod("getInterfaceDescriptor")
                getDescriptorMethod.invoke(syuBinder) as? String
            } catch (e: Exception) {
                "com.syu.ipc.IRemoteToolkit"
            }
            Log.d(TAG, "SYU interface descriptor: $descriptor")

            // Try to get interface via Stub.asInterface
            var toolkit: Any? = null
            val stubClassNames = listOf(
                "com.syu.ipc.IRemoteToolkit\$Stub",
                "com.syu.ipc.IRemoteToolkit",
                "${descriptor}\$Stub",
                descriptor
            )

            for (className in stubClassNames) {
                try {
                    val stubClass = Class.forName(className)
                    val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                    toolkit = asInterfaceMethod.invoke(null, syuBinder)
                    if (toolkit != null) {
                        Log.d(TAG, "Got SYU toolkit via $className: ${toolkit.javaClass.name}")
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "  $className failed: ${e.message}")
                }
            }

            // If Stub.asInterface failed, try to use binder directly via transact
            if (toolkit == null) {
                Log.d(TAG, "Using binder directly, listing all methods:")
                syuBinder.javaClass.methods.forEach { method ->
                    Log.d(TAG, "  Binder method: ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                }

                // Try queryLocalInterface
                try {
                    val queryMethod = syuBinder.javaClass.getMethod("queryLocalInterface", String::class.java)
                    toolkit = queryMethod.invoke(syuBinder, descriptor ?: "com.syu.ipc.IRemoteToolkit")
                    if (toolkit != null) {
                        Log.d(TAG, "Got toolkit via queryLocalInterface: ${toolkit.javaClass.name}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "queryLocalInterface failed: ${e.message}")
                }
            }

            if (toolkit != null) {
                exploreSyuToolkit(toolkit)
            } else {
                // Last resort: explore the binder proxy directly
                Log.d(TAG, "Exploring binder proxy directly")
                exploreSyuToolkit(syuBinder)
            }

        } catch (e: Exception) {
            Log.w(TAG, "SYU service error: ${e.message}", e)
        }
    }

    /**
     * Explore SYU toolkit to find key event registration methods
     */
    private fun exploreSyuToolkit(toolkit: Any) {
        Log.d(TAG, "Exploring SYU toolkit: ${toolkit.javaClass.name}")

        // List ALL methods for debugging
        val allMethods = toolkit.javaClass.methods
        Log.d(TAG, "Total methods: ${allMethods.size}")

        allMethods.forEach { method ->
            val paramStr = method.parameterTypes.joinToString(", ") { it.simpleName }
            Log.d(TAG, "  ${method.name}($paramStr) -> ${method.returnType.simpleName}")
        }

        // Look for key/listener/register methods
        val interestingMethods = allMethods.filter { method ->
            method.name.contains("key", ignoreCase = true) ||
            method.name.contains("listener", ignoreCase = true) ||
            method.name.contains("register", ignoreCase = true) ||
            method.name.contains("callback", ignoreCase = true) ||
            method.name.contains("mcu", ignoreCase = true) ||
            method.name.contains("radio", ignoreCase = true) ||
            method.name.contains("steering", ignoreCase = true) ||
            method.name.contains("event", ignoreCase = true)
        }

        if (interestingMethods.isNotEmpty()) {
            Log.i(TAG, "Found ${interestingMethods.size} interesting methods:")
            interestingMethods.forEach { method ->
                val paramStr = method.parameterTypes.joinToString(", ") { it.simpleName }
                Log.i(TAG, "  >>> ${method.name}($paramStr)")
            }

            // Try to register listeners
            for (method in interestingMethods) {
                if (method.name.startsWith("register") || method.name.startsWith("set") || method.name.startsWith("add")) {
                    tryRegisterWithMethod(toolkit, method)
                }
            }
        }

        // Also try to get a McuManager or similar sub-service
        val getterMethods = allMethods.filter {
            (it.name.startsWith("get") || it.name.startsWith("obtain")) &&
            it.parameterCount == 0 &&
            it.returnType != Void.TYPE
        }

        for (getter in getterMethods) {
            try {
                val subService = getter.invoke(toolkit)
                if (subService != null && subService.javaClass.name.contains("Mcu", ignoreCase = true)) {
                    Log.i(TAG, "Found MCU sub-service via ${getter.name}: ${subService.javaClass.name}")
                    registerMcuKeyListener(subService)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Try getRemoteModule with various IDs
        tryGetRemoteModules(toolkit)
    }

    /**
     * Try to get remote modules from toolkit
     */
    private fun tryGetRemoteModules(toolkit: Any) {
        try {
            val getRemoteModuleMethod = toolkit.javaClass.getMethod("getRemoteModule", Int::class.java)

            // Try module IDs 0-20
            for (moduleId in 0..20) {
                try {
                    val module = getRemoteModuleMethod.invoke(toolkit, moduleId)
                    if (module != null) {
                        Log.i(TAG, "Module $moduleId: ${module.javaClass.name}")
                        exploreRemoteModule(module, moduleId)
                    }
                } catch (e: Exception) {
                    // Module doesn't exist, skip
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "getRemoteModule not available: ${e.message}")
        }
    }

    /**
     * Explore a remote module for key event methods
     */
    private fun exploreRemoteModule(module: Any, moduleId: Int) {
        val moduleMethods = module.javaClass.methods
        Log.d(TAG, "Module $moduleId has ${moduleMethods.size} methods:")

        val interestingMethods = moduleMethods.filter { method ->
            method.name.contains("key", ignoreCase = true) ||
            method.name.contains("listener", ignoreCase = true) ||
            method.name.contains("register", ignoreCase = true) ||
            method.name.contains("callback", ignoreCase = true) ||
            method.name.contains("event", ignoreCase = true) ||
            method.name.contains("button", ignoreCase = true) ||
            method.name.contains("steering", ignoreCase = true)
        }

        // Log all methods for this module
        moduleMethods.forEach { method ->
            val paramStr = method.parameterTypes.joinToString(", ") { it.simpleName }
            val isInteresting = interestingMethods.contains(method)
            val prefix = if (isInteresting) ">>>" else "   "
            Log.d(TAG, "  $prefix ${method.name}($paramStr) -> ${method.returnType.simpleName}")
        }

        // Try to register with IModuleCallback
        tryRegisterModuleCallback(module, moduleId)
    }

    /**
     * Try to register IModuleCallback on a module
     */
    private fun tryRegisterModuleCallback(module: Any, moduleId: Int) {
        try {
            val registerMethod = module.javaClass.methods.find {
                it.name == "register" && it.parameterCount == 3
            } ?: return

            val callbackType = registerMethod.parameterTypes[0]
            Log.d(TAG, "Found register method with callback type: ${callbackType.name}")

            // Create callback proxy
            val callbackProxy = Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType)
            ) { _, method, args ->
                Log.i(TAG, "=== MODULE CALLBACK: ${method.name} ===")
                args?.forEachIndexed { index, arg ->
                    Log.i(TAG, "  Arg[$index]: $arg (${arg?.javaClass?.simpleName})")

                    // Try to extract key info from ModuleObject or similar
                    if (arg != null) {
                        try {
                            val argClass = arg.javaClass
                            // Log fields
                            argClass.fields.forEach { field ->
                                try {
                                    field.isAccessible = true
                                    Log.i(TAG, "    Field ${field.name}: ${field.get(arg)}")
                                } catch (e: Exception) {}
                            }
                            // Log methods that return primitives or strings
                            argClass.methods.filter {
                                it.parameterCount == 0 &&
                                (it.returnType.isPrimitive || it.returnType == String::class.java) &&
                                !it.name.startsWith("get") || it.name.startsWith("get")
                            }.forEach { getter ->
                                try {
                                    val value = getter.invoke(arg)
                                    if (value != null && value.toString().isNotEmpty()) {
                                        Log.i(TAG, "    ${getter.name}(): $value")
                                    }
                                } catch (e: Exception) {}
                            }
                        } catch (e: Exception) {}
                    }

                    // Check if it's an int array (could contain keyCode)
                    if (arg is IntArray && arg.isNotEmpty()) {
                        Log.i(TAG, "  IntArray: ${arg.toList()}")
                        // First int might be keyCode
                        if (arg.isNotEmpty()) {
                            val possibleKeyCode = arg[0]
                            Log.i(TAG, "  Possible keyCode: $possibleKeyCode")
                            if (possibleKeyCode in 1..300) {
                                handler.post { handleKeyCode(possibleKeyCode) }
                            }
                        }
                    }
                }

                when (method.name) {
                    "asBinder" -> null
                    "toString" -> "FytFM ModuleCallback Proxy"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> args?.get(0) === this
                    else -> null
                }
            }

            // Try different event type IDs for the second parameter
            // Common IDs: 0=all, 1=key, 2=mcu, etc.
            val eventTypes = listOf(0, 1, 2, 3, 4, 5, 10, 20, 32, 100)

            for (eventType in eventTypes) {
                try {
                    // register(callback, eventType, flags)
                    registerMethod.invoke(module, callbackProxy, eventType, 0)
                    Log.i(TAG, "SUCCESS: Registered callback on module $moduleId with eventType $eventType")
                    mcuListenerProxy = callbackProxy
                    isMcuRegistered = true
                } catch (e: Exception) {
                    Log.d(TAG, "  Register with eventType $eventType failed: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to register module callback", e)
        }
    }

    /**
     * Try to register a listener using a specific method
     */
    private fun tryRegisterWithMethod(toolkit: Any, method: Method) {
        if (method.parameterCount != 1) return

        val listenerType = method.parameterTypes[0]
        if (!listenerType.isInterface) return

        Log.d(TAG, "Trying to register via ${method.name} with ${listenerType.name}")

        try {
            val proxy = createListenerProxy(listenerType)
            if (proxy != null) {
                method.invoke(toolkit, proxy)
                mcuListenerProxy = proxy
                isMcuRegistered = true
                Log.i(TAG, "SUCCESS: Registered via ${method.name}!")
            }
        } catch (e: Exception) {
            Log.d(TAG, "  Failed: ${e.message}")
        }
    }

    /**
     * Register key listener on McuManager using reflection
     */
    private fun registerMcuKeyListener(mcuManager: Any) {
        try {
            val managerClass = mcuManager.javaClass
            Log.d(TAG, "McuManager methods:")
            managerClass.methods.forEach { method ->
                if (method.name.contains("key", ignoreCase = true) ||
                    method.name.contains("listener", ignoreCase = true) ||
                    method.name.contains("register", ignoreCase = true) ||
                    method.name.contains("callback", ignoreCase = true)) {
                    Log.d(TAG, "  ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                }
            }

            // Try various registration methods
            val registerMethods = listOf(
                "registerKeyEventListener",
                "registerKeyEventCallbacks",
                "setKeyEventListener",
                "addKeyEventListener",
                "registerListener"
            )

            for (methodName in registerMethods) {
                try {
                    val methods = managerClass.methods.filter { it.name == methodName }
                    for (method in methods) {
                        if (method.parameterCount == 1) {
                            val listenerType = method.parameterTypes[0]
                            Log.d(TAG, "Found register method: $methodName(${listenerType.name})")

                            // Create dynamic proxy for the listener interface
                            val proxy = createListenerProxy(listenerType)
                            if (proxy != null) {
                                method.invoke(mcuManager, proxy)
                                mcuListenerProxy = proxy
                                isMcuRegistered = true
                                Log.i(TAG, "Successfully registered MCU key listener via $methodName")
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Method $methodName failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register MCU key listener", e)
        }
    }

    /**
     * Create a dynamic proxy for listener interface
     */
    private fun createListenerProxy(listenerType: Class<*>): Any? {
        return try {
            Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType)
            ) { _, method, args ->
                Log.d(TAG, "MCU Listener callback: ${method.name}")

                when {
                    method.name.contains("KeyEvent", ignoreCase = true) ||
                    method.name.contains("onKey", ignoreCase = true) -> {
                        args?.forEach { arg ->
                            when (arg) {
                                is KeyEvent -> {
                                    Log.d(TAG, "MCU KeyEvent: keyCode=${arg.keyCode}, action=${arg.action}")
                                    if (arg.action == KeyEvent.ACTION_UP) {
                                        handler.post { handleKeyCode(arg.keyCode) }
                                    }
                                }
                                is Int -> {
                                    Log.d(TAG, "MCU keyCode: $arg")
                                    handler.post { handleKeyCode(arg) }
                                }
                            }
                        }
                    }
                    method.name == "toString" -> "FytFM MCU Listener Proxy"
                    method.name == "hashCode" -> System.identityHashCode(this)
                    method.name == "equals" -> args?.get(0) === this
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create listener proxy for ${listenerType.name}", e)
            null
        }
    }

    /**
     * Try to register via UtilEventManager
     */
    private fun tryRegisterUtilEventManager() {
        try {
            // Try both service names
            var binder: IBinder? = getServiceViaReflection(UTIL_SERVICE_NAME)
            var serviceName = UTIL_SERVICE_NAME

            if (binder == null) {
                Log.d(TAG, "util_service not found, trying UTIL_EVENT_SERVICE...")
                binder = getServiceViaReflection(UTIL_EVENT_SERVICE_NAME)
                serviceName = UTIL_EVENT_SERVICE_NAME
            }

            if (binder == null) {
                Log.d(TAG, "No util service found")
                return
            }

            Log.d(TAG, "Found service: $serviceName")

            // Get UtilEventManager from system service
            val manager = context.getSystemService(serviceName)
            if (manager == null) {
                Log.w(TAG, "Could not get UtilEventManager from system service")
                return
            }

            if (manager !is UtilEventManager) {
                Log.w(TAG, "System service is not UtilEventManager: ${manager.javaClass.name}")
                // Try to use reflection to call RPC_KeyEventChangedListener on the real object
                tryRegisterViaReflection(manager)
                return
            } else {
                utilEventManager = manager
            }

            // Register listener
            utilEventManager?.RPC_KeyEventChangedListener(keyEventListener)
            isRegistered = true
            Log.i(TAG, "Successfully registered for steering wheel key events via UtilEventManager")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register via UtilEventManager", e)
        }
    }

    /**
     * Register broadcast receiver as fallback when util_service is not available
     */
    private fun registerBroadcastFallback() {
        if (broadcastReceiverRegistered) {
            Log.d(TAG, "Broadcast receiver already registered")
            return
        }

        try {
            val filter = IntentFilter().apply {
                // NWD Actions
                addAction(ACTION_KEY_VALUE)
                addAction(ACTION_SET_RADIO_KEYCMD)
                addAction(ACTION_RADIO_FREQUENCY_FROM_MCU)
                // FYT /customize/radio/* Actions
                addAction(ACTION_RADIO_PRE)
                addAction(ACTION_RADIO_NEXT)
                addAction(ACTION_RADIO_SEEK_UP)
                addAction(ACTION_RADIO_SEEK_DOWN)
                addAction(ACTION_RADIO_STATION)
                addAction(ACTION_RADIO_BAND)
                addAction(ACTION_RADIO_CLOSE)
                // Standard Media Button
                addAction(ACTION_MEDIA_BUTTON)
                // BYD/FYT Media Button
                addAction(ACTION_BYD_MEDIA_BUTTON)
                addAction(ACTION_BYD_MEDIA_MODE)
                // HCT Radio Actions
                addAction(ACTION_HCT_CHANNEL_NEXT)
                addAction(ACTION_HCT_CHANNEL_PREV)
                addAction(ACTION_HCT_POWER_SWITCH)
                addAction(ACTION_HCT_BAND_NEXT)
                addAction(ACTION_HCT_BAND_PREV)
                addAction(ACTION_HCT_REQUEST_DATA)
                // Microntek Actions
                addAction(ACTION_MICRONTEK_REQUEST)
                addAction(ACTION_MICRONTEK_CANBUS)
                // SYU/QF Actions
                addAction(ACTION_SYU_RADIO)
                addAction(ACTION_QF_KEY_EVENT)
                // Debug/Test Action
                addAction(ACTION_UARTDEBUG_CMD)
            }
            // Für Android 13+ mit RECEIVER_EXPORTED
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(keyBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(keyBroadcastReceiver, filter)
            }
            broadcastReceiverRegistered = true
            Log.i(TAG, "Registered broadcast fallback for steering wheel keys (${filter.countActions()} actions)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register broadcast fallback", e)
        }
    }

    /**
     * Try to register the listener using reflection when direct cast fails
     */
    private fun tryRegisterViaReflection(manager: Any) {
        try {
            val managerClass = manager.javaClass
            Log.d(TAG, "Trying reflection on: ${managerClass.name}")

            // Find RPC_KeyEventChangedListener method
            val methods = managerClass.methods
            for (method in methods) {
                Log.d(TAG, "  Method: ${method.name}")
            }

            val registerMethod = managerClass.getMethod(
                "RPC_KeyEventChangedListener",
                UtilEventListener::class.java
            )
            registerMethod.invoke(manager, keyEventListener)
            isRegistered = true
            Log.i(TAG, "Successfully registered via reflection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register via reflection", e)
        }
    }

    /**
     * Unregister from key events.
     * Call this in onDestroy or onStop.
     */
    fun unregister() {
        // Unregister MCU listener
        if (isMcuRegistered && mcuManagerInstance != null && mcuListenerProxy != null) {
            try {
                val unregisterMethods = listOf(
                    "unregisterKeyEventListener",
                    "unregisterKeyEventCallbacks",
                    "removeKeyEventListener"
                )
                for (methodName in unregisterMethods) {
                    try {
                        val method = mcuManagerInstance!!.javaClass.methods.find {
                            it.name == methodName && it.parameterCount == 1
                        }
                        method?.invoke(mcuManagerInstance, mcuListenerProxy)
                        Log.i(TAG, "Unregistered MCU listener via $methodName")
                        break
                    } catch (e: Exception) {
                        // Try next method
                    }
                }
                isMcuRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister MCU listener", e)
            }
        }

        // Unregister util_service listener
        if (isRegistered) {
            try {
                utilEventManager?.RPC_RemoveListener(keyEventListener)
                isRegistered = false
                Log.i(TAG, "Unregistered from steering wheel key events")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister from key events", e)
            }
        }

        // Unregister broadcast receiver
        if (broadcastReceiverRegistered) {
            try {
                context.unregisterReceiver(keyBroadcastReceiver)
                broadcastReceiverRegistered = false
                Log.i(TAG, "Unregistered broadcast fallback")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister broadcast fallback", e)
            }
        }
    }

    /**
     * Check if steering wheel key handling is available.
     */
    fun isServiceAvailable(): Boolean {
        return isRegistered || isMcuRegistered || broadcastReceiverRegistered
    }

    /**
     * Check if MCU service is available.
     */
    fun isMcuServiceAvailable(): Boolean {
        return isMcuRegistered
    }

    /**
     * Check if the FYT util_service exists.
     */
    fun isUtilServiceAvailable(): Boolean {
        return try {
            val binder = getServiceViaReflection(UTIL_SERVICE_NAME)
            binder != null
        } catch (e: Exception) {
            false
        }
    }
}
