package com.vasundhara.atf.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Monitors a device serial during execution and fires a callback when the device
 * goes offline. Used by all test runners to handle unexpected disconnections.
 */
@Component
public class DeviceWatchdog {

    private static final Logger log = LoggerFactory.getLogger(DeviceWatchdog.class);
    private static final int POLL_SECONDS = 3;

    private final AdbClient adb;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "watchdog-thread");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<String, ScheduledFuture<?>> watches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> foregroundGuards = new ConcurrentHashMap<>();

    public DeviceWatchdog(AdbClient adb) {
        this.adb = adb;
    }

    /**
     * Start watching {@code serial}. If the device disappears, {@code onDisconnect} is called
     * exactly once on a watchdog thread, then the watch is automatically cancelled.
     *
     * @param watchId    unique key (typically the session/run id)
     * @param serial     adb device serial to monitor
     * @param onDisconnect callback to invoke on disconnect
     */
    public void startWatch(String watchId, String serial, Runnable onDisconnect) {
        if (serial == null || serial.isBlank()) return;
        ScheduledFuture<?> f = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!adb.onlineDevices().contains(serial)) {
                    log.warn("Watchdog: device {} is no longer online (watch={})", serial, watchId);
                    stopWatch(watchId);
                    onDisconnect.run();
                }
            } catch (Exception e) {
                log.debug("Watchdog poll error for {}: {}", watchId, e.getMessage());
            }
        }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        watches.put(watchId, f);
    }

    /** Stop watching. Safe to call even if no watch is active for this id. */
    public void stopWatch(String watchId) {
        ScheduledFuture<?> f = watches.remove(watchId);
        if (f != null) f.cancel(false);
    }

    /**
     * Continuous foreground guard: while active, polls the device's foreground package roughly
     * once a second and force-stops ANY foreign app the instant it appears — Chrome, the Play
     * Store, Settings, Gallery, a file picker, or any other installed app — so the framework can
     * never be left operating inside another app. Fully generic (no app-specific package names)
     * via {@link ForeignAppPolicy#shouldForceStopForeign}; the app under test, genuine permission
     * dialogs, and protected system UI (launcher/SystemUI) are always left alone.
     *
     * <p>This is a device-level safety net that runs <em>independently</em> of any crawler's own
     * per-step foreground check, so a foreign app that pops up mid-step — during an ad's dismiss
     * wait, a screen-settle dwell, or between two crawl actions (an ad click-through, a share/
     * "rate us" deep link, a social-login handoff) — is killed within ~1s rather than lingering
     * until the crawler's next loop iteration notices it. It must NOT be run during AdMob/Firebase
     * Ads Testing, the one category permitted to briefly follow an ad off-app for click validation.
     *
     * @param onForeignKilled optional callback (foreign package name) invoked each time one is
     *                        force-stopped, e.g. to record an execution-log note
     */
    public void startForegroundGuard(String watchId, String serial, String appPkg,
                                     java.util.function.Consumer<String> onForeignKilled) {
        if (serial == null || serial.isBlank() || appPkg == null || appPkg.isBlank()) return;
        stopForegroundGuard(watchId); // never stack two guards on one id
        ScheduledFuture<?> f = scheduler.scheduleWithFixedDelay(() -> {
            try {
                String fg = adb.currentForegroundPackage(serial);
                if (ForeignAppPolicy.shouldForceStopForeign(fg, appPkg)) {
                    adb.forceStop(serial, fg);
                    log.warn("Foreground guard: force-stopped foreign app '{}' (watch={}) — staying inside {}",
                            fg, watchId, appPkg);
                    if (onForeignKilled != null) {
                        try { onForeignKilled.accept(fg); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                log.debug("Foreground guard poll error for {}: {}", watchId, e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
        foregroundGuards.put(watchId, f);
    }

    /** Stop the foreground guard for this id. Safe to call even if none is active. */
    public void stopForegroundGuard(String watchId) {
        ScheduledFuture<?> f = foregroundGuards.remove(watchId);
        if (f != null) f.cancel(false);
    }
}
