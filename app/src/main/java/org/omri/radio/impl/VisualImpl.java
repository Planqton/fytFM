package org.omri.radio.impl;

import java.io.Serializable;
import org.omri.radioservice.metadata.Visual;
import org.omri.radioservice.metadata.VisualMimeType;

public abstract class VisualImpl implements Visual, Serializable {
    private static final long serialVersionUID = -6792542766032172959L;
    private byte[] mImageData;
    private VisualMimeType mMimeType = VisualMimeType.METADATA_VISUAL_MIMETYPE_UNKNOWN;
    private int mWidth = -1;
    private int mHeight = -1;

    @Override
    public byte[] getVisualData() { return this.mImageData; }

    @Override
    public int getVisualHeight() { return this.mHeight; }

    @Override
    public VisualMimeType getVisualMimeType() { return this.mMimeType; }

    @Override
    public int getVisualWidth() { return this.mWidth; }

    public void setHeight(int h) { this.mHeight = h; }
    public void setVisualData(byte[] data) { this.mImageData = data; }
    public void setWidth(int w) { this.mWidth = w; }

    public void setVisualMimeType(VisualMimeType type) { this.mMimeType = type; }

    public void setVisualMimeType(int i) {
        if (i <= VisualMimeType.values().length) {
            this.mMimeType = VisualMimeType.values()[i];
        }
    }
}
