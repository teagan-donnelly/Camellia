package com.camellia;

import com.camellia.config.CamelliaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

/**
 * Camellia — clinical intelligence and care coordination prototype.
 * Hackathon prototype using synthetic patient information only. Not for clinical use.
 */
@SpringBootApplication
@EnableConfigurationProperties(CamelliaProperties.class)
public class CamelliaApplication {

    public static void main(String[] args) {
        // All timestamps are stored and exchanged in UTC (Snowflake TIMESTAMP_NTZ columns hold UTC wall time).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // Avoid a server round-trip per NULL bind (Snowflake supports getParameterMetaData, but it costs a describe call).
        System.setProperty("spring.jdbc.getParameterType.ignore", "true");
        SpringApplication.run(CamelliaApplication.class, args);
    }
}
