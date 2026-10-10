package com.camellia.ingestion;

import com.camellia.model.*;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static com.camellia.ingestion.Json.*;

/**
 * Normalizes the flat, practice-management style export used by the fictional Bellwether practice:
 * visits with embedded notes and diagnoses, a medication list and a schedule.
 */
public class LegacyPracticeJsonNormalizer {

    public static final String FORMAT = "LEGACY_JSON";

    public NormalizedBatch normalize(String exportJson, String orgId, SourceContext ctx) {
        NormalizedBatch out = new NormalizedBatch();
        JsonNode root = parse(exportJson);
        for (JsonNode p : root.path("patients")) {
            String mrn = text(p.path("mrn"));
            String pid = ctx.patientFor(orgId, mrn);
            if (pid == null) {
                out.warnings.add("Unmatched patient identity " + orgId + "/" + mrn + " — patient block skipped");
                continue;
            }
            Set<String> seenDiagnoses = new HashSet<>();
            for (JsonNode v : p.path("visits")) visit(v, orgId, pid, ctx, out, seenDiagnoses);
            for (JsonNode m : p.path("medications")) medication(m, orgId, pid, ctx, out);
            for (JsonNode s : p.path("scheduled")) scheduled(s, orgId, pid, ctx, out);
        }
        return out;
    }

    private void visit(JsonNode v, String orgId, String pid, SourceContext ctx, NormalizedBatch out, Set<String> seenDx) {
        String visitId = text(v.path("visitId"));
        LocalDateTime when = ctx.dateTime(text(v.path("visitDate")));
        String type = text(v.path("visitType"));
        String dept = text(v.path("department"));
        String provider = text(v.path("provider"));
        String specialty = text(v.path("specialty"));
        String complaint = text(v.path("chiefComplaint"));
        String note = text(v.path("noteText"));
        String encId = SourceContext.id("ENC", orgId, visitId);
        String encRecId = SourceContext.id("REC", orgId, visitId);
        String symptom = ClinicalTagger.symptomCategory(complaint);
        boolean fracture = ClinicalTagger.fractureRelated(complaint);

        out.encounters.add(new Encounter(encId, encRecId, pid, orgId, "Outpatient", dept, specialty, provider, when,
                complaint, symptom, fracture));
        Set<String> encTags = new TreeSet<>();
        if (symptom != null) encTags.add("SYMPTOM_" + symptom);
        if (fracture) encTags.add("FRACTURE");
        out.records.add(ctx.record(encRecId, pid, orgId, encId, RecordType.ENCOUNTER, type + " — " + complaint, when,
                provider, specialty, "%s (%s). Chief complaint: %s. Provider: %s.".formatted(type, dept, complaint, provider),
                FORMAT, visitId, write(v), encTags));

        if (note != null) {
            String noteSrc = visitId + "-NOTE";
            out.records.add(ctx.record(SourceContext.id("REC", orgId, noteSrc), pid, orgId, encId, RecordType.NOTE,
                    specialty + " note — " + type, when, provider, specialty, note, FORMAT, noteSrc, note, null));
        }

        for (JsonNode dx : v.path("diagnoses")) {
            String code = text(dx.path("icd10"));
            String desc = text(dx.path("description"));
            if (code == null || !seenDx.add(code)) continue;
            String src = visitId + "-DX-" + code;
            String recId = SourceContext.id("REC", orgId, src);
            out.conditions.add(new Condition(SourceContext.id("CND", orgId, src), recId, pid, orgId, code, "ICD-10-CM", desc,
                    text(dx.path("status")), when.toLocalDate(), provider));
            out.records.add(ctx.record(recId, pid, orgId, encId, RecordType.CONDITION, desc, when, provider, specialty,
                    "Diagnosis: %s (ICD-10-CM %s). Status: %s. Documented by %s during %s.".formatted(
                            desc, code, text(dx.path("status")), provider, type),
                    FORMAT, src, write(dx), null));
        }
    }

    private void medication(JsonNode m, String orgId, String pid, SourceContext ctx, NormalizedBatch out) {
        String rxId = text(m.path("rxId"));
        String drug = text(m.path("drug"));
        String sig = text(m.path("sig"));
        String route = text(m.path("route"));
        String status = text(m.path("status"));
        var start = ctx.date(text(m.path("startDate")));
        String prescriber = text(m.path("prescriber"));
        String recId = SourceContext.id("REC", orgId, rxId);
        out.medications.add(new Medication(SourceContext.id("MED", orgId, rxId), recId, pid, orgId, drug, sig, route, null,
                status, start, null, prescriber));
        out.records.add(ctx.record(recId, pid, orgId, null, RecordType.MEDICATION, drug,
                start == null ? null : start.atStartOfDay(), prescriber, null,
                "Medication: %s. Sig: %s (%s). Status: %s. Started %s. Prescriber: %s.".formatted(drug, sig, route, status, start, prescriber),
                FORMAT, rxId, write(m), null));
    }

    private void scheduled(JsonNode s, String orgId, String pid, SourceContext ctx, NormalizedBatch out) {
        String apptId = text(s.path("apptId"));
        LocalDateTime when = ctx.dateTime(text(s.path("when")));
        String type = text(s.path("apptType"));
        String location = text(s.path("location"));
        String provider = text(s.path("provider"));
        String comments = text(s.path("comments"));
        String recId = SourceContext.id("REC", orgId, apptId);
        boolean ob = ClinicalTagger.concepts(type + " " + location).contains("OBSTETRIC");
        out.appointments.add(new Appointment(SourceContext.id("APT", orgId, apptId), recId, pid, orgId, type, location, when,
                "booked", provider, comments, ob));
        out.records.add(ctx.record(recId, pid, orgId, null, RecordType.APPOINTMENT, type, when, provider, null,
                "Scheduled %s on %s at %s. Provider: %s. %s".formatted(type, when, location, provider, comments == null ? "" : comments),
                FORMAT, apptId, write(s), null));
    }
}
