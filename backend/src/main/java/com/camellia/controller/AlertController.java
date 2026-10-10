package com.camellia.controller;

import com.camellia.config.DemoUsers;
import com.camellia.coordination.CoordinationService;
import com.camellia.coordination.CoordinationService.AlertUpdate;
import com.camellia.dto.Dtos.AlertDetailDto;
import com.camellia.dto.Dtos.AlertSummaryDto;
import com.camellia.model.CoordinationNote;
import com.camellia.service.ClinicalViewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coordination-alerts")
public class AlertController {

    private final ClinicalViewService views;
    private final CoordinationService coordination;

    public AlertController(ClinicalViewService views, CoordinationService coordination) {
        this.views = views;
        this.coordination = coordination;
    }

    @GetMapping
    public List<AlertSummaryDto> alerts(@RequestParam(required = false) String status, @RequestParam(required = false) String team) {
        return views.alertList(status, team);
    }

    @GetMapping("/{id}")
    public AlertDetailDto alert(@PathVariable String id) {
        return views.alertDetail(id);
    }

    @PatchMapping("/{id}/status")
    public AlertDetailDto update(@PathVariable String id, @RequestBody AlertUpdate update,
                                 @RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        coordination.updateAlert(id, update, CurrentUser.of(user));
        return views.alertDetail(id);
    }

    public record NoteRequest(String noteType, String body) {}

    @PostMapping("/{id}/notes")
    public CoordinationNote addNote(@PathVariable String id, @RequestBody NoteRequest req,
                                    @RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        return coordination.addNote(id, req.noteType(), req.body(), CurrentUser.of(user));
    }
}
