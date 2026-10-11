package com.camellia.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.camellia.repository.Jdbc.*;

/** History of patient record imports (IMPORT_BATCHES). */
@Repository
public class ImportRepository {

    public record ImportBatch(String importId, String fileName, String fileSha256, String sourceFormat, String orgId,
                              String patientId, String matchDecision, int recordsInserted, int recordsDuplicate,
                              String status, String detail, String importedBy, LocalDateTime importedAt) {}

    private static final RowMapper<ImportBatch> MAPPER = (rs, i) -> new ImportBatch(rs.getString("IMPORT_ID"),
            rs.getString("FILE_NAME"), rs.getString("FILE_SHA256"), rs.getString("SOURCE_FORMAT"), rs.getString("ORG_ID"),
            rs.getString("PATIENT_ID"), rs.getString("MATCH_DECISION"), nz(integer(rs, "RECORDS_INSERTED")),
            nz(integer(rs, "RECORDS_DUPLICATE")), rs.getString("STATUS"), rs.getString("DETAIL"), rs.getString("IMPORTED_BY"),
            ldt(rs, "IMPORTED_AT"));

    private final JdbcTemplate jdbc;

    public ImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ImportBatch b) {
        jdbc.update("""
                INSERT INTO IMPORT_BATCHES (IMPORT_ID, FILE_NAME, FILE_SHA256, SOURCE_FORMAT, ORG_ID, PATIENT_ID, MATCH_DECISION,
                    RECORDS_INSERTED, RECORDS_DUPLICATE, STATUS, DETAIL, IMPORTED_BY, IMPORTED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{b.importId(), b.fileName(), b.fileSha256(), b.sourceFormat(), b.orgId(), b.patientId(),
                        b.matchDecision(), b.recordsInserted(), b.recordsDuplicate(), b.status(), b.detail(), b.importedBy(),
                        ts(b.importedAt())},
                new int[]{S, S, S, S, S, S, S, I, I, S, S, S, TS});
    }

    public List<ImportBatch> history(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT * FROM IMPORT_BATCHES ORDER BY IMPORTED_AT DESC LIMIT " + safe, MAPPER);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
