package org.omri.radio.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.omri.radioservice.metadata.TextualDabDynamicLabel;
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusItem;
import org.omri.radioservice.metadata.TextualType;

public class TextualDabDynamicLabelImpl extends TextualImpl implements TextualDabDynamicLabel, Serializable {
    private static final long serialVersionUID = 342793136525104857L;
    private List<TextualDabDynamicLabelPlusItem> mDlpItemsList = new ArrayList<>();
    private boolean mItemRunning = false;
    private boolean mItemToggled = false;

    public void addDlPlusItem(TextualDabDynamicLabelPlusItem item) { this.mDlpItemsList.add(item); }
    public void addDlPlusItems(List<TextualDabDynamicLabelPlusItem> list) { this.mDlpItemsList.addAll(list); }

    @Override
    public List<TextualDabDynamicLabelPlusItem> getDlPlusItems() { return this.mDlpItemsList; }

    @Override
    public int getTagCount() { return this.mDlpItemsList.size(); }

    @Override
    public TextualType getType() { return TextualType.METADATA_TEXTUAL_TYPE_DAB_DLS; }

    @Override
    public boolean hasTags() { return this.mDlpItemsList.size() > 0; }

    @Override
    public boolean itemRunning() { return this.mItemRunning; }

    @Override
    public boolean itemToggled() { return this.mItemToggled; }

    public void setItemRunning(boolean val) { this.mItemRunning = val; }
    public void setItemToggled(boolean val) { this.mItemToggled = val; }
}
