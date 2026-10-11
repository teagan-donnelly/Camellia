package com.camellia;

import com.camellia.config.AwsSecretsBootstrap;
import com.camellia.config.CamelliaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.IOException;
import java.util.TimeZone;

/**
 * Camellia — clinical intelligence and care coordination prototype.
 * Hackathon prototype using synthetic patient information only. Not for clinical use.
 */
@SpringBootApplication
@EnableConfigurationProperties(CamelliaProperties.class)
public class CamelliaApplication {

    public static void main(String[] args) throws IOException {
        // All timestamps are stored and exchanged in UTC (Snowflake TIMESTAMP_NTZ columns hold UTC wall time).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // Avoid a server round-trip per NULL bind (Snowflake supports getParameterMetaData, but it costs a describe call).
        System.setProperty("spring.jdbc.getParameterType.ignore", "true");
        // Cloud deployments: secrets come from AWS Secrets Manager. No-op locally (no *_SECRET_ARN variables set).
        AwsSecretsBootstrap.load();
        SpringApplication.run(CamelliaApplication.class, args);
    }
}
