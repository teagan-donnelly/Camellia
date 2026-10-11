package com.camellia.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

import static com.camellia.repository.Jdbc.*;

/** PATIENT_DOCUMENTS: metadata (and extracted text) of uploaded PDF / Word documents. File bytes are not stored here. */
@Repository
public class DocumentRepository {

    /** Metadata only: extracted text is loaded separately, and only for search. */
    public record DocumentMeta(String documentId, String patientId, String encounterId, String title, String originalFilename,
                               String fileType, String contentType, long sizeBytes, String sha256, String storageKey,
                               String textStatus, Integer textChars, Integer pageCount, LocalDateTime uploadedAt,
                               String uploadedById, String uploadedBy) {}

    /** A search match: metadata plus the text needed to build a snippet. */
    public record DocumentMatch(DocumentMeta meta, String text) {}

    private static final String META_COLUMNS = """
            DOCUMENT_ID, PATIENT_ID, ENCOUNTER_ID, TITLE, ORIGINAL_FILENAME, FILE_TYPE, CONTENT_TYPE, SIZE_BYTES, SHA256,
            STORAGE_KEY, TEXT_STATUS, TEXT_CHARS, PAGE_COUNT, UPLOADED_AT, UPLOADED_BY_ID, UPLOADED_BY""";

    private static final RowMapper<DocumentMeta> MAPPER = (rs, i) -> new DocumentMeta(rs.getString("DOCUMENT_ID"),
            rs.getString("PATIENT_ID"), rs.getString("ENCOUNTER_ID"), rs.getString("TITLE"), rs.getString("ORIGINAL_FILENAME"),
            rs.getString("FILE_TYPE"), rs.getString("CONTENT_TYPE"), rs.getLong("SIZE_BYTES"), rs.getString("SHA256"),
            rs.getString("STORAGE_KEY"), rs.getString("TEXT_STATUS"), integer(rs, "TEXT_CHARS"), integer(rs, "PAGE_COUNT"),
            ldt(rs, "UPLOADED_AT"), rs.getString("UPLOADED_BY_ID"), rs.getString("UPLOADED_BY"));

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DocumentMeta d, String extractedText) {
        jdbc.update("""
                INSERT INTO PATIENT_DOCUMENTS (DOCUMENT_ID, PATIENT_ID, ENCOUNTER_ID, TITLE, ORIGINAL_FILENAME, FILE_TYPE,
                    CONTENT_TYPE, SIZE_BYTES, SHA256, STORAGE_KEY, TEXT_STATUS, TEXT_CHARS, EXTRACTED_TEXT, PAGE_COUNT,
                    UPLOADED_AT, UPLOADED_BY_ID, UPLOADED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{d.documentId(), d.patientId(), d.encounterId(), d.title(), d.originalFilename(), d.fileType(),
                        d.contentType(), d.sizeBytes(), d.sha256(), d.storageKey(), d.textStatus(), d.textChars(), extractedText,
                        d.pageCount(), ts(d.uploadedAt()), d.uploadedById(), d.uploadedBy()},
                new int[]{S, S, S, S, S, S, S, java.sql.Types.BIGINT, S, S, S, I, S, I, TS, S, S});
    }

    public List<DocumentMeta> forPatient(String patientId) {
        return jdbc.query("SELECT " + META_COLUMNS + " FROM PATIENT_DOCUMENTS WHERE PATIENT_ID = ? ORDER BY UPLOADED_AT DESC",
                MAPPER, patientId);
    }

    public Optional<DocumentMeta> find(String documentId) {
        return jdbc.query("SELECT " + META_COLUMNS + " FROM PATIENT_DOCUMENTS WHERE DOCUMENT_ID = ?", MAPPER, documentId)
                .stream().findFirst();
    }

    public Optional<String> existingWithHash(String patientId, String sha256) {
        return jdbc.queryForList("SELECT DOCUMENT_ID FROM PATIENT_DOCUMENTS WHERE PATIENT_ID = ? AND SHA256 = ? ORDER BY UPLOADED_AT",
                String.class, patientId, sha256).stream().findFirst();
    }

    /**
     * Keyword search over titles, file names and extracted text, limited to the given patients (the caller's scope).
     * Keywords are pre-sanitized (letters, digits, spaces, hyphens), and every value is a bound parameter.
     *
     * @param patientIds null = network-wide scope; empty = no patients
     */
    public List<DocumentMatch> search(List<String> keywords, boolean matchAll, Collection<String> patientIds, String patientId,
                                      List<String> traits, int limit) {
        if (keywords.isEmpty() || (patientIds != null && patientIds.isEmpty())) return List.of();
        StringBuilder sql = new StringBuilder("SELECT " + META_COLUMNS + ", EXTRACTED_TEXT FROM PATIENT_DOCUMENTS d WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (patientIds != null) {
            sql.append(" AND d.PATIENT_ID IN (").append(String.join(", ", Collections.nCopies(patientIds.size(), "?"))).append(")");
            params.addAll(patientIds.stream().sorted().toList());
        }
        if (patientId != null) {
            sql.append(" AND d.PATIENT_ID = ?");
            params.add(patientId);
        }
        if (!traits.isEmpty()) {
            sql.append(" AND d.PATIENT_ID IN (SELECT t.PATIENT_ID FROM PATIENT_TRAITS t WHERE t.STATUS = 'ACTIVE' AND t.TRAIT_CODE IN (")
               .append(String.join(", ", Collections.nCopies(traits.size(), "?"))).append("))");
            params.addAll(traits);
        }
        List<String> clauses = new ArrayList<>();
        for (String k : keywords) {
            clauses.add("(LOWER(d.EXTRACTED_TEXT) LIKE ? OR LOWER(d.TITLE) LIKE ? OR LOWER(d.ORIGINAL_FILENAME) LIKE ?)");
            String like = "%" + k + "%";
            params.addAll(List.of(like, like, like));
        }
        sql.append(" AND (").append(String.join(matchAll ? " AND " : " OR ", clauses)).append(")");
        sql.append(" ORDER BY d.UPLOADED_AT DESC LIMIT ").append(Math.max(1, Math.min(limit, 100)));
        return jdbc.query(sql.toString(), (rs, i) -> new DocumentMatch(MAPPER.mapRow(rs, i), rs.getString("EXTRACTED_TEXT")),
                params.toArray());
    }
}
