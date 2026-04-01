package org.omri.radioservice;

public interface RadioServiceDabUserApplication {
    int getCaOrganization();
    RadioServiceDabDataServiceComponentType getDataServiceComponentType();
    RadioServiceDabUserApplicationType getType();
    byte[] getUserApplicationData();
    int getXpadAppType();
    boolean isCaProtected();
    boolean isDatagroupTransportUsed();
    boolean isXpadApptype();
}
