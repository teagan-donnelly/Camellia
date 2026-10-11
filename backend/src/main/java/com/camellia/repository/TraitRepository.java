package com.camellia.repository;

import com.camellia.model.PatientTrait;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.camellia.repository.Jdbc.*;

/** PATIENT_TRAITS: structured traits derived from coded diagnoses. */
@Repository
public class TraitRepository {

    private static final RowMapper<PatientTrait> MAPPER = (rs, i) -> new PatientTrait(rs.getString("TRAIT_ID"),
            rs.getString("PATIENT_ID"), rs.getString("ENCOUNTER_ID"), rs.getString("TRAIT_CODE"), rs.getString("TRAIT_LABEL"),
            rs.getString("CODE_SYSTEM"), rs.getString("CODE"), rs.getString("CODE_DISPLAY"), rs.getString("ORG_ID"),
            rs.getString("SOURCE_RECORD_ID"), rs.getString("CONDITION_ID"), rs.getString("STATUS"), ldt(rs, "RECORDED_AT"),
            rs.getString("RECORDED_BY"), ldt(rs, "CREATED_AT"));

    private final JdbcTemplate jdbc;

    public TraitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> existingIds() {
        return new HashSet<>(jdbc.queryForList("SELECT TRAIT_ID FROM PATIENT_TRAITS", String.class));
    }

    public void insertAll(List<PatientTrait> traits) {
        batch(jdbc, """
                INSERT INTO PATIENT_TRAITS (TRAIT_ID, PATIENT_ID, ENCOUNTER_ID, TRAIT_CODE, TRAIT_LABEL, CODE_SYSTEM, CODE,
                    CODE_DISPLAY, ORG_ID, SOURCE_RECORD_ID, CONDITION_ID, STATUS, RECORDED_AT, RECORDED_BY, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                traits.stream().map(t -> new Object[]{t.traitId(), t.patientId(), t.encounterId(), t.traitCode(), t.traitLabel(),
                        t.codeSystem(), t.code(), t.codeDisplay(), t.orgId(), t.sourceRecordId(), t.conditionId(), t.status(),
                        ts(t.recordedAt()), t.recordedBy(), ts(t.createdAt())}).toList(),
                S, S, S, S, S, S, S, S, S, S, S, S, TS, S, TS);
    }

    public List<PatientTrait> forPatient(String patientId) {
        return jdbc.query("SELECT * FROM PATIENT_TRAITS WHERE PATIENT_ID = ? ORDER BY RECORDED_AT DESC, TRAIT_ID",
                MAPPER, patientId);
    }

    public List<PatientTrait> all() {
        return jdbc.query("SELECT * FROM PATIENT_TRAITS ORDER BY PATIENT_ID, TRAIT_CODE", MAPPER);
    }
}
