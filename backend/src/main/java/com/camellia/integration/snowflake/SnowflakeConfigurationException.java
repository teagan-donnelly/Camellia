package com.camellia.integration.snowflake;

/** Raised at startup when Snowflake is required but its configuration is incomplete or invalid. */
public class SnowflakeConfigurationException extends RuntimeException {
    public SnowflakeConfigurationException(String message) {
        super(message);
    }

    public SnowflakeConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
