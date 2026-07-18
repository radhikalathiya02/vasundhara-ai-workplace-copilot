package com.vasundhara.atf.wireless;

import com.vasundhara.atf.device.AdbClient;
import com.vasundhara.atf.util.ProcessRunner.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates real wireless ADB pairing and connection — the pairing-code flow, the
 * connect/disconnect/reconnect lifecycle, and mDNS discovery. The QR flow is handled by
 * {@link com.vasundhara.atf.device.QrPairingService}; this manager covers everything else and
 * shares the same {@link AdbBridge} status model so the UI sees one coherent device list.
 *
 * <p>All cryptographic pairing is performed by the adb server (via {@link AdbClient}); this class
 * is the controller/state-machine around it, with friendly errors and status transitions.
 */
@Service
public class WirelessPairingManager {

    private static final Logger log = LoggerFactory.getLogger(WirelessPairingManager.class);
    private static final Pattern IP_PORT = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})");

    private final AdbClient adb;
    private final AdbBridge bridge;

    public WirelessPairingManager(AdbClient adb, AdbBridge bridge) {
        this.adb = adb;
        this.bridge = bridge;
    }

    private record MdnsService(String name, String type, String ip, String port) {}

    /**
     * Pair with a device using its pairing IP:port + 6-digit code, then connect.
     * @param connectionPort optional; if blank, auto-discovered via adb mDNS.
     */
    public WirelessDevice pairWithCode(String host, String pairingPort, String connectionPort, String code) {
        if (isBlank(host)) throw new WirelessException("Device IP is required.");
        if (isBlank(pairingPort)) throw new WirelessException("Pairing Port (Port 1) is required for code pairing.");
        if (isBlank(code)) throw new WirelessException("Enter the 6-digit pairing code shown on the device.");

        bridge.mark(host, WirelessDeviceStatus.PAIRING, "Pairing with " + host + ":" + pairingPort);
        CommandResult pr;
        try {
            pr = adb.pair(host.trim(), pairingPort.trim(), code.trim());
        } catch (Exception e) {
            bridge.mark(host, WirelessDeviceStatus.FAILED, "Pairing error");
            throw new WirelessException("Could not run adb pair: " + e.getMessage());
        }
        String out = pr.combined();
        if (!out.toLowerCase().contains("successfully paired")) {
            bridge.mark(host, WirelessDeviceStatus.FAILED, "Pairing failed");
            throw new WirelessException(pairError(out), out);
        }
        log.info("Paired with {}:{}", host, pairingPort);

        // Resolve the connect port: explicit value, else mDNS discovery.
        String connPort = firstNonBlank(connectionPort, discoverConnectPort(host));
        if (isBlank(connPort)) {
            bridge.mark(host, WirelessDeviceStatus.FAILED, "No connection port");
            throw new WirelessException("Paired successfully, but the connection port could not be determined. "
                    + "Enter the Connection Port (Port 2) shown under Wireless debugging.", out);
        }
        return connectInternal(host.trim(), connPort.trim(), out);
    }

    /** Connect to an already-paired device (Android 10 needs no pairing; 11+ must already be paired). */
    public WirelessDevice connect(String host, String port) {
        if (isBlank(host)) throw new WirelessException("Device IP is required.");
        if (isBlank(port)) throw new WirelessException("Connection Port is required.");
        return connectInternal(host.trim(), port.trim(), null);
    }

    private WirelessDevice connectInternal(String host, String port, String pairOutput) {
        String serial = host + ":" + port;
        bridge.mark(serial, WirelessDeviceStatus.CONNECTING, "Connecting to " + serial);
        bridge.clearTransient(host); // pairing-phase key no longer needed
        CommandResult cr;
        try {
            cr = adb.connect(host, port);
        } catch (Exception e) {
            bridge.mark(serial, WirelessDeviceStatus.FAILED, "Connection error");
            throw new WirelessException("Could not run adb connect: " + e.getMessage(), pairOutput);
        }
        String out = cr.combined();
        boolean ok = out.toLowerCase().contains("connected to") || out.toLowerCase().contains("already connected");
        if (!ok || !waitOnline(serial, 12)) {
            bridge.mark(serial, WirelessDeviceStatus.FAILED, "Connection failed");
            throw new WirelessException(connectError(out), join(pairOutput, out));
        }
        bridge.clearTransient(serial);
        WirelessDevice dev = bridge.find(serial);
        log.info("Connected wirelessly: {}", serial);
        return dev != null ? dev
                : new WirelessDevice(serial, serial, host, "", true, WirelessDeviceStatus.CONNECTED, null);
    }

    /** Disconnect a wireless device by serial (ip:port). */
    public void disconnect(String serial) {
        if (isBlank(serial)) throw new WirelessException("Device serial is required to disconnect.");
        try {
            adb.disconnect(serial.trim());
        } catch (Exception e) {
            throw new WirelessException("Could not disconnect: " + e.getMessage());
        }
        bridge.clearTransient(serial);
        log.info("Disconnected: {}", serial);
    }

    /** Reconnect a previously paired wireless device (disconnect, then connect to the same ip:port). */
    public WirelessDevice reconnect(String serial) {
        if (isBlank(serial) || !serial.contains(":"))
            throw new WirelessException("A wireless serial (ip:port) is required to reconnect.");
        String host = serial.substring(0, serial.indexOf(':'));
        String port = serial.substring(serial.indexOf(':') + 1);
        try { adb.disconnect(serial); } catch (Exception ignored) { }
        return connectInternal(host, port, null);
    }

    /** Discover adb wireless services on the LAN via {@code adb mdns services}. */
    public List<MdnsService> discoverServices() {
        List<MdnsService> list = new ArrayList<>();
        String out;
        try {
            out = adb.adb(null, 10, "mdns", "services").combined();
        } catch (Exception e) {
            return list;
        }
        for (String line : out.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of discovered")) continue;
            Matcher m = IP_PORT.matcher(line);
            if (!m.find()) continue;
            String type = line.contains("_adb-tls-pairing") ? "_adb-tls-pairing._tcp"
                    : line.contains("_adb-tls-connect") ? "_adb-tls-connect._tcp"
                    : line.contains("_adb._tcp") ? "_adb._tcp" : "";
            list.add(new MdnsService(line.split("\\s+")[0], type, m.group(1), m.group(2)));
        }
        return list;
    }

    /** Auto-fill helper: pairing + connection endpoints discovered on the network. */
    public java.util.Map<String, Object> discover() {
        List<MdnsService> services = discoverServices();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("supported", bridge.ddmlibUp() || true); // mdns availability is reported separately
        boolean found = false;
        for (MdnsService s : services) {
            if (s.type().contains("_adb-tls-pairing")) {
                out.put("pairingIp", s.ip()); out.put("pairingPort", s.port()); found = true;
            } else if (s.type().contains("_adb-tls-connect")) {
                out.put("connectIp", s.ip()); out.put("connectionPort", s.port()); found = true;
            }
        }
        out.put("found", found);
        out.put("services", services.stream().map(s -> {
            java.util.Map<String, Object> sm = new java.util.LinkedHashMap<>();
            sm.put("name", s.name()); sm.put("type", s.type());
            sm.put("ip", s.ip()); sm.put("port", s.port());
            return sm;
        }).toList());
        return out;
    }

    private String discoverConnectPort(String host) {
        for (MdnsService s : discoverServices()) {
            if (s.type().contains("_adb-tls-connect") && s.ip().equals(host)) return s.port();
        }
        return null;
    }

    /** Poll adb until the serial is online, up to {@code seconds}. */
    private boolean waitOnline(String serial, int seconds) {
        for (int i = 0; i < seconds * 2; i++) {
            if (adb.onlineDevices().contains(serial)) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return adb.onlineDevices().contains(serial);
    }

    // ---- error message mapping -------------------------------------------

    private String pairError(String out) {
        String low = out.toLowerCase();
        if (low.contains("failed to authenticate") || low.contains("wrong"))
            return "Pairing rejected — the code did not match. Generate a fresh code on the device and try again.";
        if (low.contains("connection refused") || low.contains("cannot connect") || low.contains("unable to connect"))
            return "Couldn't reach the pairing port — check the IP/Port and that both devices are on the same WiFi.";
        if (low.contains("timeout") || low.isBlank())
            return "Pairing timed out — the code likely expired. Open a fresh code on the device and retry quickly.";
        return "Pairing failed. " + out.trim();
    }

    private String connectError(String out) {
        String low = out.toLowerCase();
        if (low.contains("failed to authenticate") || low.contains("missing pairing"))
            return "Connection refused — this device isn't paired yet. Pair it first (QR or code).";
        if (low.contains("connection refused") || low.contains("cannot connect") || low.contains("unable to connect"))
            return "Couldn't reach the connection port — verify Port 2 and the same-WiFi requirement.";
        if (low.contains("timeout") || low.isBlank())
            return "Connection timed out. Check the IP/port and network, then retry.";
        return "Connection failed. " + out.trim();
    }

    // ---- small utils ------------------------------------------------------

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String firstNonBlank(String a, String b) { return !isBlank(a) ? a : b; }
    private static String join(String a, String b) {
        if (isBlank(a)) return b; if (isBlank(b)) return a; return a + "\n" + b;
    }
}
