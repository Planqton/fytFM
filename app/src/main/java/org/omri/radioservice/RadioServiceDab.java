package org.omri.radioservice;

public interface RadioServiceDab extends RadioService {
    int getServiceId();
    int getEnsembleId();
    int getEnsembleFrequency();
    int getEnsembleEcc();
    String getEnsembleLabel();
    String getEnsembleShortLabel();
    String getShortLabel();
    boolean isProgrammeService();
    boolean isCaProtected();
}
