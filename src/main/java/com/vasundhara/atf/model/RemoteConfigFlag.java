package com.vasundhara.atf.model;

/**
 * A single Firebase Remote Config parameter fetched from the project template.
 * Carries the key, its default value, the declared value type and an optional
 * human-readable description parsed from the template JSON.
 */
public class RemoteConfigFlag {

    private String key;
    private String defaultValue;
    /** One of: STRING | BOOLEAN | NUMBER | JSON */
    private String valueType;
    private String description;

    public RemoteConfigFlag() {}

    public RemoteConfigFlag(String key, String defaultValue, String valueType, String description) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.valueType = valueType;
        this.description = description;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
