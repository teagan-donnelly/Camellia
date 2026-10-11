package com.camellia.repository;

import com.camellia.model.Activity;
import com.camellia.model.AiObservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.camellia.repository.Jdbc.*;

/** Activity log, AI observations, analysis runs, demo metadata, integration checks and demo resets. */
@Repository
public class SupportRepository {

    private static final RowMapper<Activity> ACTIVITY_MAPPER = (rs, i) -> new Activity(
            rs.getString("ACTIVITY_ID"), rs.getString("ENTITY_TYPE"), rs.getString("ENTITY_ID"), rs.getString("PATIENT_ID"),
            rs.getString("ACTION"), rs.getString("DETAIL"), rs.getString("ACTOR"), ldt(rs, "CREATED_AT"));

    private static final RowMapper<AiObservation> OBSERVATION_MAPPER = (rs, i) -> new AiObservation(
            rs.getString("OBSERVATION_ID"), rs.getString("PATIENT_ID"), rs.getString("RECORD_ID"), rs.getString("CATEGORY"),
            rs.getString("STATEMENT"), rs.getString("EVIDENCE_QUOTE"), rs.getString("MODEL"), ldt(rs, "CREATED_AT"));

    private final JdbcTemplate jdbc;

    public SupportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ activity

    public void logActivity(String entityType, String entityId, String patientId, String action, String detail, String actor) {
        jdbc.update("""
                INSERT INTO ACTIVITY_LOG (ACTIVITY_ID, ENTITY_TYPE, ENTITY_ID, PATIENT_ID, ACTION, DETAIL, ACTOR, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{newId("ACT"), entityType, entityId, patientId, action, detail, actor, ts(LocalDateTime.now())},
                new int[]{S, S, S, S, S, S, S, TS});
    }

    public List<Activity> recentActivity(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT * FROM ACTIVITY_LOG ORDER BY CREATED_AT DESC LIMIT " + safe, ACTIVITY_MAPPER);
    }

    public List<Activity> activityForEntity(String entityId) {
        return jdbc.query("SELECT * FROM ACTIVITY_LOG WHERE ENTITY_ID = ? ORDER BY CREATED_AT DESC", ACTIVITY_MAPPER, entityId);
    }

    // ------------------------------------------------------------ AI observations

    public List<AiObservation> observations(String patientId) {
        return jdbc.query("SELECT * FROM AI_OBSERVATIONS WHERE PATIENT_ID = ? ORDER BY CREATED_AT DESC", OBSERVATION_MAPPER, patientId);
    }

    public void replaceObservations(String patientId, List<AiObservation> observations) {
        jdbc.update("DELETE FROM AI_OBSERVATIONS WHERE PATIENT_ID = ?", patientId);
        batch(jdbc, """
                INSERT INTO AI_OBSERVATIONS (OBSERVATION_ID, PATIENT_ID, RECORD_ID, CATEGORY, STATEMENT, EVIDENCE_QUOTE, MODEL, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                observations.stream().map(o -> new Object[]{o.observationId(), o.patientId(), o.recordId(), o.category(),
                        o.statement(), o.evidenceQuote(), o.model(), ts(o.createdAt())}).toList(),
                S, S, S, S, S, S, S, TS);
    }

    // ------------------------------------------------------------ analysis runs

    public void insertRun(String runId, LocalDateTime started, LocalDateTime finished, int patients, int created,
                          int updated, int unchanged, int alerts, String actor) {
        jdbc.update("""
                INSERT INTO ANALYSIS_RUNS (RUN_ID, STARTED_AT, FINISHED_AT, PATIENTS_EVALUATED, FLAGS_CREATED, FLAGS_UPDATED,
                    FLAGS_UNCHANGED, ALERTS_CREATED, TRIGGERED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{runId, ts(started), ts(finished), patients, created, updated, unchanged, alerts, actor},
                new int[]{S, TS, TS, I, I, I, I, I, S});
    }

    public Optional<Map<String, Object>> lastRun() {
        return jdbc.query("SELECT * FROM ANALYSIS_RUNS ORDER BY STARTED_AT DESC LIMIT 1", (rs, i) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("runId", rs.getString("RUN_ID"));
            m.put("startedAt", ldt(rs, "STARTED_AT"));
            m.put("finishedAt", ldt(rs, "FINISHED_AT"));
            m.put("patientsEvaluated", integer(rs, "PATIENTS_EVALUATED"));
            m.put("flagsCreated", integer(rs, "FLAGS_CREATED"));
            m.put("flagsUpdated", integer(rs, "FLAGS_UPDATED"));
            m.put("flagsUnchanged", integer(rs, "FLAGS_UNCHANGED"));
            m.put("alertsCreated", integer(rs, "ALERTS_CREATED"));
            m.put("triggeredBy", rs.getString("TRIGGERED_BY"));
            return m;
        }).stream().findFirst();
    }

    // ------------------------------------------------------------ metadata

    public Optional<String> getMeta(String key) {
        return jdbc.queryForList("SELECT META_VALUE FROM DEMO_METADATA WHERE META_KEY = ?", String.class, key).stream().findFirst();
    }

    public void putMeta(String key, String value) {
        jdbc.update("DELETE FROM DEMO_METADATA WHERE META_KEY = ?", key);
        jdbc.update("INSERT INTO DEMO_METADATA (META_KEY, META_VALUE, UPDATED_AT) VALUES (?, ?, ?)",
                key, value, ts(LocalDateTime.now()));
    }

    // ------------------------------------------------------------ integration checks (real write + read + delete)

    public void insertIntegrationCheck(String checkId, String type, String detail) {
        jdbc.update("INSERT INTO INTEGRATION_CHECKS (CHECK_ID, CHECK_TYPE, DETAIL, CREATED_AT) VALUES (?, ?, ?, ?)",
                checkId, type, detail, ts(LocalDateTime.now()));
    }

    public Optional<String> readIntegrationCheck(String checkId) {
        return jdbc.queryForList("SELECT DETAIL FROM INTEGRATION_CHECKS WHERE CHECK_ID = ?", String.class, checkId).stream().findFirst();
    }

    public long countIntegrationChecks() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM INTEGRATION_CHECKS", Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------ demo reset

    /** Workflow and derived state: always rebuilt by seeding, screening and the simulator. */
    static final List<String> WORKFLOW_TABLES = List.of("PATIENT_TRAITS", "UNIT_SHIFT_SCHEDULE",
            "BRIEFING_SOURCE_REFERENCES", "BRIEFINGS", "SHIFT_PATIENT_ASSIGNMENTS", "PROVIDER_SHIFTS",
            "SENSOR_OBSERVATIONS", "ACTIVITY_EVENTS", "MONITORING_ALERTS", "NURSING_INTERVENTIONS", "MONITORING_CARE_PLANS",
            "MONITORING_SESSIONS", "MONITORING_DEVICES", "CLINICIAN_PRACTICES", "CLINICIANS",
            "AI_CONVERSATION_TURNS", "COORDINATION_NOTES", "ALERT_EVIDENCE", "COORDINATION_ALERTS", "FLAG_EVIDENCE",
            "CLINICAL_FLAGS", "AI_OBSERVATIONS", "ACTIVITY_LOG", "ANALYSIS_RUNS", "DEMO_METADATA");

    /** Source-record tables, keyed by the record they were normalized from. */
    static final List<String> RECORD_TABLES = List.of("APPOINTMENTS", "MEDICATIONS", "MEDICAL_CONDITIONS", "LAB_RESULTS",
            "ENCOUNTERS", "CLINICAL_RECORDS");

    /**
     * Removes the seeded demo dataset and all workflow state so it can be re-seeded with a fresh date anchor. Only rows
     * whose ids come from the seed files are deleted from the patient and record tables; data people added is kept:
     * imported patients and records, uploaded documents, import history, organizations, identifiers and practice
     * registrations.
     */
    public void resetDemoData(Collection<String> seedPatientIds, Collection<String> seedRecordIds) {
        for (String table : WORKFLOW_TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        for (String table : RECORD_TABLES) {
            deleteIn(table, "RECORD_ID", seedRecordIds);
        }
        deleteIn("PATIENTS", "PATIENT_ID", seedPatientIds);
    }

    private void deleteIn(String table, String column, Collection<String> ids) {
        List<String> all = List.copyOf(ids);
        for (int i = 0; i < all.size(); i += 500) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + 500));
            jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")",
                    chunk.toArray());
        }
    }

    public static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
