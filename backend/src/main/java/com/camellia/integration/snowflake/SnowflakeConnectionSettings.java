package com.camellia.integration.snowflake;

import com.camellia.config.CamelliaProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Builds Snowflake JDBC connection settings for key-pair (SNOWFLAKE_JWT) authentication and validates
 * that everything required is present, producing an actionable diagnostic instead of an opaque driver error.
 */
public final class SnowflakeConnectionSettings {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,254}$");

    private final CamelliaProperties.Snowflake cfg;

    public SnowflakeConnectionSettings(CamelliaProperties.Snowflake cfg) {
        this.cfg = cfg;
    }

    /** True when at least the account identifier has been supplied (used by mode=auto). */
    public boolean isAttempted() {
        return notBlank(cfg.getAccount());
    }

    /** Returns human-readable problems; an empty list means the configuration is usable. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!notBlank(cfg.getAccount())) problems.add("SNOWFLAKE_ACCOUNT is not set (format: <orgname>-<account_name>, e.g. myorg-myaccount)");
        if (!notBlank(cfg.getUser())) problems.add("SNOWFLAKE_USER is not set");
        if (!notBlank(cfg.getWarehouse())) problems.add("SNOWFLAKE_WAREHOUSE is not set");
        if (!notBlank(cfg.getPrivateKeyFile())) {
            problems.add("SNOWFLAKE_PRIVATE_KEY_FILE is not set (path to your PKCS#8 .p8 private key for key-pair auth)");
        } else if (!Files.isReadable(Path.of(privateKeyPath()))) {
            problems.add("SNOWFLAKE_PRIVATE_KEY_FILE points to a file that does not exist or is not readable: " + privateKeyPath());
        }
        if (!isIdentifier(cfg.getDatabase())) problems.add("SNOWFLAKE_DATABASE must be a simple identifier (letters, digits, _ or $)");
        if (!isIdentifier(cfg.getSchema())) problems.add("SNOWFLAKE_SCHEMA must be a simple identifier (letters, digits, _ or $)");
        if (notBlank(cfg.getWarehouse()) && !isIdentifier(cfg.getWarehouse())) problems.add("SNOWFLAKE_WAREHOUSE must be a simple identifier");
        if (notBlank(cfg.getRole()) && !isIdentifier(cfg.getRole())) problems.add("SNOWFLAKE_ROLE must be a simple identifier");
        return problems;
    }

    public String jdbcUrl() {
        return "jdbc:snowflake://" + normalizedAccount() + ".snowflakecomputing.com/";
    }

    /** Accepts "org-acct", "org-acct.snowflakecomputing.com" or a full https URL and returns "org-acct". */
    public String normalizedAccount() {
        String a = cfg.getAccount().trim();
        a = a.replaceFirst("(?i)^https?://", "");
        a = a.replaceFirst("(?i)\\.snowflakecomputing\\.com.*$", "");
        return a;
    }

    /**
     * Connection properties. When {@code includeDatabase} is false the session starts without a database so
     * the bootstrap step can create it.
     */
    public Properties connectionProperties(boolean includeDatabase) {
        Properties p = new Properties();
        p.put("user", cfg.getUser().trim());
        p.put("authenticator", "SNOWFLAKE_JWT");
        // The driver expects forward slashes on every OS (Windows paths included).
        p.put("private_key_file", privateKeyPath().replace('\\', '/'));
        if (notBlank(cfg.getPrivateKeyPassphrase())) {
            p.put("private_key_file_pwd", cfg.getPrivateKeyPassphrase());
        }
        p.put("warehouse", cfg.getWarehouse().trim());
        if (notBlank(cfg.getRole())) p.put("role", cfg.getRole().trim());
        if (includeDatabase) {
            p.put("db", database());
            p.put("schema", schema());
        }
        // JSON result format avoids Apache Arrow's reflective memory access on modern JDKs.
        p.put("JDBC_QUERY_RESULT_FORMAT", "JSON");
        p.put("CLIENT_SESSION_KEEP_ALIVE", "true");
        p.put("TIMEZONE", "UTC");
        p.put("CLIENT_TIMESTAMP_TYPE_MAPPING", "TIMESTAMP_NTZ");
        return p;
    }

    /** Key file path with a leading "~" expanded to the user's home directory, so one .env works for every teammate. */
    public String privateKeyPath() {
        String raw = cfg.getPrivateKeyFile() == null ? "" : cfg.getPrivateKeyFile().trim();
        if (raw.equals("~") || raw.startsWith("~/") || raw.startsWith("~\\")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }

    public String database() {
        return cfg.getDatabase().trim().toUpperCase(Locale.ROOT);
    }

    public String schema() {
        return cfg.getSchema().trim().toUpperCase(Locale.ROOT);
    }

    public boolean createDatabaseIfMissing() {
        return cfg.isCreateDatabaseIfMissing();
    }

    public String describe() {
        return "account=" + normalizedAccount() + ", user=" + cfg.getUser() + ", warehouse=" + cfg.getWarehouse()
                + ", database=" + database() + ", schema=" + schema()
                + (notBlank(cfg.getRole()) ? ", role=" + cfg.getRole() : "") + ", auth=key-pair (SNOWFLAKE_JWT)";
    }

    static boolean isIdentifier(String s) {
        return s != null && IDENTIFIER.matcher(s.trim()).matches();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
