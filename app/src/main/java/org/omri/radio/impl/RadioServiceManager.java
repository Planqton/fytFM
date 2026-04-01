package org.omri.radio.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceType;

/**
 * Manages discovered radio services (stations).
 * Minimal stub matching DAB-Z's RadioServiceManager.
 */
public class RadioServiceManager {
    private static RadioServiceManager INSTANCE;
    private final ConcurrentHashMap<RadioServiceType, CopyOnWriteArrayList<RadioService>> mServicesMap = new ConcurrentHashMap<>();

    public static synchronized RadioServiceManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RadioServiceManager();
        }
        return INSTANCE;
    }

    public void addRadioService(RadioService service) {
        RadioServiceType type = service.getRadioServiceType();
        mServicesMap.putIfAbsent(type, new CopyOnWriteArrayList<>());
        CopyOnWriteArrayList<RadioService> list = mServicesMap.get(type);
        if (!list.contains(service)) {
            list.add(service);
        }
    }

    public List<RadioService> getRadioServices(RadioServiceType type) {
        CopyOnWriteArrayList<RadioService> list = mServicesMap.get(type);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public boolean isServiceListReady(RadioServiceType type) {
        CopyOnWriteArrayList<RadioService> list = mServicesMap.get(type);
        return list != null && !list.isEmpty();
    }

    public void serializeServices(RadioServiceType type) {
        // Stub - persistence handled by PresetRepository
    }
}
