package org.omri.radioservice;

public enum RadioServiceDabDataServiceComponentType {
    UNSPECIFIED_DATA,
    TMC,
    EWS,
    ITTS,
    PAGING,
    TDC,
    IP_DATAGRAM,
    MOT,
    PROPRIETARY,
    RFU;

    public static RadioServiceDabDataServiceComponentType getDSCTyByType(int type) {
        if (type >= 0 && type < values().length) {
            return values()[type];
        }
        return UNSPECIFIED_DATA;
    }
}
