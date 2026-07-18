package com.vasundhara.atf.wireless;

/**
 * Thrown when a wireless pairing/connection operation fails. The message is safe to show
 * to the end user; {@link #adbOutput} carries the raw adb output for diagnostics/logging.
 */
public class WirelessException extends RuntimeException {

    private final String adbOutput;

    public WirelessException(String userMessage) {
        this(userMessage, null);
    }

    public WirelessException(String userMessage, String adbOutput) {
        super(userMessage);
        this.adbOutput = adbOutput;
    }

    public String adbOutput() { return adbOutput; }
}
