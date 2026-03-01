package android.qf.util;

/**
 * FYT System class for managing key event listeners.
 * This is a stub - the actual implementation is provided by the FYT system.
 * Obtained via context.getSystemService("util_service").
 */
public class UtilEventManager {

    /**
     * Register a listener for key events (steering wheel buttons, etc.)
     * @param listener The listener to receive key events
     */
    public void RPC_KeyEventChangedListener(UtilEventListener listener) {
        // Stub - implemented by system
    }

    /**
     * Remove a previously registered listener
     * @param listener The listener to remove
     */
    public void RPC_RemoveListener(UtilEventListener listener) {
        // Stub - implemented by system
    }

    /**
     * Get the current key code
     * @return The current key code
     */
    public int getKeyCode() {
        return 0; // Stub - implemented by system
    }
}
