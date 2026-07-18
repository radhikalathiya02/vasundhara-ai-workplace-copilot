package com.vasundhara.atf.device;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.vasundhara.atf.util.ProcessRunner.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements Android's "Pair device with QR code" the same way Android Studio does it.
 *
 * <p>Mechanism (the key point that makes this work with plain {@code adb}, no SPAKE2 server
 * on our side): in <b>both</b> the QR flow and the pairing-code flow the <b>phone</b> is the
 * pairing server. We only act as the client.
 * <ol>
 *   <li>We generate a service name + password and encode them as
 *       {@code WIFI:T:ADB;S:<service>;P:<password>;;} into a QR.</li>
 *   <li>The phone scans the QR and starts advertising an mDNS service of type
 *       {@code _adb-tls-pairing._tcp} (named after our service name), guarded by our password.</li>
 *   <li>We discover that advertisement via {@code adb mdns services}, then run
 *       {@code adb pair <ip>:<port> <password>} — adb performs the SPAKE2/TLS client handshake.</li>
 *   <li>After pairing, the phone advertises {@code _adb-tls-connect._tcp}; we {@code adb connect}
 *       to it (or it auto-connects) and the device appears in {@code adb devices}.</li>
 * </ol>
 * This requires the host's adb to support mDNS ({@code adb mdns check}); it does on modern
 * platform-tools. No Android Studio, ddmlib, or custom crypto server is needed.
 */
@Service
public class QrPairingService {

