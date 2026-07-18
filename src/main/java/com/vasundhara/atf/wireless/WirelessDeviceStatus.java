package com.vasundhara.atf.wireless;

/**
 * Lifecycle states a wireless device can be in, mirroring what Android Studio's
 * "Pair devices using Wi-Fi" surface shows.
 */
public enum WirelessDeviceStatus {
    /** Discovered on the network (advertising adb mDNS) but not yet paired/connected. */
    AVAILABLE,
    /** A pairing handshake is currently in progress. */
    PAIRING,
    /** Pairing succeeded; a connection is being established. */
    PAIRED,
    /** Connection in progress. */
    CONNECTING,
    /** Online and usable for tests (adb {@code device} state). */
    CONNECTED,
    /** Known to adb but currently offline. */
    OFFLINE,
    /** Connected but the user has not authorized USB/wireless debugging. */
    UNAUTHORIZED,
    /** Previously connected, now dropped. */
    DISCONNECTED,
    /** The last pairing/connection attempt failed. */
    FAILED
}
