package android.qf.mcu;

import android.os.IInterface;
import android.view.KeyEvent;

/**
 * Stub interface for FYT MCU key event callbacks.
 * The real implementation is provided by the FYT system at runtime.
 */
public interface IKeyEventCallbacks extends IInterface {

    /**
     * Called when a key event is received from the MCU.
     * @param keyEvent The key event
     */
    void onKeyEvent(KeyEvent keyEvent);

    /**
     * Called when a key code is received from the MCU.
     * @param keyCode The key code
     * @param action The action (ACTION_DOWN, ACTION_UP)
     */
    void onKeyCode(int keyCode, int action);
}
