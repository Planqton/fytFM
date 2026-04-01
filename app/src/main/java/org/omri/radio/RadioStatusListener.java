package org.omri.radio;

import org.omri.tuner.Tuner;

public interface RadioStatusListener {
    void tunerAttached(Tuner tuner);
    void tunerDetached(Tuner tuner);
}
