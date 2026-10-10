package com.camellia.controller;

import com.camellia.analysis.AnalysisService;
import com.camellia.coordination.CoordinationService;
import com.camellia.ingestion.DemoDataService;
import com.camellia.integration.IntegrationService;
import com.camellia.model.Organization;
import com.camellia.repository.OrganizationRepository;
import com.camellia.search.SearchCriteria;
import com.camellia.security.AuthorizationService;
import com.camellia.security.DemoUser;
import com.camellia.security.DemoDirectory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Integration status/checks, reference metadata, and demo administration. */
@RestController
@RequestMapping("/api")
public class SystemController {

    private final IntegrationService integrations;
    private final DemoDataService demoData;
    private final AnalysisService analysis;
    private final OrganizationRepository organizations;
    private final AuthorizationService auth;

    public SystemController(IntegrationService integrations, DemoDataService demoData, AnalysisService analysis,
                            OrganizationRepository organizations, AuthorizationService auth) {
        this.auth = auth;
        this.integrations = integrations;
        this.demoData = demoData;
        this.analysis = analysis;
        this.organizations = organizations;
    }

    @GetMapping("/integrations/status")
    public Map<String, Object> status() {
        return integrations.status();
    }

    /** Real read + real write against the active database (Snowflake when configured). */
    @PostMapping("/integrations/database/check")
    public Map<String, Object> databaseCheck() {
        return integrations.databaseCheck();
    }

    /** Real Gemini API call. */
    @PostMapping("/integrations/gemini/check")
    public Map<String, Object> geminiCheck() {
        return integrations.geminiCheck();
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", DemoDirectory.USERS.stream().map(u -> Map.of("id", u.id(), "name", u.name(), "role", u.role().label())).toList());
        m.put("alertStatuses", CoordinationService.ALERT_STATUSES);
        m.put("contactStatuses", CoordinationService.CONTACT_STATUSES);
        m.put("noteTypes", CoordinationService.NOTE_TYPES);
        m.put("flagStatuses", CoordinationService.FLAG_STATUSES);
        m.put("concepts", SearchCriteria.CONCEPTS);
        m.put("recordTypes", SearchCriteria.RECORD_TYPES);
        List<Organization> orgs = organizations.findAll();
        m.put("organizations", orgs);
        m.put("accessControlNotice", "Users, roles and practices are SIMULATED demo identities. There is no real authentication; never use this prototype with real patient data.");
        return m;
    }

    /** Deletes all demo data and workflow state, re-seeds anchored to today, and re-runs screening. Reports only counts. */
    @PostMapping("/admin/reset-demo")
    public Map<String, Object> reset(DemoUser user) {
        DemoDataService.SeedResult seed = demoData.resetAndReseed();
        AnalysisService.RunResult run = FlagController.scoped(analysis.run(user.name() + " (demo reset)"), auth.scope(user));
        return Map.of("anchorDate", seed.anchorDate().toString(), "recordsInserted", seed.recordsInserted(), "analysis", run);
    }

    /** Simulates a new HL7 lab result for Patient A arriving from the reference lab, then re-runs screening. */
    @PostMapping("/admin/simulate-lab-result")
    public Map<String, Object> simulateLab(DemoUser user) {
        DemoDataService.IngestResult ingest = demoData.ingestIncomingLabResult();
        AnalysisService.RunResult run = FlagController.scoped(analysis.run(user.name() + " (new lab result)"), auth.scope(user));
        return Map.of("ingest", Map.of("recordsInserted", ingest.recordsInserted(), "recordsAlreadyPresent", ingest.recordsAlreadyPresent()),
                "analysis", run);
    }
}
