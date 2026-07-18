package com.vasundhara.atf.wireless;

import com.android.ddmlib.AdbInitOptions;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.util.ProcessRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Owns the adb-server connection through <b>ddmlib</b> — the same library Android Studio uses —
 * and exposes a live, status-annotated view of all attached devices.
 *
 * <p>ddmlib gives us event-driven device tracking (connected / disconnected / state changed) and
 * manages the adb server lifecycle. Pairing/connection crypto is still performed by the adb server
 * itself (driven via {@link AdbClient}); ddmlib is the device-state source of truth, exactly as in
 * Android Studio. If ddmlib cannot initialize for any reason, the bridge degrades gracefully to the
 * adb CLI so the feature keeps working.
 */
@Component
public class AdbBridge implements AndroidDebugBridge.IDeviceChangeListener {

    private static final Logger log = LoggerFactory.getLogger(AdbBridge.class);

    private final AtfProperties props;
    private final AdbClient adb;

    private volatile AndroidDebugBridge bridge;
    private volatile boolean ddmlibUp = false;

    /** Transient statuses for serials/hosts mid pair/connect, overlaid on the ddmlib live view. */
    private final Map<String, WirelessDeviceStatus> transientStatus = new ConcurrentHashMap<>();
    private final Map<String, String> transientDetail = new ConcurrentHashMap<>();

    public AdbBridge(AtfProperties props, AdbClient adb) {
        this.props = props;
        this.adb = adb;
    }

    // ---- lifecycle --------------------------------------------------------

