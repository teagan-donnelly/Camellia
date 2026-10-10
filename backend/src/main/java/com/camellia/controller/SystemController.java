package com.camellia.controller;

import com.camellia.analysis.AnalysisService;
import com.camellia.config.DemoUsers;
import com.camellia.coordination.CoordinationService;
import com.camellia.ingestion.DemoDataService;
import com.camellia.integration.IntegrationService;
import com.camellia.model.Organization;
import com.camellia.repository.OrganizationRepository;
import com.camellia.search.SearchCriteria;
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

    public SystemController(IntegrationService integrations, DemoDataService demoData, AnalysisService analysis,
                            OrganizationRepository organizations) {
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
        m.put("users", DemoUsers.ALL);
        m.put("alertStatuses", CoordinationService.ALERT_STATUSES);
        m.put("contactStatuses", CoordinationService.CONTACT_STATUSES);
        m.put("noteTypes", CoordinationService.NOTE_TYPES);
        m.put("flagStatuses", CoordinationService.FLAG_STATUSES);
        m.put("concepts", SearchCriteria.CONCEPTS);
        m.put("recordTypes", SearchCriteria.RECORD_TYPES);
        List<Organization> orgs = organizations.findAll();
        m.put("organizations", orgs);
        m.put("accessControlNotice", "Users and teams are SIMULATED. There is no authentication; never use this prototype with real patient data.");
        return m;
    }

    /** Deletes all demo data and workflow state, re-seeds anchored to today, and re-runs screening. */
    @PostMapping("/admin/reset-demo")
    public Map<String, Object> reset(@RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        DemoDataService.SeedResult seed = demoData.resetAndReseed();
        AnalysisService.RunResult run = analysis.run(CurrentUser.of(user).name() + " (demo reset)");
        return Map.of("seed", seed, "analysis", run);
    }

    /** Simulates a new HL7 lab result for Patient A arriving from the reference lab, then re-runs screening. */
    @PostMapping("/admin/simulate-lab-result")
    public Map<String, Object> simulateLab(@RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        DemoDataService.IngestResult ingest = demoData.ingestIncomingLabResult();
        AnalysisService.RunResult run = analysis.run(CurrentUser.of(user).name() + " (new lab result)");
        return Map.of("ingest", ingest, "analysis", run);
    }
}
