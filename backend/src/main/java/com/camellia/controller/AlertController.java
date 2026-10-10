package com.camellia.controller;

import com.camellia.coordination.CoordinationService;
import com.camellia.coordination.CoordinationService.AlertUpdate;
import com.camellia.dto.Dtos.AlertDetailDto;
import com.camellia.dto.Dtos.AlertSummaryDto;
import com.camellia.model.CoordinationNote;
import com.camellia.repository.AlertRepository;
import com.camellia.security.AuthorizationService;
import com.camellia.security.DemoUser;
import com.camellia.service.ClinicalViewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Anesthesia OB Alerts (coordination alerts). Restricted to roles with the ANESTHESIA_OB_ALERTS permission. */
@RestController
@RequestMapping("/api/coordination-alerts")
public class AlertController {

    private final ClinicalViewService views;
    private final CoordinationService coordination;
    private final AuthorizationService auth;
    private final AlertRepository alerts;

    public AlertController(ClinicalViewService views, CoordinationService coordination, AuthorizationService auth, AlertRepository alerts) {
        this.views = views;
        this.coordination = coordination;
        this.auth = auth;
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertSummaryDto> alerts(DemoUser user, @RequestParam(required = false) String status, @RequestParam(required = false) String team) {
        auth.requireAnesthesiaAlerts(user);
        return views.alertList(status, team, auth.scope(user));
    }

    @GetMapping("/{id}")
    public AlertDetailDto alert(DemoUser user, @PathVariable String id) {
        auth.requireAlert(user, alerts.findById(id).orElse(null));
        return views.alertDetail(id, auth.scope(user));
    }

    @PatchMapping("/{id}/status")
    public AlertDetailDto update(DemoUser user, @PathVariable String id, @RequestBody AlertUpdate update) {
        coordination.updateAlert(id, update, user);
        return views.alertDetail(id, auth.scope(user));
    }

    public record NoteRequest(String noteType, String body) {}

    @PostMapping("/{id}/notes")
    public CoordinationNote addNote(DemoUser user, @PathVariable String id, @RequestBody NoteRequest req) {
        return coordination.addNote(id, req.noteType(), req.body(), user);
    }
}
