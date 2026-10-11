package com.camellia.controller;

import com.camellia.ai.AiDtos.ExtractionResult;
import com.camellia.ai.ClinicalAiService;
import com.camellia.dto.Dtos.*;
import com.camellia.model.ClinicalRecord;
import com.camellia.repository.ClinicalDataRepository;
import com.camellia.security.AuthorizationService;
import com.camellia.security.DemoUser;
import com.camellia.security.Permission;
import com.camellia.service.ClinicalViewService;
import com.camellia.traits.TraitService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/** Patient directory, profile and source records. Every endpoint is scoped to the active demo user. */
@RestController
@RequestMapping("/api")
public class PatientController {

    private final ClinicalViewService views;
    private final ClinicalAiService ai;
    private final AuthorizationService auth;
    private final ClinicalDataRepository clinical;
    private final com.camellia.monitoring.MonitoringService monitoring;
    private final TraitService traits;

    public PatientController(ClinicalViewService views, ClinicalAiService ai, AuthorizationService auth, ClinicalDataRepository clinical,
                             com.camellia.monitoring.MonitoringService monitoring, TraitService traits) {
        this.monitoring = monitoring;
        this.traits = traits;
        this.views = views;
        this.ai = ai;
        this.auth = auth;
        this.clinical = clinical;
    }

    @GetMapping("/patients")
    public List<PatientSummaryDto> patients(DemoUser user, @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Boolean obstetric) {
        auth.requirePermission(user, Permission.VIEW_PATIENTS);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return views.patientDirectory(auth.scope(user)).stream()
                .filter(p -> obstetric == null || p.obstetric() == obstetric)
                .filter(p -> needle.isEmpty() || (p.name() + " " + p.displayId() + " " + p.patientId() + " "
                        + String.join(" ", p.keyConditions()) + " " + String.join(" ", p.organizations()))
                        .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    @GetMapping("/patients/{id}")
    public PatientProfileDto patient(DemoUser user, @PathVariable String id) {
        auth.requirePatient(user, id);
        return views.patientProfile(id, auth.scope(user));
    }

    @GetMapping("/patients/{id}/records")
    public List<RecordDto> records(DemoUser user, @PathVariable String id, @RequestParam(required = false) String type) {
        auth.requirePatient(user, id);
        return views.patientProfile(id, auth.scope(user)).timeline().stream()
                .filter(r -> type == null || type.isBlank() || r.recordType().equalsIgnoreCase(type)).toList();
    }

    /** Structured traits derived from coded diagnoses (clinical roles only). */
    @GetMapping("/patients/{id}/traits")
    public List<TraitService.TraitView> traits(DemoUser user, @PathVariable String id) {
        return traits.forPatient(user, id);
    }

    @GetMapping("/patients/{id}/appointments")
    public List<AppointmentDto> appointments(DemoUser user, @PathVariable String id) {
        auth.requirePatient(user, id);
        return views.patientProfile(id, auth.scope(user)).appointments();
    }

    /** Gemini Capability 1: extract structured, quote-verified observations from this patient's notes. */
    @PostMapping("/patients/{id}/ai-extraction")
    public ExtractionResult extract(DemoUser user, @PathVariable String id) {
        return ai.extract(id, user);
    }

    /** Original source record. Authorized through the record's patient; unknown and restricted ids get the same 403. */
    @GetMapping("/records/{recordId}")
    public RecordDetailDto record(DemoUser user, @PathVariable String recordId) {
        // REC-MON-* citations point at evidence built from simulated wearable data (never stored as clinical records).
        ClinicalRecord r = recordId.startsWith("REC-MON-")
                ? monitoring.evidenceRecord(user, recordId).orElseThrow(() -> AuthorizationService.denied(null))
                : clinical.findRecord(recordId).orElseThrow(() -> AuthorizationService.denied(null));
        auth.requirePatient(user, r.patientId());
        return views.recordDetail(r, auth.scope(user));
    }
}