    @PostConstruct
    public synchronized void init() {
        try {
            String adbPath = resolveAdbPath();
            try {
                AndroidDebugBridge.init(AdbInitOptions.builder()
                        .setClientSupportEnabled(false) // we don't need JDWP/Client tracking
                        .build());
            } catch (IllegalStateException alreadyInit) {
                // init() is once-per-JVM; fine if something already did it.
                log.debug("ddmlib already initialized: {}", alreadyInit.getMessage());
            }
            AndroidDebugBridge.addDeviceChangeListener(this);
            bridge = AndroidDebugBridge.createBridge(adbPath, false, 30, TimeUnit.SECONDS);
            if (bridge == null) {
                log.warn("ddmlib createBridge returned null (adb at '{}'); using adb CLI fallback.", adbPath);
                return;
            }
            // Give adb a moment to hand ddmlib the initial device list.
            for (int i = 0; i < 50 && !bridge.hasInitialDeviceList(); i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            ddmlibUp = true;
            log.info("ddmlib bridge up (adb='{}', initialDeviceList={})", adbPath, bridge.hasInitialDeviceList());
        } catch (Throwable t) {
            // Never let a ddmlib problem take down the app — degrade to the CLI.
            log.warn("ddmlib bridge failed to start ({}); wireless module will use the adb CLI.", t.toString());
            ddmlibUp = false;
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        try {
            AndroidDebugBridge.removeDeviceChangeListener(this);
            AndroidDebugBridge.terminate();
        } catch (Throwable t) {
            log.debug("ddmlib terminate: {}", t.toString());
        }
    }

    /** Resolve a usable adb executable path: configured value if absolute, else locate on PATH/SDK. */
    private String resolveAdbPath() {
        String configured = props.getAdbPath();
        if (configured != null && configured.contains(File.separator) && new File(configured).canExecute()) {
            return configured;
        }
        // `which adb`
        try {
            String which = ProcessRunner.run(List.of("which", "adb"), 5).stdout().trim();
            if (!which.isBlank() && new File(which).canExecute()) return which;
        } catch (Exception ignored) { }
        // Common SDK install locations.
        String home = System.getProperty("user.home", "");
        for (String c : new String[]{
                home + "/Library/Android/sdk/platform-tools/adb",
                home + "/Android/Sdk/platform-tools/adb",
                System.getenv("ANDROID_HOME") == null ? null : System.getenv("ANDROID_HOME") + "/platform-tools/adb"}) {
            if (c != null && new File(c).canExecute()) return c;
        }
        return configured == null ? "adb" : configured; // last resort; ddmlib will try PATH
    }

    // ---- transient status overlay ----------------------------------------

    public void mark(String key, WirelessDeviceStatus status, String detail) {
        if (key == null || key.isBlank()) return;
        transientStatus.put(key, status);
        if (detail != null) transientDetail.put(key, detail);
        log.info("wireless status [{}] -> {}{}", key, status, detail == null ? "" : " (" + detail + ")");
    }

    public void clearTransient(String key) {
        if (key == null) return;
        transientStatus.remove(key);
        transientDetail.remove(key);
    }

    // ---- device view ------------------------------------------------------

    /** All known devices with their current status (ddmlib live view, or adb CLI fallback). */
    public synchronized List<WirelessDevice> devices() {
        Map<String, WirelessDevice> bySerial = new LinkedHashMap<>();

        if (ddmlibUp && bridge != null) {
            IDevice[] list = bridge.getDevices();
            for (IDevice d : list) {
                String serial = d.getSerialNumber();
                bySerial.put(serial, fromIDevice(d));
            }
        } else {
            for (String serial : adb.onlineDevices()) {
                bySerial.put(serial, cliDevice(serial));
            }
        }

        // Overlay transient pair/connect statuses (covers devices not yet in adb's list).
        for (Map.Entry<String, WirelessDeviceStatus> e : transientStatus.entrySet()) {
            String key = e.getKey();
            WirelessDeviceStatus st = e.getValue();
            String detail = transientDetail.get(key);
            WirelessDevice existing = bySerial.get(key);
            if (existing != null) {
                // Don't downgrade a CONNECTED device with a stale transient state.
                if (existing.status() != WirelessDeviceStatus.CONNECTED) {
                    bySerial.put(key, existing.withStatus(st, detail));
                }
            } else if (st != WirelessDeviceStatus.CONNECTED) {
                String ip = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
                bySerial.put(key, new WirelessDevice(key, key, ip, "", true, st, detail));
            }
        }
        return new ArrayList<>(bySerial.values());
    }

    /** Find a connected device by serial, or null. */
    public WirelessDevice find(String serial) {
        for (WirelessDevice d : devices()) if (d.serial().equals(serial)) return d;
        return null;
    }

    private WirelessDevice fromIDevice(IDevice d) {
        String serial = d.getSerialNumber();
        boolean wireless = serial.contains(":") || serial.contains("._tcp") || serial.startsWith("adb-");
        WirelessDeviceStatus status = switch (d.getState()) {
            case ONLINE -> WirelessDeviceStatus.CONNECTED;
            case OFFLINE -> WirelessDeviceStatus.OFFLINE;
            case UNAUTHORIZED, AUTHORIZING -> WirelessDeviceStatus.UNAUTHORIZED;
            case DISCONNECTED -> WirelessDeviceStatus.DISCONNECTED;
            default -> WirelessDeviceStatus.OFFLINE;
        };
        String maker = safeProp(d, "ro.product.manufacturer", serial);
        String model = safeProp(d, "ro.product.model", serial);
        String rel = safeProp(d, "ro.build.version.release", serial);
        String api = safeProp(d, "ro.build.version.sdk", serial);
        String name = (orEmpty(maker) + " " + orEmpty(model)).trim();
        String ip = serial.contains(":") ? serial.substring(0, serial.indexOf(':')) : "";
        return new WirelessDevice(serial, name.isEmpty() ? serial : name, ip, androidLabel(rel, api), wireless, status, null);
    }

    private WirelessDevice cliDevice(String serial) {
        boolean wireless = serial.contains(":");
        String maker = adb.getProp(serial, "ro.product.manufacturer");
        String model = adb.getProp(serial, "ro.product.model");
        String rel = adb.getProp(serial, "ro.build.version.release");
        String api = adb.getProp(serial, "ro.build.version.sdk");
        String name = (orEmpty(maker) + " " + orEmpty(model)).trim();
        String ip = wireless ? serial.substring(0, serial.indexOf(':')) : "";
        return new WirelessDevice(serial, name.isEmpty() ? serial : name, ip, androidLabel(rel, api),
                wireless, WirelessDeviceStatus.CONNECTED, null);
    }

    /** ddmlib props can be null right after connect; fall back to a live getprop shell. */
    private String safeProp(IDevice d, String key, String serial) {
        try {
            String v = d.getProperty(key);
            if (v != null && !v.isBlank()) return v;
        } catch (Exception ignored) { }
        try { return adb.getProp(serial, key); } catch (Exception e) { return ""; }
    }

    private static String orEmpty(String s) { return s == null ? "" : s.trim(); }

    private static String androidLabel(String release, String api) {
        if (release == null || release.isBlank()) return "";
        return "Android " + release + (api == null || api.isBlank() ? "" : " (API " + api + ")");
    }

    public boolean ddmlibUp() { return ddmlibUp; }

    // ---- IDeviceChangeListener (logging / observability) ------------------

    @Override public void deviceConnected(IDevice device) {
        log.info("ddmlib: device connected {} ({})", device.getSerialNumber(), device.getState());
        clearTransient(device.getSerialNumber());
    }

    @Override public void deviceDisconnected(IDevice device) {
        log.info("ddmlib: device disconnected {}", device.getSerialNumber());
    }

    @Override public void deviceChanged(IDevice device, int changeMask) {
        if ((changeMask & IDevice.CHANGE_STATE) != 0) {
            log.debug("ddmlib: device {} state -> {}", device.getSerialNumber(), device.getState());
            if (device.getState() == IDevice.DeviceState.ONLINE) clearTransient(device.getSerialNumber());
        }
    }

    /** Diagnostics for the status endpoint. */
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ddmlibUp", ddmlibUp);
        m.put("adbPath", resolveAdbPath());
        m.put("bridgeConnected", bridge != null && bridge.isConnected());
        return m;
    }
}
