package com.vasundhara.atf.device;

import com.vasundhara.atf.config.AtfProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Chooses which connected device a run targets and exposes its hardware/OS profile,
 * which the compatibility category compares against the APK's declared support.
 */
@Component
public class DeviceManager {

    private final AdbClient adb;
    private final AtfProperties props;

    public DeviceManager(AdbClient adb, AtfProperties props) {
        this.adb = adb;
        this.props = props;
    }

    public record DeviceInfo(
            String serial, String model, String manufacturer,
            String androidRelease, int sdkInt, int widthPx, int heightPx, int densityDpi,
            List<String> abis) {
    }

    /** Pick the configured serial if present and online, otherwise the first online device. */
    public Optional<String> selectDevice() {
        return selectDevice(null);
    }

    /**
     * Select a target device, respecting an explicit caller preference first,
     * then the configured serial, then the first available online device.
     *
     * @param preferredSerial serial chosen by the user in the dashboard (may be null/blank)
     */
    public Optional<String> selectDevice(String preferredSerial) {
        List<String> online = adb.onlineDevices();
        if (online.isEmpty()) return Optional.empty();
        if (preferredSerial != null && !preferredSerial.isBlank() && online.contains(preferredSerial))
            return Optional.of(preferredSerial);
        String configured = props.getDeviceSerial();
        if (configured != null && !configured.isBlank() && online.contains(configured))
            return Optional.of(configured);
        return Optional.of(online.get(0));
    }

    public DeviceInfo profile(String serial) {
        int[] size = adb.screenSize(serial);
        String abiList = adb.getProp(serial, "ro.product.cpu.abilist");
        return new DeviceInfo(
                serial,
                adb.getProp(serial, "ro.product.model"),
                adb.getProp(serial, "ro.product.manufacturer"),
                adb.getProp(serial, "ro.build.version.release"),
                parseInt(adb.getProp(serial, "ro.build.version.sdk")),
                size[0], size[1],
                adb.density(serial),
                abiList.isBlank() ? List.of() : List.of(abiList.split(",")));
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
