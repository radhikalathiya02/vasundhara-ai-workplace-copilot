package com.vasundhara.atf.aiproduct;

public enum MergeStrategy {
    SKIP_DUPLICATES,  // Add only missing screens, preserve existing
    OVERWRITE,        // Replace all UI files, keep build files
    READ_AND_MERGE    // Claude reads existing code, merges intelligently
}
