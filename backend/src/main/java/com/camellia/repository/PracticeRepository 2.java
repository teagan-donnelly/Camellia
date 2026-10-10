package com.camellia.repository;

import com.camellia.model.Practice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

import static com.camellia.repository.Jdbc.*;

@Repository
public class PracticeRepository {

    public record Association(String patientId, String practiceId, String relationship) {}

    private final JdbcTemplate jdbc;

    public PracticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Practice> findAll() {
        return jdbc.query("SELECT * FROM PRACTICES ORDER BY PRACTICE_ID", (rs, i) -> new Practice(rs.getString("PRACTICE_ID"),
                rs.getString("NAME"), rs.getString("ORG_ID"), rs.getString("SPECIALTY")));
    }

    public Set<String> existingIds() {
        return new HashSet<>(jdbc.queryForList("SELECT PRACTICE_ID FROM PRACTICES", String.class));
    }

    public void insertAll(List<Practice> practices) {
        batch(jdbc, "INSERT INTO PRACTICES (PRACTICE_ID, NAME, ORG_ID, SPECIALTY, IS_SYNTHETIC) VALUES (?, ?, ?, ?, TRUE)",
                practices.stream().map(p -> new Object[]{p.practiceId(), p.name(), p.orgId(), p.specialty()}).toList(),
                S, S, S, S);
    }

    public List<Association> allAssociations() {
        return jdbc.query("SELECT * FROM PATIENT_PRACTICE_ASSOCIATIONS", (rs, i) -> new Association(
                rs.getString("PATIENT_ID"), rs.getString("PRACTICE_ID"), rs.getString("RELATIONSHIP")));
    }

    public void insertAssociations(List<Association> associations) {
        LocalDateTime now = LocalDateTime.now();
        batch(jdbc, "INSERT INTO PATIENT_PRACTICE_ASSOCIATIONS (PATIENT_ID, PRACTICE_ID, RELATIONSHIP, REGISTERED_AT) VALUES (?, ?, ?, ?)",
                associations.stream().map(a -> new Object[]{a.patientId(), a.practiceId(), a.relationship(), ts(now)}).toList(),
                S, S, S, TS);
    }

    /** Patient ids registered to any of the given practices (one bound query). */
    public Set<String> patientIdsForPractices(Collection<String> practiceIds) {
        if (practiceIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT PATIENT_ID FROM PATIENT_PRACTICE_ASSOCIATIONS WHERE PRACTICE_ID IN ("
                + placeholders(practiceIds) + ")", String.class, practiceIds.toArray()));
    }

    public Map<String, List<String>> practicesByPatient() {
        Map<String, List<String>> out = new HashMap<>();
        allAssociations().forEach(a -> out.computeIfAbsent(a.patientId(), k -> new ArrayList<>()).add(a.practiceId()));
        return out;
    }
}
