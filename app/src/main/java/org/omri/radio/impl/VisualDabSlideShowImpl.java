package org.omri.radio.impl;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import org.omri.radioservice.metadata.VisualDabSlideShow;
import org.omri.radioservice.metadata.VisualType;

public class VisualDabSlideShowImpl extends VisualImpl implements VisualDabSlideShow, Serializable {
    private URI mCatClickLink;
    private URI mCatLink;
    private int mCatId = -1;
    private String mContentName = "";
    private int mContentType = -1;
    private int mContentSubType = -1;
    private URI mAltLocUri = null;
    private Calendar mExpiryCal = null;
    private int mSlsId = -1;
    private Calendar mTriggerCal = null;
    private String mCatText = "";

    @Override
    public URI getAlternativeLocationURL() { return this.mAltLocUri; }
    @Override
    public int getCategoryId() { return this.mCatId; }
    @Override
    public String getCategoryText() { return this.mCatText; }
    @Override
    public URI getClickThroughUrl() { return this.mCatClickLink; }
    @Override
    public String getContentName() { return this.mContentName; }
    @Override
    public int getContentSubType() { return this.mContentSubType; }
    @Override
    public int getContentType() { return this.mContentType; }
    @Override
    public Calendar getExpiryTime() { return this.mExpiryCal; }
    @Override
    public URI getLink() { return this.mCatLink; }
    @Override
    public int getSlideId() { return this.mSlsId; }
    @Override
    public Calendar getTriggerTime() { return this.mTriggerCal; }

    @Override
    public VisualType getVisualType() { return VisualType.METADATA_VISUAL_TYPE_DAB_SLS; }

    @Override
    public boolean isCategorized() { return this.mCatId >= 0; }

    public void setAlternativeLocationURL(String str) {
        try { this.mAltLocUri = URI.create(str); } catch (IllegalArgumentException e) { }
    }
    public void setCategoryClickThroughLink(String str) {
        try { this.mCatClickLink = new URI(str); } catch (URISyntaxException e) { this.mCatClickLink = null; }
    }
    public void setCategoryId(int id) { this.mCatId = id; }
    public void setCategoryLink(String str) {
        try { this.mCatLink = new URI(str); } catch (URISyntaxException e) { this.mCatLink = null; }
    }
    public void setCategoryText(String str) { this.mCatText = str; }
    public void setContentName(String str) { this.mContentName = str; }
    public void setContentSubType(int i) { this.mContentSubType = i; }
    public void setContentType(int i) { this.mContentType = i; }
    public void setExpiryTime(Calendar cal) { this.mExpiryCal = cal; }
    public void setSlideId(int id) { this.mSlsId = id; }
    public void setTriggerTime(String str) { if (str == "NOW") { this.mTriggerCal = null; } }
}
