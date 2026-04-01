package org.omri.radioservice;

public enum RadioServiceDabUserApplicationType {
    RFU,
    DAB_DYNAMIC_LABEL,
    DAB_SLIDESHOW;

    public static RadioServiceDabUserApplicationType getUserApplicationTypeByType(int type) {
        if (type >= 0 && type < values().length) {
            return values()[type];
        }
        return RFU;
    }
}
