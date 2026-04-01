package org.omri.radio.impl;

import java.io.Serializable;
import org.omri.radioservice.metadata.TermId;

public class TermIdImpl implements TermId, Serializable {
    private static final long serialVersionUID = -3003567186963940474L;
    private String mGenreHref = "";
    private String mTermId = "";
    private String mGenreText = "";

    @Override
    public String getGenreHref() { return this.mGenreHref; }

    @Override
    public String getTermId() { return this.mTermId; }

    @Override
    public String getText() { return this.mGenreText; }

    public void setGenreHref(String str) { this.mGenreHref = str; }
    public void setGenreText(String str) { this.mGenreText = str; }
    public void setTermId(String str) { this.mTermId = str; }
}
