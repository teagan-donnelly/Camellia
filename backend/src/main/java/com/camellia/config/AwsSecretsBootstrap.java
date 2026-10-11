package com.camellia.config;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cloud only: copies secrets from AWS Secrets Manager into system properties before Spring starts, so the existing
 * ${GEMINI_API_KEY}, ${CAMELLIA_DEMO_SESSION_SECRET} and ${SNOWFLAKE_PRIVATE_KEY_FILE} placeholders keep working
 * (Spring gives system properties precedence over environment variables). Environment variables hold only secret
 * ARNs, never values. Does nothing when none of the ARN variables is set, which is the case in local development.
 */
public final class AwsSecretsBootstrap {

    static final String GEMINI_KEY_ARN = "CAMELLIA_GEMINI_KEY_SECRET_ARN";
    static final String SESSION_SECRET_ARN = "CAMELLIA_SESSION_SECRET_ARN";
    static final String SNOWFLAKE_KEY_ARN = "CAMELLIA_SNOWFLAKE_KEY_SECRET_ARN";

    /** Reads one secret's string value. Closed after loading so no client lingers. */
    interface SecretReader extends AutoCloseable {
        String read(String arn);

        @Override
        void close();
    }

    private AwsSecretsBootstrap() {}

    public static void load() throws IOException {
        load(System::getenv, () -> {
            SecretsManagerClient sm = SecretsManagerClient.create();
            return new SecretReader() {
                @Override
                public String read(String arn) {
                    return sm.getSecretValue(r -> r.secretId(arn)).secretString();
                }

                @Override
                public void close() {
                    sm.close();
                }
            };
        });
    }

    static void load(Function<String, String> env, Supplier<SecretReader> readers) throws IOException {
        String gemini = arn(env, GEMINI_KEY_ARN);
        String session = arn(env, SESSION_SECRET_ARN);
        String snowflakeKey = arn(env, SNOWFLAKE_KEY_ARN);
        if (gemini == null && session == null && snowflakeKey == null) return;

        try (SecretReader secrets = readers.get()) {
            if (gemini != null) System.setProperty("GEMINI_API_KEY", secrets.read(gemini));
            if (session != null) System.setProperty("CAMELLIA_DEMO_SESSION_SECRET", secrets.read(session));
            if (snowflakeKey != null) {
                // The Snowflake driver reads the key from a file. Lambda's /tmp is private to one execution environment.
                Path key = Files.createTempFile("snowflake-", ".p8",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(key, secrets.read(snowflakeKey));
                System.setProperty("SNOWFLAKE_PRIVATE_KEY_FILE", key.toString());
            }
        }
    }

    private static String arn(Function<String, String> env, String name) {
        String v = env.apply(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
