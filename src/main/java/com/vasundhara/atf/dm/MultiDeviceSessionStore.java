package com.vasundhara.atf.dm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MultiDeviceSessionStore {

    private final ConcurrentHashMap<String, MultiDeviceSession> map = new ConcurrentHashMap<>();

    public void save(MultiDeviceSession s)        { map.put(s.getId(), s); }
    public MultiDeviceSession get(String id)       { return map.get(id); }
    public List<MultiDeviceSession> all()          { return new ArrayList<>(map.values()); }
}
