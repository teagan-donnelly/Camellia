package com.camellia.repository;

import com.camellia.security.DemoDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.camellia.repository.Jdbc.*;

/** Synthetic clinicians, their practices, and simulated provider shifts with patient assignments. */
@Repository
public class WorkforceRepository {

    public record Shift(String shiftId, String userId, LocalDate shiftDate, LocalDateTime startAt, LocalDateTime endAt,
                        String orgId, String department, String location, String label) {}

    public record Assignment(String shiftId, String patientId, String role, String note) {}

    private static final RowMapper<Shift> SHIFT_MAPPER = (rs, i) -> new Shift(rs.getString("SHIFT_ID"), rs.getString("USER_ID"),
            ld(rs, "SHIFT_DATE"), ldt(rs, "START_AT"), ldt(rs, "END_AT"), rs.getString("ORG_ID"), rs.getString("DEPARTMENT"),
            rs.getString("LOCATION"), rs.getString("SHIFT_LABEL"));

    private static final RowMapper<Assignment> ASSIGNMENT_MAPPER = (rs, i) -> new Assignment(rs.getString("SHIFT_ID"),
            rs.getString("PATIENT_ID"), rs.getString("ASSIGNMENT_ROLE"), rs.getString("NOTE"));

    private final JdbcTemplate jdbc;

    public WorkforceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ clinicians

    public Set<String> existingClinicianIds() {
        return new HashSet<>(jdbc.queryForList("SELECT USER_ID FROM CLINICIANS", String.class));
    }

    public void insertClinicians(List<DemoDirectory.Clinician> clinicians) {
        LocalDateTime now = LocalDateTime.now();
        batch(jdbc, """
                INSERT INTO CLINICIANS (USER_ID, DISPLAY_NAME, ROLE, DEPARTMENT, ORG_ID, SPECIALTY, CREDENTIALS, IS_DEMO_ACCOUNT, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                clinicians.stream().map(c -> new Object[]{c.user().id(), c.user().name(), c.user().role().name(),
                        c.user().department(), c.orgId(), c.specialty(), c.credentials(), c.demoAccount(), ts(now)}).toList(),
                S, S, S, S, S, S, S, B, TS);
    }

    public Set<String> existingClinicianPracticeKeys() {
        Set<String> out = new HashSet<>();
        jdbc.query("SELECT USER_ID, PRACTICE_ID FROM CLINICIAN_PRACTICES",
                rs -> { out.add(rs.getString(1) + "|" + rs.getString(2)); });
        return out;
    }

    public void insertClinicianPractices(List<String[]> userPractice) {
        batch(jdbc, "INSERT INTO CLINICIAN_PRACTICES (USER_ID, PRACTICE_ID) VALUES (?, ?)",
                userPractice.stream().map(a -> new Object[]{a[0], a[1]}).toList(), S, S);
    }

    /** Clinician counts per role, straight from the database. */
    public Map<String, Long> clinicianCountsByRole() {
        Map<String, Long> out = new TreeMap<>();
        jdbc.query("SELECT ROLE, COUNT(*) AS N FROM CLINICIANS GROUP BY ROLE",
                rs -> { out.put(rs.getString("ROLE"), rs.getLong("N")); });
        return out;
    }

    // ------------------------------------------------------------ shifts

    public Set<String> existingShiftIds() {
        return new HashSet<>(jdbc.queryForList("SELECT SHIFT_ID FROM PROVIDER_SHIFTS", String.class));
    }

    public void insertShifts(List<Shift> shifts) {
        LocalDateTime now = LocalDateTime.now();
        batch(jdbc, """
                INSERT INTO PROVIDER_SHIFTS (SHIFT_ID, USER_ID, SHIFT_DATE, START_AT, END_AT, ORG_ID, DEPARTMENT, LOCATION, SHIFT_LABEL, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                shifts.stream().map(s -> new Object[]{s.shiftId(), s.userId(), date(s.shiftDate()), ts(s.startAt()), ts(s.endAt()),
                        s.orgId(), s.department(), s.location(), s.label(), ts(now)}).toList(),
                S, S, D, TS, TS, S, S, S, S, TS);
    }

    public void insertAssignments(List<Assignment> assignments) {
        batch(jdbc, "INSERT INTO SHIFT_PATIENT_ASSIGNMENTS (SHIFT_ID, PATIENT_ID, ASSIGNMENT_ROLE, NOTE) VALUES (?, ?, ?, ?)",
                assignments.stream().map(a -> new Object[]{a.shiftId(), a.patientId(), a.role(), a.note()}).toList(), S, S, S, S);
    }

    public List<Shift> shiftsForUser(String userId) {
        return jdbc.query("SELECT * FROM PROVIDER_SHIFTS WHERE USER_ID = ? ORDER BY START_AT", SHIFT_MAPPER, userId);
    }

    public Optional<Shift> findShift(String shiftId) {
        return jdbc.query("SELECT * FROM PROVIDER_SHIFTS WHERE SHIFT_ID = ?", SHIFT_MAPPER, shiftId).stream().findFirst();
    }

    public List<Assignment> assignments(String shiftId) {
        return jdbc.query("SELECT * FROM SHIFT_PATIENT_ASSIGNMENTS WHERE SHIFT_ID = ? ORDER BY PATIENT_ID", ASSIGNMENT_MAPPER, shiftId);
    }
}
