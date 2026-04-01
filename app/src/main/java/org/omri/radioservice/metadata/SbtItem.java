package org.omri.radioservice.metadata;

public interface SbtItem {
    TextualDabDynamicLabel getDls();
    long getId();
    boolean getItemRunningState();
    boolean getItemToggleState();
    long getPosixTime();
    VisualDabSlideShow getSls();
}
