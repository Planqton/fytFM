package org.omri.radioservice;

import java.util.List;
import org.omri.radioservice.metadata.SbtItem;

public interface RadioServiceDabEdi extends RadioServiceDab {

    interface SbtCallback {
        void sbtEnabled();
        void sbtItemAdded(SbtItem sbtItem);
        void sbtItemInvalid(SbtItem sbtItem);
        void streamDabTime(long j);
    }

    void addSbtCallback(SbtCallback sbtCallback);
    long getRealtimePosixMs();
    List<SbtItem> getSbtItems();
    long getSbtMax();
    String getSbtToken();
    String getUrl();
    void pauseSbt(boolean z);
    void removeSbtCallback(SbtCallback sbtCallback);
    boolean sbtEnabled();
    void seekSbt(long j);
    void setInitialSbtOffset(long j);
    void setInitialSbtToggleId(long j);
    void setInitialSbtToken(String str);
    void setInitialTimePosix(long j);
    void setToggleSbt(long j);
}
