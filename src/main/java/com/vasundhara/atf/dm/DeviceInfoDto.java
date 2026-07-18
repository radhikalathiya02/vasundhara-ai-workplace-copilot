package com.vasundhara.atf.dm;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Connected device / emulator as seen by adb, enriched with hardware properties. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceInfoDto(
        String  serial,
        String  displayName,
        String  model,
        String  manufacturer,
        String  androidVersion,
        int     apiLevel,
        String  status,        // online | offline | unauthorized | connecting
        long    ramMb,
        String  resolution,
        String  abi,
        boolean isEmulator,
        String  avdName        // null for physical devices
) {}
