package org.omri.radioservice.metadata;

import java.util.List;

public interface TextualDabDynamicLabel extends Textual {
    List<TextualDabDynamicLabelPlusItem> getDlPlusItems();
    int getTagCount();
    boolean hasTags();
    boolean itemRunning();
    boolean itemToggled();
}
