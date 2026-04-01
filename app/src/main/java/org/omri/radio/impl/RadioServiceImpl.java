package org.omri.radio.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceType;
import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.TermId;
import org.omri.radioservice.metadata.Visual;
import org.omri.radioservice.metadata.VisualDabSlideShow;

/**
 * Base RadioService implementation with all methods called by native libirtdab.
 */
public class RadioServiceImpl implements RadioService, Serializable {
    private static final long serialVersionUID = 1L;
    protected RadioServiceType mServiceType = RadioServiceType.RADIOSERVICE_TYPE_DAB;
    private String mShortDescription = "";
    private String mLongDescription = "";
    private String mHradioSearchSource = "";
    private final List<TermId> mGenres = new ArrayList<>();
    private final List<String> mKeywords = new ArrayList<>();
    private final List<String> mLinks = new ArrayList<>();
    private final List<Visual> mLogos = new ArrayList<>();
    private final List<RadioService> mFollowingServices = new ArrayList<>();

    @Override public RadioServiceType getRadioServiceType() { return mServiceType; }
    @Override public String getServiceLabel() { return ""; }

    // Getters called by native
    public List<TermId> getGenres() { return mGenres; }
    public List<String> getKeywords() { return mKeywords; }
    public List<String> getLinks() { return mLinks; }
    public List<Visual> getLogos() { return mLogos; }
    public String getShortDescription() { return mShortDescription; }
    public String getLongDescription() { return mLongDescription; }
    public String getHradioSearchSource() { return mHradioSearchSource; }
    public ArrayList<RadioService> getFollowingServices() { return new ArrayList<>(mFollowingServices); }

    // Setters called by native via JNI
    public void addGenre(TermId t) { if (t != null) mGenres.add(t); }
    public void addKeyword(String s) { if (s != null) mKeywords.add(s); }
    public void addLink(String s) { if (s != null) mLinks.add(s); }
    public void addLocation(Object loc) { /* stub */ }
    public void addLogo(Visual v) { if (v != null) mLogos.add(v); }
    public void addLogo(List<Visual> list) { if (list != null) mLogos.addAll(list); }
    public void addMembership(Object g) { /* stub */ }
    public void setShortDescription(String s) { mShortDescription = s != null ? s : ""; }
    public void setLongDescription(String s) { mLongDescription = s != null ? s : ""; }
    public void setHradioSearchSource(String s) { mHradioSearchSource = s != null ? s : ""; }
    public void setFollowingServices(ArrayList<RadioService> list) { mFollowingServices.clear(); if (list != null) mFollowingServices.addAll(list); }

    // Audio callbacks from native
    public void audioData(byte[] data, int sampleRate, int channels) { /* stub */ }
    public void audioFormatChanged(int codec, int bitrate, int sampleRate, boolean sbr, boolean ps) { /* stub */ }
    public void decodedAudioData(byte[] data, int sampleRate, int channels) { /* stub */ }

    // Metadata callbacks from native
    public void labelReceived(Textual textual) { /* stub */ }
    public void slideshowReceived(VisualDabSlideShow slideshow) { /* stub */ }
    public void spiReceived(String spi) { /* stub */ }
    public void serviceFollowingReceived(ArrayList<RadioService> list) { /* stub */ }

    public void serviceStarted() { /* stub */ }
    public void serviceStopped() { /* stub */ }
    public ArrayList<RadioService> replaceLinkedRadioServicesWithKnown(ArrayList<RadioService> list) { return list; }
    public boolean equalsRadioService(RadioService other) { return this.equals(other); }
}
