package org.omri.radioservice;

import java.util.List;

public interface RadioServiceDabComponent {
    int getBitrate();
    String getLabel();
    int getMscStartAddress();
    int getPacketAddress();
    int getProtectionLevel();
    int getProtectionType();
    int getServiceComponentIdWithinService();
    int getServiceComponentType();
    int getServiceId();
    int getSubchannelId();
    int getSubchannelSize();
    int getTmId();
    int getUepTableIndex();
    List<RadioServiceDabUserApplication> getUserApplications();
    boolean isCaApplied();
    boolean isDatagroupTransportUsed();
    boolean isFecSchemeApplied();
    boolean isPrimary();
    void subscribe(RadioServiceDabComponentListener listener);
    void unsubscribe(RadioServiceDabComponentListener listener);
}
