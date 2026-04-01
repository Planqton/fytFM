package org.omri.radio.impl;

public enum TunerUsbCallbackTypes {
    TUNER_CALLBACK_UNKNOWN(-1),
    TUNER_READY(0),
    TUNER_FAILED(1),
    TUNER_SCAN_IN_PROGRESS(4),
    SERVICELIST_READY(5),
    VISUALLIST_READY(6);

    private final int mIntType;

    TunerUsbCallbackTypes(int i) {
        this.mIntType = i;
    }

    public static TunerUsbCallbackTypes getTypeByValue(int i) {
        for (TunerUsbCallbackTypes t : values()) {
            if (t.getIntValue() == i) return t;
        }
        return TUNER_CALLBACK_UNKNOWN;
    }

    public int getIntValue() {
        return this.mIntType;
    }
}
