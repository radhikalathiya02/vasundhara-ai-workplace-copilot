package com.vasundhara.atf.model;

/** Severity ranking for an individual finding, ordered most-to-least serious. */
public enum Severity {
    CRITICAL(4),
    HIGH(3),
    MEDIUM(2),
    LOW(1),
    INFO(0);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
