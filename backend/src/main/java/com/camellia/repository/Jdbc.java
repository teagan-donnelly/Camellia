package com.camellia.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Small JDBC helpers. java.sql.Timestamp/Date are used for binds (rather than java.time objects) because they
 * are handled identically by the Snowflake JDBC driver and the local fallback; the JVM and Snowflake session both run in UTC.
 */
final class Jdbc {

    private Jdbc() {}

    // Explicit bind types keep Snowflake array binding consistent (a NULL is bound with its column's type).
    static final int S = java.sql.Types.VARCHAR;
    static final int TS = java.sql.Types.TIMESTAMP;
    static final int D = java.sql.Types.DATE;
    static final int B = java.sql.Types.BOOLEAN;
    static final int N = java.sql.Types.DOUBLE;
    static final int I = java.sql.Types.INTEGER;

    static void batch(JdbcTemplate jdbc, String sql, List<Object[]> rows, int... types) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(sql, rows, types);
    }

    static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    static LocalDateTime ldt(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    static LocalDate ld(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    static Double dbl(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.doubleValue();
    }

    static Integer integer(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.intValue();
    }

    /** "?, ?, ?" for an IN list (values are always bound, never concatenated). */
    static String placeholders(Collection<?> values) {
        return String.join(", ", Collections.nCopies(Math.max(values.size(), 1), "?"));
    }
}
