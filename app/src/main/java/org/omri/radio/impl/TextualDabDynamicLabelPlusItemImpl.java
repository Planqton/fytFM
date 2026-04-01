package org.omri.radio.impl;

import java.io.Serializable;
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusContentType;
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusItem;

public class TextualDabDynamicLabelPlusItemImpl implements TextualDabDynamicLabelPlusItem, Serializable {
    private static final long serialVersionUID = 4395469276512935565L;
    private TextualDabDynamicLabelPlusContentType mContentType = TextualDabDynamicLabelPlusContentType.DUMMY;
    private String mDlpItemText = "";

    @Override
    public String getDlPlusContentCategory() { return this.mContentType.toString(); }

    @Override
    public String getDlPlusContentText() { return this.mDlpItemText; }

    @Override
    public String getDlPlusContentTypeDescription() { return this.mContentType.getContentTypeString(); }

    @Override
    public TextualDabDynamicLabelPlusContentType getDynamicLabelPlusContentType() { return this.mContentType; }

    public void setDlPlusContentText(String str) { this.mDlpItemText = str; }

    public void setDlPlusContentType(int i) {
        if (i <= TextualDabDynamicLabelPlusContentType.values().length) {
            this.mContentType = TextualDabDynamicLabelPlusContentType.values()[i];
        }
    }
}
