package com.camellia.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.camellia.config.AwsSecretsBootstrap.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Secrets Manager values reach Spring through system properties; nothing happens without secret ARNs. */
class AwsSecretsBootstrapTest {

    @AfterEach
    void clearProperties() throws Exception {
        String keyFile = System.getProperty("SNOWFLAKE_PRIVATE_KEY_FILE");
        if (keyFile != null) Files.deleteIfExists(Path.of(keyFile));
        System.clearProperty("GEMINI_API_KEY");
        System.clearProperty("CAMELLIA_DEMO_SESSION_SECRET");
        System.clearProperty("SNOWFLAKE_PRIVATE_KEY_FILE");
    }

    @Test
    void doesNothingLocally() throws Exception {
        AwsSecretsBootstrap.load(name -> null, () -> {
            throw new AssertionError("no Secrets Manager client should be created without secret ARNs");
        });
        assertThat(System.getProperty("GEMINI_API_KEY")).isNull();
        assertThat(System.getProperty("SNOWFLAKE_PRIVATE_KEY_FILE")).isNull();
    }

    @Test
    void copiesSecretsAndWritesAnOwnerOnlyKeyFile() throws Exception {
        Map<String, String> env = Map.of(GEMINI_KEY_ARN, "arn:test:gemini", SESSION_SECRET_ARN, "arn:test:session",
                SNOWFLAKE_KEY_ARN, "arn:test:key");
        Map<String, String> secrets = Map.of("arn:test:gemini", "test-gemini-value", "arn:test:session",
                "test-session-secret-0123456789", "arn:test:key", "-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----\n");
        AtomicBoolean closed = new AtomicBoolean();

        AwsSecretsBootstrap.load(env::get, () -> new SecretReader() {
            @Override
            public String read(String arn) {
                return secrets.get(arn);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        });

        assertThat(System.getProperty("GEMINI_API_KEY")).isEqualTo("test-gemini-value");
        assertThat(System.getProperty("CAMELLIA_DEMO_SESSION_SECRET")).isEqualTo("test-session-secret-0123456789");
        Path key = Path.of(System.getProperty("SNOWFLAKE_PRIVATE_KEY_FILE"));
        assertThat(Files.readString(key)).isEqualTo(secrets.get("arn:test:key"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key))).isEqualTo("rw-------");
        assertThat(closed).isTrue();
    }
}
