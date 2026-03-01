package android.qf.mcu;

import android.view.KeyEvent;

/**
 * Stub interface for FYT MCU listener.
 * The real implementation is provided by the FYT system at runtime.
 */
public interface IMcuListener {

    /**
     * Called when a key event is received from the MCU.
     * @param keyEvent The key event
     */
    void onKeyEvent(KeyEvent keyEvent);

    /**
     * Called when MCU data is received.
     * @param type The data type
     * @param data The data bytes
     */
    void onMcuData(int type, byte[] data);
}
