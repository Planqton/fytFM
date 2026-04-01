package org.omri.radio.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.omri.radioservice.RadioServiceDabComponent;
import org.omri.radioservice.RadioServiceDabComponentListener;
import org.omri.radioservice.RadioServiceDabUserApplication;

public class RadioServiceDabComponentImpl implements RadioServiceDabComponent, Serializable {
    private static final long serialVersionUID = -600743978196068815L;
    private int mScIDsId;
    private int mScBitrate = -1;
    private boolean mScCaFlag = false;
    private int mServiceId = -1;
    private String mScChannelIdString = "";
    private boolean mScDgFlag = false;
    private int mScId = -1;
    private String mScIdString = "";
    private String mScLabel = "";
    private int mScPacketAddress = -1;
    private String mScPacketAddressString = "";
    private boolean mScIsPrimary = false;
    private String mScServiceCompIdString = "";
    private int mScTmId = -1;
    private String mScTmIdString = "";
    private int mScTypeId = -1;
    private String mScTypeIdString = "";
    private boolean mDataGroupsUsed = false;
    private List<RadioServiceDabUserApplication> mScUappList = new ArrayList<>();
    private int mMscStartAddress = -1;
    private int mSubchanSize = -1;
    private int mProtLvl = -1;
    private int mProtType = -1;
    private int mUepIdx = -1;
    private boolean mFecApplied = false;

    public void addScUserApplication(RadioServiceDabUserApplication app) { this.mScUappList.add(app); }
    public void addScUserApplications(List<RadioServiceDabUserApplication> list) { this.mScUappList.addAll(list); }

    @Override public int getBitrate() { return this.mScBitrate; }
    @Override public String getLabel() { return this.mScLabel; }
    @Override public int getMscStartAddress() { return this.mMscStartAddress; }
    @Override public int getPacketAddress() { return this.mScPacketAddress; }
    @Override public int getProtectionLevel() { return this.mProtLvl; }
    @Override public int getProtectionType() { return this.mProtType; }
    @Override public int getServiceComponentIdWithinService() { return this.mScIDsId; }
    @Override public int getServiceComponentType() { return this.mScTypeId; }
    @Override public int getServiceId() { return this.mServiceId; }
    @Override public int getSubchannelId() { return this.mScId; }
    @Override public int getSubchannelSize() { return this.mSubchanSize; }
    @Override public int getTmId() { return this.mScTmId; }
    @Override public int getUepTableIndex() { return this.mUepIdx; }
    @Override public List<RadioServiceDabUserApplication> getUserApplications() { return this.mScUappList; }
    @Override public boolean isCaApplied() { return this.mScCaFlag; }
    @Override public boolean isDatagroupTransportUsed() { return this.mDataGroupsUsed; }
    @Override public boolean isFecSchemeApplied() { return this.mFecApplied; }
    @Override public boolean isPrimary() { return this.mScIsPrimary; }

    public void setScBitrate(int val) { this.mScBitrate = val; }
    public void setScLabel(String str) { this.mScLabel = str.trim(); }
    public void setServiceComponentIdWithinService(int val) { this.mScIDsId = val; }
    public void setServiceComponentType(int val) { this.mScTypeId = val; }
    public void setServiceId(int val) { this.mServiceId = val; }
    public void setSubchannelId(int val) { this.mScId = val; }
    public void setSubchannelSize(int val) { this.mSubchanSize = val; }
    public void setTmId(int val) { this.mScTmId = val; }
    public void setMscStartAddress(int val) { this.mMscStartAddress = val; }
    public void setPacketAddress(int val) { this.mScPacketAddress = val; }
    public void setProtectionLevel(int val) { this.mProtLvl = val; }
    public void setProtectionType(int val) { this.mProtType = val; }
    public void setUepTableIndex(int val) { this.mUepIdx = val; }
    public void setIsScPrimary(boolean val) { this.mScIsPrimary = val; }
    public void setIsScCaFlagSet(boolean val) { this.mScCaFlag = val; }
    public void setDatagroupTransportUsed(boolean val) { this.mDataGroupsUsed = val; }
    public void setIsFecSchemeApplied(boolean val) { this.mFecApplied = val; }

    @Override public void subscribe(RadioServiceDabComponentListener listener) { }
    @Override public void unsubscribe(RadioServiceDabComponentListener listener) { }
}
