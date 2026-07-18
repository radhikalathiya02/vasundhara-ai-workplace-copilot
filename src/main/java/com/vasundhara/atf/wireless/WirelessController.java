package com.vasundhara.atf.wireless;

import com.vasundhara.atf.device.QrPairingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the wireless debugging feature: device status, QR pairing, code pairing,
 * and the connect / disconnect / reconnect lifecycle. No endpoint requires the user to run a
 * manual adb command.
 */
@RestController
@RequestMapping("/api/wireless")
public class WirelessController {

    private static final Logger log = LoggerFactory.getLogger(WirelessController.class);

    private final WirelessPairingManager manager;
    private final QrPairingService qrPairing;
    private final AdbBridge bridge;

    public WirelessController(WirelessPairingManager manager, QrPairingService qrPairing, AdbBridge bridge) {
        this.manager = manager;
        this.qrPairing = qrPairing;
        this.bridge = bridge;
    }

    /** Live device list with per-device status (auto-detects paired/connected devices). */
    @GetMapping("/devices")
    public Map<String, Object> devices() {
        List<WirelessDevice> list = bridge.devices();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", list.stream().map(WirelessDevice::toMap).toList());
        out.put("ddmlibUp", bridge.ddmlibUp());
        out.put("mdnsSupported", qrPairing.mdnsSupported());
        return out;
    }

    /** Bridge / adb health for diagnostics. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> h = bridge.health();
        h.put("mdnsSupported", qrPairing.mdnsSupported());
        return h;
    }

    /** Auto-discover pairing/connection endpoints on the LAN (so users needn't type ports). */
    @GetMapping("/discover")
    public Map<String, Object> discover() {
        Map<String, Object> out = manager.discover();
        out.put("mdnsSupported", qrPairing.mdnsSupported());
        return out;
    }

    // ---- QR pairing -------------------------------------------------------

    @PostMapping("/qr/start")
    public Map<String, Object> qrStart() {
        return qrPairing.start();
    }

    @GetMapping("/qr/status")
    public Map<String, Object> qrStatus() {
        return qrPairing.status();
    }

    // ---- code pairing -----------------------------------------------------

    @PostMapping("/pair")
    public Map<String, Object> pair(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        try {
            WirelessDevice d = manager.pairWithCode(
                    b.get("host"), b.get("pairingPort"), b.get("connectionPort"), b.get("code"));
            return success(d);
        } catch (WirelessException e) {
            return failure(e);
        }
    }

    // ---- connection lifecycle --------------------------------------------

    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        try {
            // accept either {host,port} or {host,connectionPort}
            String port = b.getOrDefault("port", b.get("connectionPort"));
            WirelessDevice d = manager.connect(b.get("host"), port);
            return success(d);
        } catch (WirelessException e) {
            return failure(e);
        }
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        try {
            manager.disconnect(b.get("serial"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "disconnected");
            out.put("serial", b.get("serial"));
            return out;
        } catch (WirelessException e) {
            return failure(e);
        }
    }

    @PostMapping("/reconnect")
    public Map<String, Object> reconnect(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> b = body == null ? Map.of() : body;
        try {
            WirelessDevice d = manager.reconnect(b.get("serial"));
            return success(d);
        } catch (WirelessException e) {
            return failure(e);
        }
    }

    // ---- response helpers -------------------------------------------------

    private Map<String, Object> success(WirelessDevice d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "connected");
        out.put("device", d.toMap());
        return out;
    }

    private Map<String, Object> failure(WirelessException e) {
        log.warn("wireless op failed: {}", e.getMessage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "failed");
        out.put("reason", e.getMessage());
        if (e.adbOutput() != null) out.put("output", e.adbOutput());
        return out;
    }
}
