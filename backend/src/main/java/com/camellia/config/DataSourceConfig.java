package com.camellia.config;

import com.camellia.integration.snowflake.DatabaseMode;
import com.camellia.integration.snowflake.SnowflakeConfigurationException;
import com.camellia.integration.snowflake.SnowflakeConnectionSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * Chooses the persistence backend.
 * <ul>
 *   <li>Snowflake (primary): key-pair authenticated JDBC with a Hikari pool.</li>
 *   <li>Local fallback (H2 file database): only for development without credentials. Always labeled; never reported as Snowflake.</li>
 * </ul>
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DatabaseMode databaseMode(CamelliaProperties props) {
        SnowflakeConnectionSettings sf = new SnowflakeConnectionSettings(props.getSnowflake());
        String mode = props.getDatabase().getMode() == null ? "auto" : props.getDatabase().getMode().trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "snowflake" -> {
                requireSnowflake(sf);
                yield DatabaseMode.SNOWFLAKE;
            }
            case "local" -> DatabaseMode.LOCAL_FALLBACK;
            case "auto" -> {
                if (sf.isAttempted()) {
                    requireSnowflake(sf);
                    yield DatabaseMode.SNOWFLAKE;
                }
                log.warn("""

                        ==========================================================================
                         SNOWFLAKE IS NOT CONFIGURED - using the LOCAL DEVELOPMENT FALLBACK (H2).
                         This is NOT Snowflake. Set SNOWFLAKE_* variables in backend/.env
                         (see .env.example) to use the real Snowflake integration.
                        ==========================================================================""");
                yield DatabaseMode.LOCAL_FALLBACK;
            }
            default -> throw new SnowflakeConfigurationException(
                    "CAMELLIA_DB_MODE must be one of auto | snowflake | local (got '" + mode + "')");
        };
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(DatabaseMode mode, CamelliaProperties props) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("camellia-" + mode.name().toLowerCase(Locale.ROOT));
        if (mode == DatabaseMode.SNOWFLAKE) {
            SnowflakeConnectionSettings sf = new SnowflakeConnectionSettings(props.getSnowflake());
            bootstrapSnowflake(sf);
            hc.setJdbcUrl(sf.jdbcUrl());
            hc.setDataSourceProperties(sf.connectionProperties(true));
            hc.setMaximumPoolSize(6);
            hc.setMinimumIdle(1);
            hc.setMaxLifetime(30 * 60_000L);
            hc.setConnectionTimeout(60_000L);
            log.info("Using Snowflake: {}", sf.describe());
        } else {
            hc.setJdbcUrl(props.getDatabase().getLocalUrl());
            hc.setUsername("sa");
            hc.setPassword("");
            hc.setMaximumPoolSize(5);
            log.info("Using LOCAL DEVELOPMENT FALLBACK database at {}", props.getDatabase().getLocalUrl());
        }
        return new HikariDataSource(hc);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    private static void requireSnowflake(SnowflakeConnectionSettings sf) {
        List<String> problems = sf.problems();
        if (!problems.isEmpty()) {
            throw new SnowflakeConfigurationException("""
                    Snowflake configuration is incomplete:
                      - %s
                    Fix backend/.env (see .env.example), or set CAMELLIA_DB_MODE=local to run with the labeled local fallback."""
                    .formatted(String.join("\n  - ", problems)));
        }
    }

    /** Opens one session without a database so the demo database and schema can be created when allowed. */
    private static void bootstrapSnowflake(SnowflakeConnectionSettings sf) {
        try (Connection c = DriverManager.getConnection(sf.jdbcUrl(), sf.connectionProperties(false));
             Statement st = c.createStatement()) {
            // Identifiers were validated by SnowflakeConnectionSettings.problems() (simple identifiers only).
            if (sf.createDatabaseIfMissing()) {
                st.execute("CREATE DATABASE IF NOT EXISTS " + sf.database());
                st.execute("CREATE SCHEMA IF NOT EXISTS " + sf.database() + "." + sf.schema());
            }
            st.execute("USE SCHEMA " + sf.database() + "." + sf.schema());
        } catch (SQLException e) {
            throw new SnowflakeConfigurationException("""
                    Could not connect to Snowflake (%s).
                    Driver message: %s
                    Common causes: wrong account identifier, public key not registered on the user (ALTER USER ... SET RSA_PUBLIC_KEY),
                    encrypted key without SNOWFLAKE_PRIVATE_KEY_PASSPHRASE, warehouse/role not granted, or missing CREATE DATABASE privilege
                    (set SNOWFLAKE_CREATE_DATABASE=false if the database and schema already exist)."""
                    .formatted(sf.describe(), e.getMessage()), e);
        }
    }
}
