package org.omri.radioservice.metadata;

import java.net.URI;
import java.util.Calendar;

public interface VisualDabSlideShow extends Visual {
    URI getAlternativeLocationURL();
    int getCategoryId();
    String getCategoryText();
    URI getClickThroughUrl();
    String getContentName();
    int getContentSubType();
    int getContentType();
    Calendar getExpiryTime();
    URI getLink();
    int getSlideId();
    Calendar getTriggerTime();
    boolean isCategorized();
}
