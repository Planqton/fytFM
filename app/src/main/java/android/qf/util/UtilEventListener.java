package android.qf.util;

/**
 * FYT System interface for receiving key events.
 * This is a stub that matches the system interface signature.
 */
public interface UtilEventListener {
    /**
     * Called when a key event is received from the steering wheel or other input.
     * @param type The event type
     * @param keyEventInfo The key event information containing KeyEvent and keyCode
     */
    void onReceived(int type, QFKeyEventInfo keyEventInfo);
}
