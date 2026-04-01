package org.omri.radio.impl;

import java.io.Serializable;
import org.omri.radioservice.RadioServiceDabDataServiceComponentType;
import org.omri.radioservice.RadioServiceDabUserApplication;
import org.omri.radioservice.RadioServiceDabUserApplicationType;

public class RadioServiceDabUserApplicationImpl implements RadioServiceDabUserApplication, Serializable {
    private static final long serialVersionUID = -2789012667334731485L;
    private RadioServiceDabUserApplicationType mApptype = RadioServiceDabUserApplicationType.RFU;
    private boolean mIsCaProtected = false;
    private int mCaOrg = -1;
    private boolean mIsXpadApptype = false;
    private int mXpadApptype = -1;
    private boolean mDgUsed = false;
    private RadioServiceDabDataServiceComponentType mDSCTy = RadioServiceDabDataServiceComponentType.UNSPECIFIED_DATA;
    private byte[] mUappSpecificData = null;

    @Override public int getCaOrganization() { return this.mCaOrg; }
    @Override public RadioServiceDabDataServiceComponentType getDataServiceComponentType() { return this.mDSCTy; }
    @Override public RadioServiceDabUserApplicationType getType() { return this.mApptype; }
    @Override public byte[] getUserApplicationData() { return this.mUappSpecificData; }
    @Override public int getXpadAppType() { return this.mXpadApptype; }
    @Override public boolean isCaProtected() { return this.mIsCaProtected; }
    @Override public boolean isDatagroupTransportUsed() { return this.mDgUsed; }
    @Override public boolean isXpadApptype() { return this.mIsXpadApptype; }

    public void setCaOrganization(int val) { this.mCaOrg = val; }
    public void setDSCTy(int val) { this.mDSCTy = RadioServiceDabDataServiceComponentType.getDSCTyByType(val); }
    public void setIsCaProtected(boolean val) { this.mIsCaProtected = val; }
    public void setIsDatagroupsUsed(boolean val) { this.mDgUsed = val; }
    public void setIsXpadApptype(boolean val) { this.mIsXpadApptype = val; }
    public void setUappdata(byte[] data) { this.mUappSpecificData = data; }
    public void setUserApplicationType(int val) { this.mApptype = RadioServiceDabUserApplicationType.getUserApplicationTypeByType(val); }
    public void setXpadApptype(int val) { this.mXpadApptype = val; }
}
