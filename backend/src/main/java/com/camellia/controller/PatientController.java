package com.camellia.controller;

import com.camellia.ai.AiDtos.ExtractionResult;
import com.camellia.ai.ClinicalAiService;
import com.camellia.config.DemoUsers;
import com.camellia.dto.Dtos.*;
import com.camellia.service.ClinicalViewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class PatientController {

    private final ClinicalViewService views;
    private final ClinicalAiService ai;

    public PatientController(ClinicalViewService views, ClinicalAiService ai) {
        this.views = views;
        this.ai = ai;
    }

    @GetMapping("/patients")
    public List<PatientSummaryDto> patients(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) Boolean obstetric) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return views.patientDirectory().stream()
                .filter(p -> obstetric == null || p.obstetric() == obstetric)
                .filter(p -> needle.isEmpty() || (p.name() + " " + p.displayId() + " " + p.patientId() + " "
                        + String.join(" ", p.keyConditions()) + " " + String.join(" ", p.organizations()))
                        .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    @GetMapping("/patients/{id}")
    public PatientProfileDto patient(@PathVariable String id) {
        return views.patientProfile(id);
    }

    @GetMapping("/patients/{id}/records")
    public List<RecordDto> records(@PathVariable String id, @RequestParam(required = false) String type) {
        return views.patientProfile(id).timeline().stream()
                .filter(r -> type == null || type.isBlank() || r.recordType().equalsIgnoreCase(type)).toList();
    }

    @GetMapping("/patients/{id}/appointments")
    public List<AppointmentDto> appointments(@PathVariable String id) {
        return views.patientProfile(id).appointments();
    }

    /** Gemini Capability 1: extract structured, quote-verified observations from this patient's notes. */
    @PostMapping("/patients/{id}/ai-extraction")
    public ExtractionResult extract(@PathVariable String id, @RequestHeader(value = DemoUsers.HEADER, required = false) String user) {
        return ai.extract(id, CurrentUser.of(user).name());
    }

    @GetMapping("/records/{recordId}")
    public RecordDetailDto record(@PathVariable String recordId) {
        return views.recordDetail(recordId);
    }
}
