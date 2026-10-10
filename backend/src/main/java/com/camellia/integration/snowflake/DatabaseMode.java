package com.camellia.integration.snowflake;

/**
 * Which persistence backend is active. LOCAL_FALLBACK exists only so the app can be developed without
 * credentials; it is always labeled as such in the API and UI and is never reported as Snowflake.
 */
public enum DatabaseMode {
    SNOWFLAKE("Snowflake"),
    LOCAL_FALLBACK("Local development fallback (H2) — NOT Snowflake");

    private final String label;

    DatabaseMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
