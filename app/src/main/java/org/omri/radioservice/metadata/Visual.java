package org.omri.radioservice.metadata;

public interface Visual {
    byte[] getVisualData();
    int getVisualHeight();
    VisualMimeType getVisualMimeType();
    VisualType getVisualType();
    int getVisualWidth();
}
