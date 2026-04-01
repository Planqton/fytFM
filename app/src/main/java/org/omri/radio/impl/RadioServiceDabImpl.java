package org.omri.radio.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceDab;
import org.omri.radioservice.RadioServiceType;

/**
 * DAB RadioService implementation. Created by native libirtdab during scanning.
 * Fields are set by native code via reflection.
 */
public class RadioServiceDabImpl extends RadioServiceImpl implements RadioServiceDab, Serializable {
    private static final long serialVersionUID = 4382868398713243924L;
    private int mEnsembleEcc = 0;
    private int mEnsembleId = 0;
    private String mEnsembleLabel = "";
    private String mEnsembleShortLabel = "";
    private int mEnsembleFrequency = 0;
    private boolean mIsCaApplied = false;
    private int mCaId = -1;
    private String mServiceLabel = "";
    private String mShortServiceLabel = "";
    private int mServiceId = 0;
    private boolean mIsProgrammeService = false;
    private final List<org.omri.radioservice.RadioServiceDabComponent> mServiceComponents = new ArrayList<>();

    // Getters
    @Override public int getServiceId() { return mServiceId; }
    @Override public int getEnsembleId() { return mEnsembleId; }
    @Override public int getEnsembleFrequency() { return mEnsembleFrequency; }
    @Override public int getEnsembleEcc() { return mEnsembleEcc; }
    @Override public String getEnsembleLabel() { return mEnsembleLabel; }
    @Override public String getEnsembleShortLabel() { return mEnsembleShortLabel; }
    @Override public String getShortLabel() { return mShortServiceLabel; }
    @Override public boolean isProgrammeService() { return mIsProgrammeService; }
    @Override public boolean isCaProtected() { return mIsCaApplied; }
    @Override public String getServiceLabel() { return mServiceLabel; }
    @Override public RadioServiceType getRadioServiceType() { return RadioServiceType.RADIOSERVICE_TYPE_DAB; }
    public int getCaId() { return mCaId; }
    public List<org.omri.radioservice.RadioServiceDabComponent> getServiceComponents() { return mServiceComponents; }

    // Setters - called by native libirtdab.so via JNI
    public void setServiceId(int id) { mServiceId = id; }
    public void setEnsembleId(int id) { mEnsembleId = id; }
    public void setEnsembleFrequency(int freq) { mEnsembleFrequency = freq; }
    public void setEnsembleEcc(int ecc) { mEnsembleEcc = ecc; }
    public void setEnsembleLabel(String label) { mEnsembleLabel = label != null ? label : ""; }
    public void setEnsembleShortLabel(String label) { mEnsembleShortLabel = label != null ? label : ""; }
    public void setServiceLabel(String label) { mServiceLabel = label != null ? label : ""; }
    public void setShortLabel(String label) { mShortServiceLabel = label != null ? label : ""; }
    public void setIsProgrammeService(boolean b) { mIsProgrammeService = b; }
    public void setIsCaProtected(boolean b) { mIsCaApplied = b; }
    public void setCaId(int id) { mCaId = id; }
    public void addServiceComponent(org.omri.radioservice.RadioServiceDabComponent c) { mServiceComponents.add(c); }
    public void addServiceComponent(List<org.omri.radioservice.RadioServiceDabComponent> list) { mServiceComponents.addAll(list); }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof RadioServiceDab)) return false;
        RadioServiceDab other = (RadioServiceDab) obj;
        return other.getEnsembleId() == mEnsembleId
            && other.getEnsembleFrequency() == mEnsembleFrequency
            && other.getServiceId() == mServiceId
            && other.getEnsembleEcc() == mEnsembleEcc;
    }

    @Override
    public int hashCode() {
        return mServiceId * 31 + mEnsembleId;
    }
}
