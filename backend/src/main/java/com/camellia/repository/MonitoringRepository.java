package com.camellia.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

import static com.camellia.repository.Jdbc.*;

/**
 * Simulated wearable monitoring. ENCOUNTER ISOLATION: every read of observations, events, alerts, plans and
 * interventions takes both the patient id and the encounter id and filters on both in SQL, so data from another
 * hospital stay can never be returned for the requested one.
 */
@Repository
public class MonitoringRepository {

    public record Device(String deviceId, String model, String firmware, boolean simulated) {}

    public record Session(String sessionId, String patientId, String encounterId, String deviceId, LocalDateTime startedAt,
                          LocalDateTime endedAt, String status, boolean connected, LocalDateTime lastSyncAt, Double batteryPct,
                          LocalDateTime simClockAt, long simSeed, String scenario) {

        public Session with(String status, boolean connected, LocalDateTime lastSyncAt, Double batteryPct,
                            LocalDateTime simClockAt, LocalDateTime endedAt) {
            return new Session(sessionId, patientId, encounterId, deviceId, startedAt, endedAt, status, connected, lastSyncAt,
                    batteryPct, simClockAt, simSeed, scenario);
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record Observation(String observationId, String sessionId, String patientId, String encounterId, String deviceId,
                              LocalDateTime observedAt, String type, Double value, String unit, String source, String quality) {}

    public record ActivityEvent(String eventId, String sessionId, String patientId, String encounterId, String eventType,
                                LocalDateTime startedAt, LocalDateTime endedAt, Integer durationSeconds, String source,
                                String detail, String recordedBy) {}

    public record CarePlan(String planId, String patientId, String encounterId, Integer mobilityReviewMinutes,
                           Integer repositioningIntervalMinutes, Integer staleDataMinutes, String note, String createdBy) {}

    public record Intervention(String interventionId, String patientId, String encounterId, String type,
                               LocalDateTime performedAt, String documentedById, String documentedBy, String note,
                               LocalDateTime createdAt) {}

    public record Alert(String alertId, String sessionId, String patientId, String encounterId, String alertType, String status,
                        String message, String basis, LocalDateTime triggeredAt, LocalDateTime updatedAt, String updatedBy,
                        String resolution) {}

    private static final RowMapper<Session> SESSION = (rs, i) -> new Session(rs.getString("SESSION_ID"),
            rs.getString("PATIENT_ID"), rs.getString("ENCOUNTER_ID"), rs.getString("DEVICE_ID"), ldt(rs, "STARTED_AT"),
            ldt(rs, "ENDED_AT"), rs.getString("STATUS"), rs.getBoolean("CONNECTED"), ldt(rs, "LAST_SYNC_AT"),
            dbl(rs, "BATTERY_PCT"), ldt(rs, "SIM_CLOCK_AT"), rs.getBigDecimal("SIM_SEED") == null ? 0 : rs.getBigDecimal("SIM_SEED").longValue(),
            rs.getString("SCENARIO"));

    private static final RowMapper<Observation> OBSERVATION = (rs, i) -> new Observation(rs.getString("OBSERVATION_ID"),
            rs.getString("SESSION_ID"), rs.getString("PATIENT_ID"), rs.getString("ENCOUNTER_ID"), rs.getString("DEVICE_ID"),
            ldt(rs, "OBSERVED_AT"), rs.getString("OBS_TYPE"), dbl(rs, "VALUE_NUM"), rs.getString("UNIT"), rs.getString("SOURCE"),
            rs.getString("QUALITY"));

    private static final RowMapper<ActivityEvent> EVENT = (rs, i) -> new ActivityEvent(rs.getString("EVENT_ID"),
            rs.getString("SESSION_ID"), rs.getString("PATIENT_ID"), rs.getString("ENCOUNTER_ID"), rs.getString("EVENT_TYPE"),
            ldt(rs, "STARTED_AT"), ldt(rs, "ENDED_AT"), integer(rs, "DURATION_SECONDS"), rs.getString("SOURCE"),
            rs.getString("DETAIL"), rs.getString("RECORDED_BY"));

    private static final RowMapper<CarePlan> PLAN = (rs, i) -> new CarePlan(rs.getString("PLAN_ID"), rs.getString("PATIENT_ID"),
            rs.getString("ENCOUNTER_ID"), integer(rs, "MOBILITY_REVIEW_MINUTES"), integer(rs, "REPOSITIONING_INTERVAL_MINUTES"),
            integer(rs, "STALE_DATA_MINUTES"), rs.getString("PLAN_NOTE"), rs.getString("CREATED_BY"));

    private static final RowMapper<Intervention> INTERVENTION = (rs, i) -> new Intervention(rs.getString("INTERVENTION_ID"),
            rs.getString("PATIENT_ID"), rs.getString("ENCOUNTER_ID"), rs.getString("INTERVENTION_TYPE"), ldt(rs, "PERFORMED_AT"),
            rs.getString("DOCUMENTED_BY_ID"), rs.getString("DOCUMENTED_BY"), rs.getString("NOTE"), ldt(rs, "CREATED_AT"));

    private static final RowMapper<Alert> ALERT = (rs, i) -> new Alert(rs.getString("ALERT_ID"), rs.getString("SESSION_ID"),
            rs.getString("PATIENT_ID"), rs.getString("ENCOUNTER_ID"), rs.getString("ALERT_TYPE"), rs.getString("STATUS"),
            rs.getString("MESSAGE"), rs.getString("BASIS"), ldt(rs, "TRIGGERED_AT"), ldt(rs, "UPDATED_AT"),
            rs.getString("UPDATED_BY"), rs.getString("RESOLUTION"));

    private final JdbcTemplate jdbc;

    public MonitoringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ devices and sessions

    public Set<String> existingDeviceIds() {
        return new HashSet<>(jdbc.queryForList("SELECT DEVICE_ID FROM MONITORING_DEVICES", String.class));
    }

    public void insertDevice(Device d) {
        jdbc.update("INSERT INTO MONITORING_DEVICES (DEVICE_ID, MODEL, FIRMWARE, IS_SIMULATED, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                new Object[]{d.deviceId(), d.model(), d.firmware(), d.simulated(), ts(LocalDateTime.now())},
                new int[]{S, S, S, B, TS});
    }

    public Optional<Device> device(String deviceId) {
        return jdbc.query("SELECT * FROM MONITORING_DEVICES WHERE DEVICE_ID = ?", (rs, i) -> new Device(rs.getString("DEVICE_ID"),
                rs.getString("MODEL"), rs.getString("FIRMWARE"), rs.getBoolean("IS_SIMULATED")), deviceId).stream().findFirst();
    }

    public void insertSession(Session s) {
        jdbc.update("""
                INSERT INTO MONITORING_SESSIONS (SESSION_ID, PATIENT_ID, ENCOUNTER_ID, DEVICE_ID, STARTED_AT, ENDED_AT, STATUS,
                    CONNECTED, LAST_SYNC_AT, BATTERY_PCT, SIM_CLOCK_AT, SIM_SEED, SCENARIO, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{s.sessionId(), s.patientId(), s.encounterId(), s.deviceId(), ts(s.startedAt()), ts(s.endedAt()),
                        s.status(), s.connected(), ts(s.lastSyncAt()), s.batteryPct(), ts(s.simClockAt()), s.simSeed(),
                        s.scenario(), ts(LocalDateTime.now())},
                new int[]{S, S, S, S, TS, TS, S, B, TS, N, TS, java.sql.Types.BIGINT, S, TS});
    }

    public void updateSession(Session s) {
        jdbc.update("""
                UPDATE MONITORING_SESSIONS SET STATUS = ?, CONNECTED = ?, LAST_SYNC_AT = ?, BATTERY_PCT = ?, SIM_CLOCK_AT = ?, ENDED_AT = ?
                WHERE SESSION_ID = ? AND ENCOUNTER_ID = ?""",
                new Object[]{s.status(), s.connected(), ts(s.lastSyncAt()), s.batteryPct(), ts(s.simClockAt()), ts(s.endedAt()),
                        s.sessionId(), s.encounterId()},
                new int[]{S, B, TS, N, TS, TS, S, S});
    }

    public List<Session> sessionsForEncounter(String patientId, String encounterId) {
        return jdbc.query("SELECT * FROM MONITORING_SESSIONS WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ? ORDER BY STARTED_AT DESC",
                SESSION, patientId, encounterId);
    }

    public List<Session> sessionsForPatient(String patientId) {
        return jdbc.query("SELECT * FROM MONITORING_SESSIONS WHERE PATIENT_ID = ? ORDER BY STARTED_AT DESC", SESSION, patientId);
    }

    public List<Session> allSessions() {
        return jdbc.query("SELECT * FROM MONITORING_SESSIONS ORDER BY STARTED_AT DESC", SESSION);
    }

    // ------------------------------------------------------------ observations and events

    public void insertObservations(List<Observation> obs) {
        batch(jdbc, """
                INSERT INTO SENSOR_OBSERVATIONS (OBSERVATION_ID, SESSION_ID, PATIENT_ID, ENCOUNTER_ID, DEVICE_ID, OBSERVED_AT, OBS_TYPE,
                    VALUE_NUM, UNIT, SOURCE, QUALITY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                obs.stream().map(o -> new Object[]{o.observationId(), o.sessionId(), o.patientId(), o.encounterId(), o.deviceId(),
                        ts(o.observedAt()), o.type(), o.value(), o.unit(), o.source(), o.quality()}).toList(),
                S, S, S, S, S, TS, S, N, S, S, S);
    }

    /** Observations of one encounter in [from, to] (either bound may be null), optionally of one type. */
    public List<Observation> observations(String patientId, String encounterId, LocalDateTime from, LocalDateTime to, String type) {
        StringBuilder sql = new StringBuilder("SELECT * FROM SENSOR_OBSERVATIONS WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ?");
        List<Object> args = new ArrayList<>(List.of(patientId, encounterId));
        if (from != null) { sql.append(" AND OBSERVED_AT >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND OBSERVED_AT <= ?"); args.add(ts(to)); }
        if (type != null) { sql.append(" AND OBS_TYPE = ?"); args.add(type); }
        sql.append(" ORDER BY OBSERVED_AT");
        return jdbc.query(sql.toString(), OBSERVATION, args.toArray());
    }

    public Optional<Observation> latestObservation(String patientId, String encounterId, String type) {
        return jdbc.query("""
                SELECT * FROM SENSOR_OBSERVATIONS WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ? AND OBS_TYPE = ?
                ORDER BY OBSERVED_AT DESC LIMIT 1""", OBSERVATION, patientId, encounterId, type).stream().findFirst();
    }

    public void insertEvents(List<ActivityEvent> events) {
        batch(jdbc, """
                INSERT INTO ACTIVITY_EVENTS (EVENT_ID, SESSION_ID, PATIENT_ID, ENCOUNTER_ID, EVENT_TYPE, STARTED_AT, ENDED_AT,
                    DURATION_SECONDS, SOURCE, DETAIL, RECORDED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                events.stream().map(e -> new Object[]{e.eventId(), e.sessionId(), e.patientId(), e.encounterId(), e.eventType(),
                        ts(e.startedAt()), ts(e.endedAt()), e.durationSeconds(), e.source(), e.detail(), e.recordedBy()}).toList(),
                S, S, S, S, S, TS, TS, I, S, S, S);
    }

    public List<ActivityEvent> events(String patientId, String encounterId, LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ACTIVITY_EVENTS WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ?");
        List<Object> args = new ArrayList<>(List.of(patientId, encounterId));
        if (from != null) { sql.append(" AND STARTED_AT >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND STARTED_AT <= ?"); args.add(ts(to)); }
        sql.append(" ORDER BY STARTED_AT");
        return jdbc.query(sql.toString(), EVENT, args.toArray());
    }

    // ------------------------------------------------------------ care plans and interventions

    public void insertPlan(CarePlan p) {
        jdbc.update("""
                INSERT INTO MONITORING_CARE_PLANS (PLAN_ID, PATIENT_ID, ENCOUNTER_ID, MOBILITY_REVIEW_MINUTES,
                    REPOSITIONING_INTERVAL_MINUTES, STALE_DATA_MINUTES, PLAN_NOTE, CREATED_BY, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{p.planId(), p.patientId(), p.encounterId(), p.mobilityReviewMinutes(), p.repositioningIntervalMinutes(),
                        p.staleDataMinutes(), p.note(), p.createdBy(), ts(LocalDateTime.now())},
                new int[]{S, S, S, I, I, I, S, S, TS});
    }

    public Optional<CarePlan> plan(String patientId, String encounterId) {
        return jdbc.query("SELECT * FROM MONITORING_CARE_PLANS WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ? ORDER BY CREATED_AT DESC LIMIT 1",
                PLAN, patientId, encounterId).stream().findFirst();
    }

    public void insertIntervention(Intervention v) {
        jdbc.update("""
                INSERT INTO NURSING_INTERVENTIONS (INTERVENTION_ID, PATIENT_ID, ENCOUNTER_ID, INTERVENTION_TYPE, PERFORMED_AT,
                    DOCUMENTED_BY_ID, DOCUMENTED_BY, NOTE, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{v.interventionId(), v.patientId(), v.encounterId(), v.type(), ts(v.performedAt()), v.documentedById(),
                        v.documentedBy(), v.note(), ts(v.createdAt())},
                new int[]{S, S, S, S, TS, S, S, S, TS});
    }

    public List<Intervention> interventions(String patientId, String encounterId) {
        return jdbc.query("SELECT * FROM NURSING_INTERVENTIONS WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ? ORDER BY PERFORMED_AT",
                INTERVENTION, patientId, encounterId);
    }

    // ------------------------------------------------------------ alerts

    public void insertAlert(Alert a) {
        jdbc.update("""
                INSERT INTO MONITORING_ALERTS (ALERT_ID, SESSION_ID, PATIENT_ID, ENCOUNTER_ID, ALERT_TYPE, STATUS, MESSAGE, BASIS,
                    TRIGGERED_AT, UPDATED_AT, UPDATED_BY, RESOLUTION)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                new Object[]{a.alertId(), a.sessionId(), a.patientId(), a.encounterId(), a.alertType(), a.status(), a.message(),
                        a.basis(), ts(a.triggeredAt()), ts(a.updatedAt()), a.updatedBy(), a.resolution()},
                new int[]{S, S, S, S, S, S, S, S, TS, TS, S, S});
    }

    public void updateAlertStatus(String alertId, String status, LocalDateTime at, String by, String resolution) {
        jdbc.update("UPDATE MONITORING_ALERTS SET STATUS = ?, UPDATED_AT = ?, UPDATED_BY = ?, RESOLUTION = ? WHERE ALERT_ID = ?",
                new Object[]{status, ts(at), by, resolution, alertId}, new int[]{S, TS, S, S, S});
    }

    public Optional<Alert> alert(String alertId) {
        return jdbc.query("SELECT * FROM MONITORING_ALERTS WHERE ALERT_ID = ?", ALERT, alertId).stream().findFirst();
    }

    public List<Alert> alerts(String patientId, String encounterId) {
        return jdbc.query("SELECT * FROM MONITORING_ALERTS WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ? ORDER BY TRIGGERED_AT DESC",
                ALERT, patientId, encounterId);
    }

    public List<Alert> allAlerts() {
        return jdbc.query("SELECT * FROM MONITORING_ALERTS ORDER BY TRIGGERED_AT DESC", ALERT);
    }

    // ------------------------------------------------------------ simulator reset (synthetic monitoring data only)

    /** Deletes the simulated data of one encounter so its seeded scenario can be regenerated. Clinical records are untouched. */
    public void deleteSimulatedData(String patientId, String encounterId) {
        for (String table : List.of("SENSOR_OBSERVATIONS", "ACTIVITY_EVENTS", "MONITORING_ALERTS", "NURSING_INTERVENTIONS",
                "MONITORING_CARE_PLANS", "MONITORING_SESSIONS")) {
            jdbc.update("DELETE FROM " + table + " WHERE PATIENT_ID = ? AND ENCOUNTER_ID = ?", patientId, encounterId);
        }
    }
}
