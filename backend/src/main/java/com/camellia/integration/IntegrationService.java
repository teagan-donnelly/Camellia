package com.camellia.integration;

import com.camellia.ai.ClinicalAiService;
import com.camellia.integration.gemini.GoogleGenAiGateway;
import com.camellia.integration.snowflake.DatabaseMode;
import com.camellia.repository.PatientRepository;
import com.camellia.repository.SupportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Live connectivity checks for Snowflake (real read + write) and Gemini (real API call). */
@Service
public class IntegrationService {

    private final DatabaseMode mode;
    private final JdbcTemplate jdbc;
    private final SupportRepository support;
    private final PatientRepository patients;
    private final GoogleGenAiGateway gemini;
    private final ClinicalAiService ai;

    public IntegrationService(DatabaseMode mode, JdbcTemplate jdbc, SupportRepository support, PatientRepository patients,
                              GoogleGenAiGateway gemini, ClinicalAiService ai) {
        this.mode = mode;
        this.jdbc = jdbc;
        this.support = support;
        this.patients = patients;
        this.gemini = gemini;
        this.ai = ai;
    }

    public DatabaseMode mode() {
        return mode;
    }

    /** Cheap status for the header badges (no network calls to Gemini). */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("mode", mode.name());
        db.put("label", mode.label());
        db.put("isSnowflake", mode == DatabaseMode.SNOWFLAKE);
        try {
            db.put("session", sessionInfo());
            db.put("connected", true);
            db.put("patientCount", patients.count());
        } catch (RuntimeException e) {
            db.put("connected", false);
            db.put("error", rootMessage(e));
        }
        out.put("database", db);
        GoogleGenAiGateway.Stats s = gemini.stats();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("configured", s.configured());
        g.put("model", s.model());
        g.put("sdk", "com.google.genai:google-genai (Google Gen AI Java SDK)");
        g.put("callsSinceStartup", s.calls());
        g.put("failuresSinceStartup", s.failures());
        g.put("lastSuccessAt", s.lastSuccessAt());
        g.put("lastError", s.lastError());
        g.put("lastOperation", s.lastOperation());
        out.put("gemini", g);
        out.put("anchorDate", support.getMeta("ANCHOR_DATE").orElse(null));
        out.put("syntheticDataNotice", "Demo application using synthetic patient information. Not for clinical use.");
        return out;
    }

    /** Performs a real read and a real write (insert + read-back) against the active database. */
    public Map<String, Object> databaseCheck() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode.name());
        out.put("label", mode.label());
        long start = System.currentTimeMillis();
        out.put("session", sessionInfo());
        Map<String, Object> read = jdbc.queryForMap("""
                SELECT p.PATIENT_ID, p.FIRST_NAME, p.LAST_NAME, COUNT(r.RECORD_ID) AS RECORD_COUNT
                FROM PATIENTS p LEFT JOIN CLINICAL_RECORDS r ON r.PATIENT_ID = p.PATIENT_ID
                WHERE p.PATIENT_ID = 'CAM-P-1001' GROUP BY p.PATIENT_ID, p.FIRST_NAME, p.LAST_NAME""");
        out.put("readCheck", Map.of("query", "Patient CAM-P-1001 with record count", "result", read));
        String checkId = SupportRepository.newId("CHK");
        String detail = "Integration write check at " + LocalDateTime.now();
        support.insertIntegrationCheck(checkId, "WRITE", detail);
        String readBack = support.readIntegrationCheck(checkId).orElse(null);
        out.put("writeCheck", Map.of("checkId", checkId, "inserted", detail, "readBack", String.valueOf(readBack),
                "verified", detail.equals(readBack), "totalCheckRows", support.countIntegrationChecks()));
        out.put("elapsedMs", System.currentTimeMillis() - start);
        support.logActivity("SYSTEM", checkId, null, "DB_CHECK", "Database read/write check passed on " + mode.label(), "System");
        return out;
    }

    public Map<String, Object> geminiCheck() {
        return ai.ping();
    }

    private Map<String, Object> sessionInfo() {
        if (mode == DatabaseMode.SNOWFLAKE) {
            return jdbc.queryForMap("""
                    SELECT CURRENT_VERSION() AS VERSION, CURRENT_ACCOUNT() AS ACCOUNT, CURRENT_REGION() AS REGION,
                           CURRENT_WAREHOUSE() AS WAREHOUSE, CURRENT_DATABASE() AS DATABASE_NAME, CURRENT_SCHEMA() AS SCHEMA_NAME,
                           CURRENT_ROLE() AS ROLE_NAME, CURRENT_USER() AS USER_NAME""");
        }
        return jdbc.queryForMap("SELECT H2VERSION() AS VERSION, DATABASE() AS DATABASE_NAME, SCHEMA() AS SCHEMA_NAME");
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
