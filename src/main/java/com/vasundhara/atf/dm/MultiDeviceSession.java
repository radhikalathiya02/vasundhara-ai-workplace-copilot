package com.vasundhara.atf.dm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks a parallel multi-device smoke-test run. */
public class MultiDeviceSession {

    private final String id;
    private final String apkFileName;
    private volatile String state = "RUNNING"; // RUNNING|DONE|ERROR
    private volatile String overallStatus = "—"; // PASS|FAIL|PARTIAL
    private final List<DeviceSlotResult> slots;
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final long createdAt;

    public MultiDeviceSession(String id, String apkFileName,
                               List<DeviceSlotResult> slots, long createdAt) {
        this.id = id;
        this.apkFileName = apkFileName;
        this.slots = slots;
        this.createdAt = createdAt;
    }

    public String getId()              { return id; }
    public String getApkFileName()     { return apkFileName; }
    public String getState()           { return state; }
    public void   setState(String s)   { this.state = s; }
    public String getOverallStatus()   { return overallStatus; }
    public void   setOverallStatus(String s) { this.overallStatus = s; }
    public List<DeviceSlotResult> getSlots() { return slots; }
    public List<String> getLogs()      { return logs; }
    public long getCreatedAt()         { return createdAt; }
    public void addLog(String msg)     { logs.add(msg); }
}
