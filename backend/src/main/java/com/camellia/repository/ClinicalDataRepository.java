package com.camellia.repository;

import com.camellia.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

import static com.camellia.repository.Jdbc.*;

/** Read/write access to normalized clinical data (records, encounters, labs, conditions, medications, appointments). */
@Repository
public class ClinicalDataRepository {

    public static final RowMapper<ClinicalRecord> RECORD_MAPPER = (rs, i) -> new ClinicalRecord(
            rs.getString("RECORD_ID"), rs.getString("PATIENT_ID"), rs.getString("ORG_ID"), rs.getString("ENCOUNTER_ID"),
            RecordType.valueOf(rs.getString("RECORD_TYPE")), rs.getString("TITLE"), ldt(rs, "RECORD_DATE"),
            rs.getString("AUTHOR"), rs.getString("SPECIALTY"), rs.getString("CONTENT_TEXT"), rs.getString("SOURCE_FORMAT"),
            rs.getString("SOURCE_RECORD_ID"), rs.getString("RAW_CONTENT"), rs.getString("CATEGORY_TAGS"),
            ldt(rs, "INGESTED_AT"));

    private static final RowMapper<Encounter> ENCOUNTER_MAPPER = (rs, i) -> new Encounter(
            rs.getString("ENCOUNTER_ID"), rs.getString("RECORD_ID"), rs.getString("PATIENT_ID"), rs.getString("ORG_ID"),
            rs.getString("ENCOUNTER_TYPE"), rs.getString("DEPARTMENT"), rs.getString("SPECIALTY"),
            rs.getString("PROVIDER_NAME"), ldt(rs, "START_TIME"), rs.getString("REASON"),
            rs.getString("SYMPTOM_CATEGORY"), rs.getBoolean("IS_FRACTURE_RELATED"), ldt(rs, "END_TIME"));

    private static final RowMapper<LabResult> LAB_MAPPER = (rs, i) -> new LabResult(
            rs.getString("LAB_ID"), rs.getString("RECORD_ID"), rs.getString("PATIENT_ID"), rs.getString("ORG_ID"),
            rs.getString("TEST_CODE"), rs.getString("TEST_NAME"), dbl(rs, "VALUE_NUM"), rs.getString("VALUE_TEXT"),
            rs.getString("UNIT"), dbl(rs, "REF_LOW"), dbl(rs, "REF_HIGH"), rs.getString("REF_TEXT"),
            rs.getString("ABNORMAL_FLAG"), ldt(rs, "COLLECTED_AT"));

    private static final RowMapper<Condition> CONDITION_MAPPER = (rs, i) -> new Condition(
            rs.getString("CONDITION_ID"), rs.getString("RECORD_ID"), rs.getString("PATIENT_ID"), rs.getString("ORG_ID"),
            rs.getString("CODE"), rs.getString("CODE_SYSTEM"), rs.getString("DISPLAY"), rs.getString("CLINICAL_STATUS"),
            ld(rs, "ONSET_DATE"), rs.getString("RECORDED_BY"));

    private static final RowMapper<Medication> MEDICATION_MAPPER = (rs, i) -> new Medication(
            rs.getString("MEDICATION_ID"), rs.getString("RECORD_ID"), rs.getString("PATIENT_ID"), rs.getString("ORG_ID"),
            rs.getString("NAME"), rs.getString("DOSE"), rs.getString("ROUTE"), rs.getString("FREQUENCY"),
            rs.getString("STATUS"), ld(rs, "START_DATE"), ld(rs, "END_DATE"), rs.getString("PRESCRIBER"));

    private static final RowMapper<Appointment> APPOINTMENT_MAPPER = (rs, i) -> new Appointment(
            rs.getString("APPOINTMENT_ID"), rs.getString("RECORD_ID"), rs.getString("PATIENT_ID"), rs.getString("ORG_ID"),
            rs.getString("APPOINTMENT_TYPE"), rs.getString("DEPARTMENT"), ldt(rs, "SCHEDULED_START"),
            rs.getString("STATUS"), rs.getString("PROVIDER_NAME"), rs.getString("DESCRIPTION"),
            rs.getBoolean("IS_OBSTETRIC"));

    private final JdbcTemplate jdbc;

    public ClinicalDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- reads

    public List<ClinicalRecord> recordsForPatient(String patientId) {
        return jdbc.query("SELECT * FROM CLINICAL_RECORDS WHERE PATIENT_ID = ? ORDER BY RECORD_DATE DESC", RECORD_MAPPER, patientId);
    }

    public List<ClinicalRecord> allRecords() {
        return jdbc.query("SELECT * FROM CLINICAL_RECORDS ORDER BY PATIENT_ID, RECORD_DATE DESC", RECORD_MAPPER);
    }

    public List<ClinicalRecord> recordsOfType(RecordType type) {
        return jdbc.query("SELECT * FROM CLINICAL_RECORDS WHERE RECORD_TYPE = ?", RECORD_MAPPER, type.name());
    }