    private static final Logger log = LoggerFactory.getLogger(QrPairingService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int QR_SIZE = 250;
    private static final long SCAN_WINDOW_MS = 120_000; // how long we wait for the phone to scan

    private final AdbClient adb;
    private volatile Session active;

    public QrPairingService(AdbClient adb) { this.adb = adb; }

    /** State machine for one QR pairing attempt. */
    private static final class Session {
        final String serviceName, password, data, qrBase64;
        final long createdAt;
        final Set<String> baselinePairing;  // pairing services present before the phone scanned
        final Set<String> baselineDevices;   // adb devices present before pairing
        volatile String phase = "WAITING_SCAN"; // WAITING_SCAN -> PAIRING -> CONNECTING -> CONNECTED / FAILED
        volatile String pairedIp;
        volatile String lastError;
        volatile String pairOutput;
        Session(String serviceName, String password, String data, String qrBase64, long createdAt,
                Set<String> baselinePairing, Set<String> baselineDevices) {
            this.serviceName = serviceName; this.password = password; this.data = data;
            this.qrBase64 = qrBase64; this.createdAt = createdAt;
            this.baselinePairing = baselinePairing; this.baselineDevices = baselineDevices;
        }
    }

    /** A parsed mDNS service line: instance name, type, ip, port. */
    private record MdnsService(String name, String type, String ip, String port) {}

    /** Start a new pairing session: build the QR and snapshot the current mDNS/device state. */
    public synchronized Map<String, Object> start() {
        String serviceName = "ADB_WIFI_" + randomToken(6);
        String password = randomToken(8);
        String data = "WIFI:T:ADB;S:" + serviceName + ";P:" + password + ";;";
        String qr = qrPng(data);

        Set<String> basePairing = new HashSet<>();
        for (MdnsService m : scanMdns()) {
            if (m.type().contains("_adb-tls-pairing")) basePairing.add(m.name() + "@" + m.ip() + ":" + m.port());
        }
        Set<String> baseDevices = new HashSet<>(adb.onlineDevices());

        active = new Session(serviceName, password, data, qr, System.currentTimeMillis(), basePairing, baseDevices);
        log.info("QR pairing session started: service={}", serviceName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("qr", qr);
        out.put("serviceName", serviceName);
        out.put("ttlMs", SCAN_WINDOW_MS);
        out.put("mdnsSupported", mdnsSupported());
        return out;
    }

    /**
     * Poll the pairing state machine. Each call advances it by one step so the HTTP poll loop
     * (every ~2.5s) drives discovery -> pair -> connect without blocking for the whole handshake.
     */
    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Session s = active;
        if (s == null) { out.put("state", "idle"); return out; }

        // A device may have come online already (auto-connect after a prior pair).
        String newDevice = firstNewDevice(s);
        if (newDevice != null) return connected(s, newDevice, out);

        List<MdnsService> services = scanMdns();

        // --- Step 1: discover the phone's pairing advertisement (named after our QR) ----------
        if ("WAITING_SCAN".equals(s.phase)) {
            MdnsService target = null;
            for (MdnsService m : services) {
                if (!m.type().contains("_adb-tls-pairing")) continue;
                boolean nameMatch = m.name().equalsIgnoreCase(s.serviceName);
                boolean isNew = !s.baselinePairing.contains(m.name() + "@" + m.ip() + ":" + m.port());
                if (nameMatch || isNew) { target = m; break; }
            }
            if (target == null) {
                boolean expired = System.currentTimeMillis() - s.createdAt > SCAN_WINDOW_MS;
                out.put("state", expired ? "expired" : "waiting");
                out.put("ageMs", System.currentTimeMillis() - s.createdAt);
                return out;
            }
            // --- Step 2: pair as the client using the QR's password --------------------------
            s.phase = "PAIRING";
            log.info("QR pairing: discovered {} at {}:{}, running adb pair", target.name(), target.ip(), target.port());
            CommandResult res = adb.pair(target.ip(), target.port(), s.password);
            s.pairOutput = res.combined();
            if (res.combined().toLowerCase().contains("successfully paired")) {
                s.phase = "CONNECTING";
                s.pairedIp = target.ip();
            } else {
                s.phase = "FAILED";
                s.lastError = extractPairError(res.combined());
                out.put("state", "failed");
                out.put("reason", s.lastError);
                out.put("output", res.combined());
                active = null;
                return out;
            }
        }

        // --- Step 3: connect to the now-paired device -----------------------------------------
        if ("CONNECTING".equals(s.phase)) {
            // Prefer the device's advertised connect port; otherwise it may auto-connect.
            for (MdnsService m : services) {
                if (m.type().contains("_adb-tls-connect")
                        && (s.pairedIp == null || m.ip().equals(s.pairedIp))) {
                    adb.connect(m.ip(), m.port());
                    break;
                }
            }
            String dev = firstNewDevice(s);
            if (dev != null) return connected(s, dev, out);
            out.put("state", "pairing"); // paired, waiting for the connection to come online
            out.put("detail", "Paired - establishing connection...");
            return out;
        }

        out.put("state", "waiting");
        out.put("ageMs", System.currentTimeMillis() - s.createdAt);
        return out;
    }

    private Map<String, Object> connected(Session s, String serial, Map<String, Object> out) {
        s.phase = "CONNECTED";
        out.put("state", "connected");
        out.put("serial", serial);
        String model = adb.getProp(serial, "ro.product.model");
        String maker = adb.getProp(serial, "ro.product.manufacturer");
        String rel = adb.getProp(serial, "ro.build.version.release");
        String api = adb.getProp(serial, "ro.build.version.sdk");
        String name = (maker + " " + model).trim();
        out.put("name", name.isEmpty() ? serial : name);
        out.put("ip", serial.contains(":") ? serial.substring(0, serial.indexOf(':')) : serial);
        out.put("android", rel.isEmpty() ? "" : ("Android " + rel + (api.isEmpty() ? "" : " (API " + api + ")")));
        active = null; // consume the session
        log.info("QR pairing complete: {}", serial);
        return out;
    }

    /** A device in {@code adb devices} that was not present when this session started. */
    private String firstNewDevice(Session s) {
        for (String serial : adb.onlineDevices()) {
            if (!s.baselineDevices.contains(serial)) return serial;
        }
        return null;
    }

    /** Parse {@code adb mdns services} into structured entries. */
    private List<MdnsService> scanMdns() {
        List<MdnsService> list = new ArrayList<>();
        String out;
        try {
            out = adb.adb(null, 10, "mdns", "services").combined();
        } catch (Exception e) {
            return list;
        }
        Pattern ipPort = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})");
        for (String line : out.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of discovered")) continue;
            Matcher m = ipPort.matcher(line);
            if (!m.find()) continue;
            String ip = m.group(1), port = m.group(2);
            String type = line.contains("_adb-tls-pairing") ? "_adb-tls-pairing._tcp"
                        : line.contains("_adb-tls-connect") ? "_adb-tls-connect._tcp"
                        : line.contains("_adb._tcp") ? "_adb._tcp" : "";
            // The instance name is the first whitespace-delimited token on the line.
            String name = line.split("\\s+")[0];
            list.add(new MdnsService(name, type, ip, port));
        }
        return list;
    }

    private String extractPairError(String output) {
        String low = output.toLowerCase();
        if (low.contains("failed to authenticate") || low.contains("wrong password"))
            return "Pairing rejected - the code/password did not match. Generate a fresh QR and rescan.";
        if (low.contains("connection refused") || low.contains("unable to connect") || low.contains("cannot connect"))
            return "Could not reach the device's pairing port - make sure the phone is still on the QR screen and on the same WiFi.";
        if (low.contains("timeout") || low.isBlank())
            return "Pairing timed out. Keep the 'Pair device with QR code' screen open and rescan.";
        return "Pairing failed. " + output.trim();
    }

    /** Whether this machine's adb reports mDNS support (needed for QR/wireless discovery). */
    public boolean mdnsSupported() {
        try {
            String o = adb.adb(null, 10, "mdns", "check").combined().toLowerCase();
            return o.contains("mdns daemon") || o.contains("supported") || o.contains("available") || o.contains("zeroconf");
        } catch (Exception e) {
            return false;
        }
    }

    private String qrPng(String data) {
        try {
            Map<EncodeHintType, Object> hints = new LinkedHashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.warn("QR generation failed: {}", e.toString());
            return "";
        }
    }

    private String randomToken(int len) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(RNG.nextInt(chars.length())));
        return sb.toString();
    }
}
