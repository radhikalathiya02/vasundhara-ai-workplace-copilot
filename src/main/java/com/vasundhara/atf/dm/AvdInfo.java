package com.vasundhara.atf.dm;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Snapshot of one Android Virtual Device, both running and stopped. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvdInfo(
        String name,
        String displayName,
        int    apiLevel,
        String androidVersion,
        String device,
        String abi,
        String sdcard,
        int    ramMb,
        boolean running,
        String serial      // null when the emulator is not currently running
) {}
