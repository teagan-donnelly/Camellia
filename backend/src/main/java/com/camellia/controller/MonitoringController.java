package com.camellia.controller;

import com.camellia.monitoring.MonitoringService;
import com.camellia.monitoring.MonitoringService.*;
import com.camellia.repository.MonitoringRepository.Alert;
import com.camellia.security.DemoUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Encounter-scoped wearable monitoring (SIMULATED), the nursing work queue, nursing documentation and the simulator.
 * Every endpoint resolves the user from the demo session and is authorized in {@link MonitoringService}.
 */
@RestController
@RequestMapping("/api")
public class MonitoringController {

    private final MonitoringService monitoring;

    public MonitoringController(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping("/patients/{patientId}/encounters")
    public List<EncounterView> encounters(DemoUser user, @PathVariable String patientId) {
        return monitoring.encounters(user, patientId);
    }

    @GetMapping("/patients/{patientId}/encounters/{encounterId}/monitoring")
    public MonitoringDetail detail(DemoUser user, @PathVariable String patientId, @PathVariable String encounterId) {
        return monitoring.detail(user, patientId, encounterId);
    }

    @GetMapping("/patients/{patientId}/encounters/{encounterId}/monitoring/observations")
    public ObservationSeries observations(DemoUser user, @PathVariable String patientId, @PathVariable String encounterId,
                                          @RequestParam(defaultValue = "24h") String range) {
        return monitoring.observations(user, patientId, encounterId, range);
    }

    @GetMapping("/patients/{patientId}/encounters/{encounterId}/monitoring/events")
    public List<TimelineItem> events(DemoUser user, @PathVariable String patientId, @PathVariable String encounterId) {
        return monitoring.timeline(user, patientId, encounterId);
    }

    @GetMapping("/patients/{patientId}/encounters/{encounterId}/monitoring/alerts")
    public List<Alert> alerts(DemoUser user, @PathVariable String patientId, @PathVariable String encounterId) {
        return monitoring.alerts(user, patientId, encounterId);
    }

    /** Nursing work queue: monitored stays and monitoring alerts in the user's scope. */
    @GetMapping("/monitoring/alerts")
    public NursingQueue queue(DemoUser user) {
        return monitoring.queue(user);
    }

    @PatchMapping("/monitoring/alerts/{id}/status")
    public Alert updateAlert(DemoUser user, @PathVariable String id, @RequestBody AlertStatusRequest req) {
        return monitoring.updateAlertStatus(user, id, req);
    }

    @PostMapping("/monitoring/interventions/repositioning")
    public MonitoringDetail repositioning(DemoUser user, @RequestBody RepositioningRequest req) {
        return monitoring.documentRepositioning(user, req);
    }

    @PostMapping("/monitoring/simulator/events")
    public MonitoringDetail simulate(DemoUser user, @RequestBody SimulatorRequest req) {
        return monitoring.simulate(user, req);
    }

    public record AdvanceRequest(String patientId, String encounterId, Integer minutes) {}

    @PostMapping("/monitoring/simulator/advance-time")
    public MonitoringDetail advance(DemoUser user, @RequestBody AdvanceRequest req) {
        return monitoring.simulate(user, new SimulatorRequest(req.patientId(), req.encounterId(), "ADVANCE_TIME", req.minutes()));
    }

    public record ResetRequest(String patientId, String encounterId) {}

    @PostMapping("/monitoring/simulator/reset")
    public MonitoringDetail reset(DemoUser user, @RequestBody ResetRequest req) {
        return monitoring.simulate(user, new SimulatorRequest(req.patientId(), req.encounterId(), "RESET", null));
    }
}
