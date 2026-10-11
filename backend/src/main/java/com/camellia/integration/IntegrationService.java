package com.camellia.integration;

import com.camellia.ai.ClinicalAiService;
import com.camellia.config.CamelliaProperties;
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
    private final CamelliaProperties props;

    /**
     * The anchor date only changes on a demo reset, so the status poll reads it at most this often. Otherwise every
     * open browser tab would query a table each minute and could keep a Snowflake warehouse from suspending.
     */
    static final long ANCHOR_CACHE_MS = 5 * 60_000L;
    private volatile String cachedAnchorDate;
    private volatile long anchorDateReadAt;

    public IntegrationService(DatabaseMode mode, JdbcTemplate jdbc, SupportRepository support, PatientRepository patients,
                              GoogleGenAiGateway gemini, ClinicalAiService ai, CamelliaProperties props) {
        this.mode = mode;
        this.jdbc = jdbc;
        this.support = support;
        this.patients = patients;
        this.gemini = gemini;
        this.ai = ai;
        this.props = props;
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
        out.put("anchorDate", anchorDate());
        out.put("demoAdminEnabled", props.getDemo().isAdminEnabled());
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
        // The read check uses non-patient reference data so this endpoint never discloses patient information.
        Map<String, Object> read = jdbc.queryForMap("""
                SELECT COUNT(DISTINCT o.ORG_ID) AS ORGANIZATIONS, COUNT(DISTINCT pr.PRACTICE_ID) AS PRACTICES
                FROM HEALTHCARE_ORGANIZATIONS o LEFT JOIN PRACTICES pr ON pr.ORG_ID = o.ORG_ID""");
        out.put("readCheck", Map.of("query", "JOIN of HEALTHCARE_ORGANIZATIONS and PRACTICES (reference data)", "result", read));
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

    /** Forces the next status call to re-read the anchor date (after a demo reset on this instance). */
    public void forgetAnchorDate() {
        anchorDateReadAt = 0;
    }

    private String anchorDate() {
        long now = System.currentTimeMillis();
        if (anchorDateReadAt == 0 || now - anchorDateReadAt > ANCHOR_CACHE_MS) {
            try {
                cachedAnchorDate = support.getMeta("ANCHOR_DATE").orElse(null);
                anchorDateReadAt = now;
            } catch (RuntimeException e) {
                return cachedAnchorDate;   // the database badge already reports the failure
            }
        }
        return cachedAnchorDate;
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
