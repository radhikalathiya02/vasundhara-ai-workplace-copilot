package com.vasundhara.atf.wireless;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A snapshot of one wireless (or USB) device as the wireless module sees it.
 * Immutable value object; the bridge rebuilds these on every device change.
 */
public final class WirelessDevice {

    private final String serial;          // adb serial (ip:port for wireless, usb id otherwise)
    private final String name;            // human-friendly "Manufacturer Model"
    private final String ip;              // ip portion of a wireless serial, or "" for USB
    private final String androidVersion;  // e.g. "Android 14 (API 34)" or ""
    private final boolean wireless;
    private final WirelessDeviceStatus status;
    private final String detail;          // optional status detail / last error

    public WirelessDevice(String serial, String name, String ip, String androidVersion,
                          boolean wireless, WirelessDeviceStatus status, String detail) {
        this.serial = serial;
        this.name = name;
        this.ip = ip;
        this.androidVersion = androidVersion;
        this.wireless = wireless;
        this.status = status;
        this.detail = detail;
    }

    public String serial() { return serial; }
    public String name() { return name; }
    public String ip() { return ip; }
    public String androidVersion() { return androidVersion; }
    public boolean wireless() { return wireless; }
    public WirelessDeviceStatus status() { return status; }
    public String detail() { return detail; }

    public WirelessDevice withStatus(WirelessDeviceStatus s, String detail) {
        return new WirelessDevice(serial, name, ip, androidVersion, wireless, s, detail);
    }

    /** Shape returned to the dashboard JSON API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serial", serial);
        m.put("name", name == null || name.isBlank() ? serial : name);
        m.put("ip", ip == null ? "" : ip);
        m.put("androidVersion", androidVersion == null ? "" : androidVersion);
        m.put("wireless", wireless);
        m.put("status", status.name());
        m.put("statusLabel", label(status));
        if (detail != null && !detail.isBlank()) m.put("detail", detail);
        return m;
    }

    private static String label(WirelessDeviceStatus s) {
        return switch (s) {
            case AVAILABLE -> "Available";
            case PAIRING -> "Pairing…";
            case PAIRED -> "Paired";
            case CONNECTING -> "Connecting…";
            case CONNECTED -> "Connected";
            case OFFLINE -> "Offline";
            case UNAUTHORIZED -> "Unauthorized";
            case DISCONNECTED -> "Disconnected";
            case FAILED -> "Failed";
        };
    }
}
