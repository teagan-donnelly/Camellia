package com.camellia.repository;

import com.camellia.model.CoordinationAlert;
import com.camellia.model.CoordinationNote;
import com.camellia.model.EvidenceLink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.camellia.repository.Jdbc.*;

@Repository
public class AlertRepository {

    private static final RowMapper<CoordinationAlert> MAPPER = (rs, i) -> new CoordinationAlert(
            rs.getString("ALERT_ID"), rs.getString("FLAG_ID"), rs.getString("PATIENT_ID"), rs.getString("ALERT_TYPE"),
            rs.getString("TITLE"), rs.getString("ASSIGNED_TEAM"), rs.getString("STATUS"), rs.getString("PRIORITY"),
            rs.getString("APPOINTMENT_ID"), ldt(rs, "ENCOUNTER_DATE"), ld(rs, "REVIEW_DUE_DATE"),
            rs.getString("ACKNOWLEDGED_BY"), ldt(rs, "ACKNOWLEDGED_AT"), rs.getString("CONTACT_STATUS"),
            rs.getBoolean("PLANNING_DISCUSSION_OCCURRED"), rs.getBoolean("INFO_NEEDED"), rs.getString("DISMISS_REASON"),
            rs.getString("CONCERN_SUMMARY"), rs.getString("INVOLVED_SPECIALTIES"), ldt(rs, "CREATED_AT"),
            ldt(rs, "UPDATED_AT"), rs.getString("UPDATED_BY"), ldt(rs, "EVIDENCE_UPDATED_AT"));

    private static final RowMapper<EvidenceLink> EVIDENCE_MAPPER = (rs, i) -> new EvidenceLink(
            rs.getString("ALERT_ID"), rs.getString("RECORD_ID"), rs.getString("EVIDENCE_ROLE"), rs.getString("DESCRIPTION"),
            ldt(rs, "ADDED_AT"));

    private static final RowMapper<CoordinationNote> NOTE_MAPPER = (rs, i) -> new CoordinationNote(
            rs.getString("NOTE_ID"), rs.getString("ALERT_ID"), rs.getString("PATIENT_ID"), rs.getString("AUTHOR_NAME"),
            rs.getString("AUTHOR_ROLE"), rs.getString("NOTE_TYPE"), rs.getString("BODY"), ldt(rs, "CREATED_AT"));

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CoordinationAlert> find(String status, String team, String patientId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM COORDINATION_ALERTS WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) { sql.append(" AND STATUS = ?"); args.add(status); }
        if (team != null) { sql.append(" AND ASSIGNED_TEAM = ?"); args.add(team); }
        if (patientId != null) { sql.append(" AND PATIENT_ID = ?"); args.add(patientId); }
        sql.append(" ORDER BY ENCOUNTER_DATE");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<CoordinationAlert> findById(String alertId) {
        return jdbc.query("SELECT * FROM COORDINATION_ALERTS WHERE ALERT_ID = ?", MAPPER, alertId).stream().findFirst();
    }

    public Optional<CoordinationAlert> findByFlagId(String flagId) {
        return jdbc.query("SELECT * FROM COORDINATION_ALERTS WHERE FLAG_ID = ?", MAPPER, flagId).stream().findFirst();
    }