    public Map<String, Integer> recordCounts() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query("SELECT PATIENT_ID, COUNT(*) AS N FROM CLINICAL_RECORDS GROUP BY PATIENT_ID",
                rs -> { out.put(rs.getString("PATIENT_ID"), rs.getInt("N")); });
        return out;
    }

    public Optional<ClinicalRecord> findRecord(String recordId) {
        return jdbc.query("SELECT * FROM CLINICAL_RECORDS WHERE RECORD_ID = ?", RECORD_MAPPER, recordId).stream().findFirst();
    }

    public List<ClinicalRecord> findRecords(Collection<String> recordIds) {
        if (recordIds.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM CLINICAL_RECORDS WHERE RECORD_ID IN (" + placeholders(recordIds) + ") ORDER BY RECORD_DATE DESC",
                RECORD_MAPPER, recordIds.toArray());
    }

    /** Executes a search built by {@code SearchQueryBuilder} from allow-listed fragments with bound parameters only. */
    public List<ClinicalRecord> queryRecords(String sql, List<Object> params) {
        return jdbc.query(sql, RECORD_MAPPER, params.toArray());
    }

    public Set<String> existingRecordIds() {
        return new HashSet<>(jdbc.queryForList("SELECT RECORD_ID FROM CLINICAL_RECORDS", String.class));
    }

    public Map<String, Set<String>> organizationsByPatient() {
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.query("SELECT DISTINCT PATIENT_ID, ORG_ID FROM CLINICAL_RECORDS", rs -> {
            out.computeIfAbsent(rs.getString("PATIENT_ID"), k -> new TreeSet<>()).add(rs.getString("ORG_ID"));
        });
        return out;
    }

    public List<Encounter> encountersForPatient(String patientId) {
        return jdbc.query("SELECT * FROM ENCOUNTERS WHERE PATIENT_ID = ? ORDER BY START_TIME DESC", ENCOUNTER_MAPPER, patientId);
    }

    public Optional<Encounter> findEncounter(String encounterId) {
        return jdbc.query("SELECT * FROM ENCOUNTERS WHERE ENCOUNTER_ID = ?", ENCOUNTER_MAPPER, encounterId).stream().findFirst();
    }

    public List<Encounter> allEncounters() {
        return jdbc.query("SELECT * FROM ENCOUNTERS", ENCOUNTER_MAPPER);
    }

    public List<LabResult> labsForPatient(String patientId) {
        return jdbc.query("SELECT * FROM LAB_RESULTS WHERE PATIENT_ID = ? ORDER BY COLLECTED_AT DESC", LAB_MAPPER, patientId);
    }

    public List<LabResult> allLabs() {
        return jdbc.query("SELECT * FROM LAB_RESULTS", LAB_MAPPER);
    }

    public List<Condition> conditionsForPatient(String patientId) {
        return jdbc.query("SELECT * FROM MEDICAL_CONDITIONS WHERE PATIENT_ID = ? ORDER BY ONSET_DATE DESC", CONDITION_MAPPER, patientId);
    }

    public List<Condition> allConditions() {
        return jdbc.query("SELECT * FROM MEDICAL_CONDITIONS", CONDITION_MAPPER);
    }

    public List<Medication> medicationsForPatient(String patientId) {
        return jdbc.query("SELECT * FROM MEDICATIONS WHERE PATIENT_ID = ? ORDER BY START_DATE DESC", MEDICATION_MAPPER, patientId);
    }

    public List<Medication> allMedications() {
        return jdbc.query("SELECT * FROM MEDICATIONS", MEDICATION_MAPPER);
    }

    public List<Appointment> appointmentsForPatient(String patientId) {
        return jdbc.query("SELECT * FROM APPOINTMENTS WHERE PATIENT_ID = ? ORDER BY SCHEDULED_START", APPOINTMENT_MAPPER, patientId);
    }

    public List<Appointment> allAppointments() {
        return jdbc.query("SELECT * FROM APPOINTMENTS ORDER BY SCHEDULED_START", APPOINTMENT_MAPPER);
    }

    public Optional<Appointment> findAppointment(String appointmentId) {
        return jdbc.query("SELECT * FROM APPOINTMENTS WHERE APPOINTMENT_ID = ?", APPOINTMENT_MAPPER, appointmentId).stream().findFirst();
    }

    public List<Appointment> upcomingAppointments(LocalDateTime from, LocalDateTime to) {
        return jdbc.query("SELECT * FROM APPOINTMENTS WHERE SCHEDULED_START >= ? AND SCHEDULED_START <= ? ORDER BY SCHEDULED_START",
                APPOINTMENT_MAPPER, ts(from), ts(to));
    }

    // ---------------------------------------------------------------- writes (batched: one round-trip per table)

    public void insertRecords(List<ClinicalRecord> records) {
        if (records.isEmpty()) return;
        batch(jdbc, """
                INSERT INTO CLINICAL_RECORDS (RECORD_ID, PATIENT_ID, ORG_ID, ENCOUNTER_ID, RECORD_TYPE, TITLE, RECORD_DATE, AUTHOR,
                    SPECIALTY, CONTENT_TEXT, SOURCE_FORMAT, SOURCE_RECORD_ID, RAW_CONTENT, CATEGORY_TAGS, INGESTED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                records.stream().map(r -> new Object[]{r.recordId(), r.patientId(), r.orgId(), r.encounterId(),
                        r.recordType().name(), r.title(), ts(r.recordDate()), r.author(), r.specialty(), r.contentText(),
                        r.sourceFormat(), r.sourceRecordId(), r.rawContent(), r.categoryTags(), ts(r.ingestedAt())}).toList(),
                S, S, S, S, S, S, TS, S, S, S, S, S, S, S, TS);
    }

    public void insertEncounters(List<Encounter> encounters) {
        if (encounters.isEmpty()) return;
        batch(jdbc, """
                INSERT INTO ENCOUNTERS (ENCOUNTER_ID, RECORD_ID, PATIENT_ID, ORG_ID, ENCOUNTER_TYPE, DEPARTMENT, SPECIALTY,
                    PROVIDER_NAME, START_TIME, REASON, SYMPTOM_CATEGORY, IS_FRACTURE_RELATED, END_TIME)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                encounters.stream().map(e -> new Object[]{e.encounterId(), e.recordId(), e.patientId(), e.orgId(),
                        e.encounterType(), e.department(), e.specialty(), e.providerName(), ts(e.startTime()), e.reason(),
                        e.symptomCategory(), e.fractureRelated(), ts(e.endTime())}).toList(),
                S, S, S, S, S, S, S, S, TS, S, S, B, TS);
    }

    public void insertLabs(List<LabResult> labs) {
        if (labs.isEmpty()) return;
        batch(jdbc, """
                INSERT INTO LAB_RESULTS (LAB_ID, RECORD_ID, PATIENT_ID, ORG_ID, TEST_CODE, TEST_NAME, VALUE_NUM, VALUE_TEXT, UNIT,
                    REF_LOW, REF_HIGH, REF_TEXT, ABNORMAL_FLAG, COLLECTED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                labs.stream().map(l -> new Object[]{l.labId(), l.recordId(), l.patientId(), l.orgId(), l.testCode(),
                        l.testName(), l.valueNum(), l.valueText(), l.unit(), l.refLow(), l.refHigh(), l.refText(),
                        l.abnormalFlag(), ts(l.collectedAt())}).toList(),
                S, S, S, S, S, S, N, S, S, N, N, S, S, TS);
    }

    public void insertConditions(List<Condition> conditions) {
        if (conditions.isEmpty()) return;
        batch(jdbc, """
                INSERT INTO MEDICAL_CONDITIONS (CONDITION_ID, RECORD_ID, PATIENT_ID, ORG_ID, CODE, CODE_SYSTEM, DISPLAY,
                    CLINICAL_STATUS, ONSET_DATE, RECORDED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                conditions.stream().map(c -> new Object[]{c.conditionId(), c.recordId(), c.patientId(), c.orgId(), c.code(),
                        c.codeSystem(), c.display(), c.clinicalStatus(), date(c.onsetDate()), c.recordedBy()}).toList(),
                S, S, S, S, S, S, S, S, D, S);
    }

    public void insertMedications(List<Medication> meds) {
        if (meds.isEmpty()) return;
        batch(jdbc, """
                INSERT INTO MEDICATIONS (MEDICATION_ID, RECORD_ID, PATIENT_ID, ORG_ID, NAME, DOSE, ROUTE, FREQUENCY, STATUS,
                    START_DATE, END_DATE, PRESCRIBER)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                meds.stream().map(m -> new Object[]{m.medicationId(), m.recordId(), m.patientId(), m.orgId(), m.name(),
                        m.dose(), m.route(), m.frequency(), m.status(), date(m.startDate()), date(m.endDate()),
                        m.prescriber()}).toList(),
                S, S, S, S, S, S, S, S, S, D, D, S);
    }

    public void insertAppointments(List<Appointment> appts) {
        if (appts.isEmpty()) return;
        batch(jdbc, """
                INSERT INTO APPOINTMENTS (APPOINTMENT_ID, RECORD_ID, PATIENT_ID, ORG_ID, APPOINTMENT_TYPE, DEPARTMENT,
                    SCHEDULED_START, STATUS, PROVIDER_NAME, DESCRIPTION, IS_OBSTETRIC)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                appts.stream().map(a -> new Object[]{a.appointmentId(), a.recordId(), a.patientId(), a.orgId(),
                        a.appointmentType(), a.department(), ts(a.scheduledStart()), a.status(), a.providerName(),
                        a.description(), a.obstetric()}).toList(),
                S, S, S, S, S, S, TS, S, S, S, B);
    }
}
