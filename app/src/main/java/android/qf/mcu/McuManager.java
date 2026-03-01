package android.qf.mcu;

import android.content.Context;
import android.view.KeyEvent;

/**
 * Stub class for FYT MCU Manager.
 * The real implementation is provided by the FYT system at runtime.
 *
 * This manager provides access to MCU (Microcontroller Unit) functions
 * including steering wheel button events on FYT head units.
 */
public class McuManager {

    private static McuManager sInstance;

    /**
     * Get the McuManager instance.
     * @param context The context
     * @return The McuManager instance, or null if not available
     */
    public static McuManager getInstance(Context context) {
        // Stub - real implementation provided by system
        return null;
    }

    /**
     * Register a listener for MCU key events.
     * @param listener The listener to register
     */
    public void registerKeyEventListener(IMcuListener listener) {
        // Stub - real implementation provided by system
    }

    /**
     * Unregister a previously registered listener.
     * @param listener The listener to unregister
     */
    public void unregisterKeyEventListener(IMcuListener listener) {
        // Stub - real implementation provided by system
    }

    /**
     * Register key event callbacks via AIDL.
     * @param callbacks The callbacks to register
     */
    public void registerKeyEventCallbacks(IKeyEventCallbacks callbacks) {
        // Stub - real implementation provided by system
    }

    /**
     * Unregister key event callbacks.
     * @param callbacks The callbacks to unregister
     */
    public void unregisterKeyEventCallbacks(IKeyEventCallbacks callbacks) {
        // Stub - real implementation provided by system
    }

    /**
     * Send a command to the MCU.
     * @param cmd The command
     * @param data The data
     */
    public void sendMcuCommand(int cmd, byte[] data) {
        // Stub - real implementation provided by system
    }
}
