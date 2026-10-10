package com.camellia.integration.snowflake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the canonical Snowflake DDL. For the labeled local fallback (H2) the Snowflake-specific
 * types are translated explicitly rather than assuming the two databases behave identically.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    static final String SCHEMA_RESOURCE = "db/schema-snowflake.sql";

    private final JdbcTemplate jdbc;
    private final DatabaseMode mode;

    public SchemaInitializer(JdbcTemplate jdbc, DatabaseMode mode) {
        this.jdbc = jdbc;
        this.mode = mode;
    }

    public void initialize() {
        List<String> statements = statements(mode);
        for (String sql : statements) {
            jdbc.execute(sql);
        }
        log.info("Schema ensured on {} ({} statements)", mode.label(), statements.size());
    }

    static List<String> statements(DatabaseMode mode) {
        String ddl;
        try {
            ddl = new ClassPathResource(SCHEMA_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StringBuilder withoutComments = new StringBuilder();
        for (String line : ddl.split("\n")) {
            int idx = line.indexOf("--");
            withoutComments.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String raw : withoutComments.toString().split(";")) {
            String sql = raw.trim();
            if (sql.isEmpty()) continue;
            out.add(mode == DatabaseMode.SNOWFLAKE ? sql : toH2(sql));
        }
        return out;
    }

    /** Snowflake -> H2 type translation for the local development fallback only. */
    static String toH2(String sql) {
        return sql.replace("TIMESTAMP_NTZ", "TIMESTAMP")
                .replaceAll("NUMBER\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)")
                .replaceAll("VARCHAR(\\s*[,\\n])", "VARCHAR(1000000)$1");
    }
}
