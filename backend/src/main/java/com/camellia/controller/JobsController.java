package com.camellia.controller;

import com.camellia.analysis.AnalysisService;
import com.camellia.ingestion.DemoDataService;
import com.camellia.integration.snowflake.SchemaInitializer;
import com.camellia.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point for scheduled and operator jobs in the camellia-jobs function. Lambda Web Adapter forwards non-HTTP
 * events (EventBridge Scheduler, {@code aws lambda invoke}) to {@code POST /events}; the event body is
 * {@code {"job": "<name>"}}.
 *
 * <p>Only registered when {@code camellia.jobs.enabled=true}. That function has no public route and runs with the
 * owner role, which is why seeding and demo resets live here instead of in the internet-facing API.</p>
 */
@RestController
@ConditionalOnProperty(prefix = "camellia.jobs", name = "enabled", havingValue = "true")
public class JobsController {

    private static final Logger log = LoggerFactory.getLogger(JobsController.class);

    private final SchemaInitializer schema;
    private final DemoDataService demoData;
    private final AnalysisService analysis;

    public JobsController(SchemaInitializer schema, DemoDataService demoData, AnalysisService analysis) {
        this.schema = schema;
        this.demoData = demoData;
        this.analysis = analysis;
    }

    @PostMapping("/events")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> event) {
        String job = event == null ? null : String.valueOf(event.get("job"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job);
        switch (job == null ? "" : job) {
            case "bootstrap" -> {
                schema.initialize();
                DemoDataService.SeedResult seed = demoData.seed();
                out.put("recordsInserted", seed.recordsInserted());
                out.put("anchorDate", seed.anchorDate().toString());
                out.put("screening", summary(analysis.run("System (bootstrap job)")));
            }
            case "screening" -> out.put("screening", summary(analysis.run("System (scheduled screening)")));
            case "reset-demo" -> {
                DemoDataService.SeedResult seed = demoData.resetAndReseed();
                out.put("recordsInserted", seed.recordsInserted());
                out.put("anchorDate", seed.anchorDate().toString());
                out.put("screening", summary(analysis.run("System (demo reset job)")));
            }
            case "simulate-lab-result" -> {
                DemoDataService.IngestResult ingest = demoData.ingestIncomingLabResult();
                out.put("recordsInserted", ingest.recordsInserted());
                out.put("recordsAlreadyPresent", ingest.recordsAlreadyPresent());
                out.put("screening", summary(analysis.run("System (new lab result job)")));
            }
            default -> throw ApiException.badRequest(
                    "Unknown job. Send {\"job\": \"bootstrap\" | \"screening\" | \"reset-demo\" | \"simulate-lab-result\"}.");
        }
        log.info("Job {} finished: {}", job, out);
        return out;
    }

    /** Counts only: job output ends up in logs, which should not carry patient details. */
    private static Map<String, Object> summary(AnalysisService.RunResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId());
        m.put("patientsEvaluated", r.patientsEvaluated());
        m.put("flagsCreated", r.flagsCreated());
        m.put("flagsUpdated", r.flagsUpdated());
        m.put("flagsUnchanged", r.flagsUnchanged());
        m.put("alertsCreated", r.alertsCreated());
        return m;
    }
}
