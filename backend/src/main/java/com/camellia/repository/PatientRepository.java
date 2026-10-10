package com.camellia.repository;

import com.camellia.model.Patient;
import com.camellia.model.PatientIdentifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.camellia.repository.Jdbc.*;

@Repository
public class PatientRepository {

    private static final RowMapper<Patient> MAPPER = (rs, i) -> new Patient(
            rs.getString("PATIENT_ID"), rs.getString("DISPLAY_ID"), rs.getString("FIRST_NAME"), rs.getString("LAST_NAME"),
            ld(rs, "DATE_OF_BIRTH"), rs.getString("SEX"), rs.getBoolean("IS_OBSTETRIC"), ld(rs, "ESTIMATED_DUE_DATE"),
            rs.getString("GESTATIONAL_NOTE"), rs.getString("PRIMARY_ORG_ID"), rs.getString("SUMMARY"),
            rs.getString("DEMO_SCENARIO"), ldt(rs, "CREATED_AT"));

    private final JdbcTemplate jdbc;

    public PatientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Patient> findAll() {
        return jdbc.query("SELECT * FROM PATIENTS ORDER BY LAST_NAME, FIRST_NAME", MAPPER);
    }

    public Optional<Patient> findById(String patientId) {
        return jdbc.query("SELECT * FROM PATIENTS WHERE PATIENT_ID = ?", MAPPER, patientId).stream().findFirst();
    }

    public boolean exists(String patientId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PATIENTS WHERE PATIENT_ID = ?", Integer.class, patientId);
        return n != null && n > 0;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM PATIENTS", Long.class);
        return n == null ? 0 : n;
    }

    public Set<String> existingIds() {
        return new HashSet<>(jdbc.queryForList("SELECT PATIENT_ID FROM PATIENTS", String.class));
    }

    public void insertAll(List<Patient> patients) {
        if (patients.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        batch(jdbc, """
                INSERT INTO PATIENTS (PATIENT_ID, DISPLAY_ID, FIRST_NAME, LAST_NAME, DATE_OF_BIRTH, SEX, IS_OBSTETRIC,
                    ESTIMATED_DUE_DATE, GESTATIONAL_NOTE, PRIMARY_ORG_ID, SUMMARY, DEMO_SCENARIO, IS_SYNTHETIC, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)""",
                patients.stream().map(p -> new Object[]{p.patientId(), p.displayId(), p.firstName(), p.lastName(),
                        date(p.dateOfBirth()), p.sex(), p.obstetric(), date(p.estimatedDueDate()), p.gestationalNote(),
                        p.primaryOrgId(), p.summary(), p.demoScenario(), ts(now)}).toList(),
                S, S, S, S, D, S, B, D, S, S, S, S, TS);
    }

    public List<PatientIdentifier> findIdentifiers() {
        return jdbc.query("SELECT * FROM PATIENT_SOURCE_IDENTIFIERS",
                (rs, i) -> new PatientIdentifier(rs.getString("PATIENT_ID"), rs.getString("ORG_ID"), rs.getString("SOURCE_MRN")));
    }

    public List<PatientIdentifier> findIdentifiers(String patientId) {
        return jdbc.query("SELECT * FROM PATIENT_SOURCE_IDENTIFIERS WHERE PATIENT_ID = ? ORDER BY ORG_ID",
                (rs, i) -> new PatientIdentifier(rs.getString("PATIENT_ID"), rs.getString("ORG_ID"), rs.getString("SOURCE_MRN")),
                patientId);
    }

    public void insertIdentifiers(List<PatientIdentifier> ids) {
        if (ids.isEmpty()) return;
        batch(jdbc, "INSERT INTO PATIENT_SOURCE_IDENTIFIERS (PATIENT_ID, ORG_ID, SOURCE_MRN) VALUES (?, ?, ?)",
                ids.stream().map(x -> new Object[]{x.patientId(), x.orgId(), x.sourceMrn()}).toList(),
                S, S, S);
    }
}
