package com.camellia.repository;

import com.camellia.model.ClinicalFlag;
import com.camellia.model.EvidenceLink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.camellia.repository.Jdbc.*;

@Repository
public class FlagRepository {

    private static final RowMapper<ClinicalFlag> MAPPER = (rs, i) -> new ClinicalFlag(
            rs.getString("FLAG_ID"), rs.getString("PATIENT_ID"), rs.getString("RULE_ID"), rs.getString("RULE_VERSION"),
            rs.getString("TITLE"), rs.getString("DETECTION_REASON"), rs.getString("REVIEW_PRIORITY"), rs.getString("STATUS"),
            rs.getString("STATUS_REASON"), rs.getString("DEDUP_KEY"), rs.getString("EVIDENCE_FINGERPRINT"),
            ldt(rs, "CREATED_AT"), ldt(rs, "UPDATED_AT"), rs.getString("UPDATED_BY"), ldt(rs, "LAST_EVALUATED_AT"),
            ldt(rs, "EVIDENCE_UPDATED_AT"), rs.getString("AI_EXPLANATION_JSON"), rs.getString("AI_EXPLANATION_MODEL"),
            ldt(rs, "AI_EXPLANATION_AT"));

    static final RowMapper<EvidenceLink> FLAG_EVIDENCE_MAPPER = (rs, i) -> new EvidenceLink(
            rs.getString("FLAG_ID"), rs.getString("RECORD_ID"), rs.getString("EVIDENCE_ROLE"), rs.getString("DESCRIPTION"),
            ldt(rs, "ADDED_AT"));

    private final JdbcTemplate jdbc;

    public FlagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClinicalFlag> find(String status, String ruleId, String patientId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM CLINICAL_FLAGS WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) { sql.append(" AND STATUS = ?"); args.add(status); }
        if (ruleId != null) { sql.append(" AND RULE_ID = ?"); args.add(ruleId); }
        if (patientId != null) { sql.append(" AND PATIENT_ID = ?"); args.add(patientId); }
        sql.append(" ORDER BY UPDATED_AT DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<ClinicalFlag> findById(String flagId) {
        return jdbc.query("SELECT * FROM CLINICAL_FLAGS WHERE FLAG_ID = ?", MAPPER, flagId).stream().findFirst();
    }

    public Optional<ClinicalFlag> findByDedupKey(String dedupKey) {
        return jdbc.query("SELECT * FROM CLINICAL_FLAGS WHERE DEDUP_KEY = ? ORDER BY CREATED_AT", MAPPER, dedupKey).stream().findFirst();
    }

    public long countOpen() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM CLINICAL_FLAGS WHERE STATUS IN ('UNREVIEWED', 'IN_REVIEW')", Long.class);
        return n == null ? 0 : n;
    }

    public void insert(ClinicalFlag f) {
        jdbc.update("""
                INSERT INTO CLINICAL_FLAGS (FLAG_ID, PATIENT_ID, RULE_ID, RULE_VERSION, TITLE, DETECTION_REASON, REVIEW_PRIORITY,
                    STATUS, STATUS_REASON, DEDUP_KEY, EVIDENCE_FINGERPRINT, CREATED_AT, UPDATED_AT, UPDATED_BY, LAST_EVALUATED_AT,
                    EVIDENCE_UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{f.flagId(), f.patientId(), f.ruleId(), f.ruleVersion(), f.title(), f.detectionReason(),
                        f.reviewPriority(), f.status(), f.statusReason(), f.dedupKey(), f.evidenceFingerprint(),
                        ts(f.createdAt()), ts(f.updatedAt()), f.updatedBy(), ts(f.lastEvaluatedAt()), ts(f.evidenceUpdatedAt())},
                new int[]{S, S, S, S, S, S, S, S, S, S, S, TS, TS, S, TS, TS});
    }

    public void markEvaluated(String flagId, LocalDateTime at) {
        jdbc.update("UPDATE CLINICAL_FLAGS SET LAST_EVALUATED_AT = ? WHERE FLAG_ID = ?", ts(at), flagId);
    }

    /** Re-evaluation found different evidence: refresh the explanation and fingerprint, keep the human review status. */
    public void updateEvidence(String flagId, String title, String reason, String priority, String fingerprint, LocalDateTime at) {
        jdbc.update("""
                UPDATE CLINICAL_FLAGS SET TITLE = ?, DETECTION_REASON = ?, REVIEW_PRIORITY = ?, EVIDENCE_FINGERPRINT = ?,
                    EVIDENCE_UPDATED_AT = ?, LAST_EVALUATED_AT = ?, UPDATED_AT = ?, AI_EXPLANATION_JSON = NULL,
                    AI_EXPLANATION_MODEL = NULL, AI_EXPLANATION_AT = NULL
                WHERE FLAG_ID = ?""",
                title, reason, priority, fingerprint, ts(at), ts(at), ts(at), flagId);
    }

    public int updateStatus(String flagId, String status, String reason, String actor, LocalDateTime at) {
        return jdbc.update("UPDATE CLINICAL_FLAGS SET STATUS = ?, STATUS_REASON = ?, UPDATED_BY = ?, UPDATED_AT = ? WHERE FLAG_ID = ?",
                new Object[]{status, reason, actor, ts(at), flagId}, new int[]{S, S, S, TS, S});
    }

    public void saveExplanation(String flagId, String json, String model, LocalDateTime at) {
        jdbc.update("UPDATE CLINICAL_FLAGS SET AI_EXPLANATION_JSON = ?, AI_EXPLANATION_MODEL = ?, AI_EXPLANATION_AT = ? WHERE FLAG_ID = ?",
                json, model, ts(at), flagId);
    }

    public List<EvidenceLink> evidence(String flagId) {
        return jdbc.query("SELECT * FROM FLAG_EVIDENCE WHERE FLAG_ID = ? ORDER BY EVIDENCE_ROLE DESC, ADDED_AT", FLAG_EVIDENCE_MAPPER, flagId);
    }

    public List<EvidenceLink> allEvidence() {
        return jdbc.query("SELECT * FROM FLAG_EVIDENCE", FLAG_EVIDENCE_MAPPER);
    }

    public List<EvidenceLink> evidenceFor(Collection<String> flagIds) {
        if (flagIds.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM FLAG_EVIDENCE WHERE FLAG_ID IN (" + placeholders(flagIds) + ")",
                FLAG_EVIDENCE_MAPPER, flagIds.toArray());
    }

    public void replaceEvidence(String flagId, List<EvidenceLink> links) {
        jdbc.update("DELETE FROM FLAG_EVIDENCE WHERE FLAG_ID = ?", flagId);
        batch(jdbc, "INSERT INTO FLAG_EVIDENCE (FLAG_ID, RECORD_ID, EVIDENCE_ROLE, DESCRIPTION, ADDED_AT) VALUES (?, ?, ?, ?, ?)",
                links.stream().map(l -> new Object[]{flagId, l.recordId(), l.role(), l.description(), ts(l.addedAt())}).toList(),
                S, S, S, S, TS);
    }
}