    public long countByStatus(String status) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM COORDINATION_ALERTS WHERE STATUS = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    public void insert(CoordinationAlert a) {
        jdbc.update("""
                INSERT INTO COORDINATION_ALERTS (ALERT_ID, FLAG_ID, PATIENT_ID, ALERT_TYPE, TITLE, ASSIGNED_TEAM, STATUS, PRIORITY,
                    APPOINTMENT_ID, ENCOUNTER_DATE, REVIEW_DUE_DATE, ACKNOWLEDGED_BY, ACKNOWLEDGED_AT, CONTACT_STATUS,
                    PLANNING_DISCUSSION_OCCURRED, INFO_NEEDED, DISMISS_REASON, CONCERN_SUMMARY, INVOLVED_SPECIALTIES,
                    CREATED_AT, UPDATED_AT, UPDATED_BY, EVIDENCE_UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{a.alertId(), a.flagId(), a.patientId(), a.alertType(), a.title(), a.assignedTeam(), a.status(),
                        a.priority(), a.appointmentId(), ts(a.encounterDate()), date(a.reviewDueDate()), a.acknowledgedBy(),
                        ts(a.acknowledgedAt()), a.contactStatus(), a.planningDiscussionOccurred(), a.infoNeeded(),
                        a.dismissReason(), a.concernSummary(), a.involvedSpecialties(), ts(a.createdAt()), ts(a.updatedAt()),
                        a.updatedBy(), ts(a.evidenceUpdatedAt())},
                new int[]{S, S, S, S, S, S, S, S, S, TS, D, S, TS, S, B, B, S, S, S, TS, TS, S, TS});
    }

    /** Persists all human-editable workflow fields of an alert in one statement. */
    public int updateWorkflow(CoordinationAlert a) {
        return jdbc.update("""
                UPDATE COORDINATION_ALERTS SET STATUS = ?, ACKNOWLEDGED_BY = ?, ACKNOWLEDGED_AT = ?, CONTACT_STATUS = ?,
                    PLANNING_DISCUSSION_OCCURRED = ?, INFO_NEEDED = ?, DISMISS_REASON = ?, UPDATED_AT = ?, UPDATED_BY = ?
                WHERE ALERT_ID = ?""",
                new Object[]{a.status(), a.acknowledgedBy(), ts(a.acknowledgedAt()), a.contactStatus(),
                        a.planningDiscussionOccurred(), a.infoNeeded(), a.dismissReason(), ts(a.updatedAt()), a.updatedBy(),
                        a.alertId()},
                new int[]{S, S, TS, S, B, B, S, TS, S, S});
    }

    /** Re-evaluation changed the evidence (e.g. a new lab result arrived). Workflow status is preserved. */
    public void updateEvidenceSnapshot(String alertId, String title, String type, String priority, String concernSummary,
                                       String specialties, String appointmentId, LocalDateTime encounterDate,
                                       java.time.LocalDate reviewDue, LocalDateTime at) {
        jdbc.update("""
                UPDATE COORDINATION_ALERTS SET TITLE = ?, ALERT_TYPE = ?, PRIORITY = ?, CONCERN_SUMMARY = ?, INVOLVED_SPECIALTIES = ?,
                    APPOINTMENT_ID = ?, ENCOUNTER_DATE = ?, REVIEW_DUE_DATE = ?, EVIDENCE_UPDATED_AT = ?, UPDATED_AT = ?
                WHERE ALERT_ID = ?""",
                new Object[]{title, type, priority, concernSummary, specialties, appointmentId, ts(encounterDate),
                        date(reviewDue), ts(at), ts(at), alertId},
                new int[]{S, S, S, S, S, S, TS, D, TS, TS, S});
    }

    public List<EvidenceLink> evidence(String alertId) {
        return jdbc.query("SELECT * FROM ALERT_EVIDENCE WHERE ALERT_ID = ? ORDER BY EVIDENCE_ROLE DESC, ADDED_AT", EVIDENCE_MAPPER, alertId);
    }

    public void replaceEvidence(String alertId, List<EvidenceLink> links) {
        jdbc.update("DELETE FROM ALERT_EVIDENCE WHERE ALERT_ID = ?", alertId);
        batch(jdbc, "INSERT INTO ALERT_EVIDENCE (ALERT_ID, RECORD_ID, EVIDENCE_ROLE, DESCRIPTION, ADDED_AT) VALUES (?, ?, ?, ?, ?)",
                links.stream().map(l -> new Object[]{alertId, l.recordId(), l.role(), l.description(), ts(l.addedAt())}).toList(),
                S, S, S, S, TS);
    }

    public List<EvidenceLink> allEvidence() {
        return jdbc.query("SELECT * FROM ALERT_EVIDENCE", EVIDENCE_MAPPER);
    }

    /** Human-entered note counts per alert (system status notes excluded). */
    public java.util.Map<String, Integer> noteCounts() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        jdbc.query("SELECT ALERT_ID, COUNT(*) AS N FROM COORDINATION_NOTES WHERE NOTE_TYPE <> 'STATUS' GROUP BY ALERT_ID",
                rs -> { out.put(rs.getString("ALERT_ID"), rs.getInt("N")); });
        return out;
    }

    public List<CoordinationNote> notes(String alertId) {
        return jdbc.query("SELECT * FROM COORDINATION_NOTES WHERE ALERT_ID = ? ORDER BY CREATED_AT", NOTE_MAPPER, alertId);
    }

    public void insertNote(CoordinationNote n) {
        jdbc.update("""
                INSERT INTO COORDINATION_NOTES (NOTE_ID, ALERT_ID, PATIENT_ID, AUTHOR_NAME, AUTHOR_ROLE, NOTE_TYPE, BODY, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{n.noteId(), n.alertId(), n.patientId(), n.authorName(), n.authorRole(), n.noteType(), n.body(),
                        ts(n.createdAt())},
                new int[]{S, S, S, S, S, S, S, TS});
    }
}
