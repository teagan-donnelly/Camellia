package com.camellia.ingestion;

import com.camellia.model.*;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.camellia.ingestion.Json.*;

/** Normalizes FHIR R4-like bundles (Encounter, Observation, Condition, MedicationStatement, DocumentReference, DiagnosticReport, Appointment). */
public class FhirLikeNormalizer {

    public static final String FORMAT = "FHIR_R4_JSON";

    public NormalizedBatch normalize(String bundleJson, String orgId, SourceContext ctx) {
        NormalizedBatch out = new NormalizedBatch();
        JsonNode bundle = parse(bundleJson);
        Map<String, Encounter> encountersBySourceId = new HashMap<>();

        // Encounters first so notes, labs and imaging can link to them.
        for (JsonNode entry : bundle.path("entry")) {
            JsonNode r = entry.path("resource");
            if ("Encounter".equals(text(r.path("resourceType")))) {
                Encounter e = encounter(r, orgId, ctx, out);
                if (e != null) encountersBySourceId.put(text(r.path("id")), e);
            }
        }
        for (JsonNode entry : bundle.path("entry")) {
            JsonNode r = entry.path("resource");
            String type = text(r.path("resourceType"));
            if (type == null || type.equals("Encounter")) continue;
            String patientId = patient(r, orgId, ctx, out);
            if (patientId == null) continue;
            switch (type) {
                case "Observation" -> observation(r, orgId, patientId, ctx, out, encountersBySourceId);
                case "Condition" -> condition(r, orgId, patientId, ctx, out, encountersBySourceId);
                case "MedicationStatement" -> medication(r, orgId, patientId, ctx, out);
                case "DocumentReference" -> document(r, orgId, patientId, ctx, out, encountersBySourceId);
                case "DiagnosticReport" -> diagnosticReport(r, orgId, patientId, ctx, out, encountersBySourceId);
                case "Appointment" -> appointment(r, orgId, patientId, ctx, out);
                default -> out.warnings.add("Skipped unsupported resource type " + type);
            }
        }
        return out;
    }

    private String patient(JsonNode r, String orgId, SourceContext ctx, NormalizedBatch out) {
        String ref = textAt(r, "subject.reference");
        String mrn = ref == null ? null : ref.replaceFirst("^Patient/", "");
        String pid = ctx.patientFor(orgId, mrn);
        if (pid == null) out.warnings.add("Unmatched patient identity " + orgId + "/" + mrn + " — resource skipped");
        return pid;
    }

    private Encounter encounter(JsonNode r, String orgId, SourceContext ctx, NormalizedBatch out) {
        String pid = patient(r, orgId, ctx, out);
        if (pid == null) return null;
        String srcId = text(r.path("id"));
        String encId = SourceContext.id("ENC", orgId, srcId);
        String recId = SourceContext.id("REC", orgId, srcId);
        String type = textAt(r, "type.0.text");
        String specialty = textAt(r, "serviceType.text");
        String provider = textAt(r, "participant.0.individual.display");
        String reason = textAt(r, "reasonCode.0.text");
        String dept = text(r.path("department"));
        String cls = textAt(r, "class.code");
        LocalDateTime start = ctx.dateTime(textAt(r, "period.start"));
        String endToken = textAt(r, "period.end");
        LocalDateTime end = endToken == null ? null : ctx.dateTime(endToken);
        String symptom = ClinicalTagger.symptomCategory(reason);
        boolean fracture = ClinicalTagger.fractureRelated(reason);

        Encounter e = new Encounter(encId, recId, pid, orgId, encounterClass(cls, type), dept, specialty, provider, start,
                reason, symptom, fracture, end);
        out.encounters.add(e);
        Set<String> tags = new TreeSet<>();
        if (fracture) tags.add("FRACTURE");
        if ("EMER".equals(cls)) tags.add("EMERGENCY");
        if (symptom != null) tags.add("SYMPTOM_" + symptom);
        String content = "%s at %s (%s). Reason: %s. Provider: %s.".formatted(
                type, dept == null ? "unspecified department" : dept, specialty, reason, provider);
        out.records.add(ctx.record(recId, pid, orgId, encId, RecordType.ENCOUNTER, type + " — " + reason, start, provider,
                specialty, content, FORMAT, srcId, write(r), tags));
        return e;
    }

    static String encounterClass(String cls, String type) {
        if ("EMER".equals(cls)) return "Emergency";
        if ("IMP".equals(cls)) return "Inpatient";
        if (type != null && type.toLowerCase().contains("urgent")) return "Urgent care";
        return "Outpatient";
    }

    private void observation(JsonNode r, String orgId, String pid, SourceContext ctx, NormalizedBatch out,
                             Map<String, Encounter> encs) {
        String srcId = text(r.path("id"));
        String code = textAt(r, "code.coding.0.code");
        String name = textAt(r, "code.coding.0.display");
        Double value = number(at(r, "valueQuantity.value"));
        String unit = textAt(r, "valueQuantity.unit");
        Double low = number(at(r, "referenceRange.0.low.value"));
        Double high = number(at(r, "referenceRange.0.high.value"));
        String refText = textAt(r, "referenceRange.0.text");
        if (refText == null && (low != null || high != null)) refText = fmt(low) + "-" + fmt(high);
        String flag = textAt(r, "interpretation.0.coding.0.code");
        LocalDateTime when = ctx.dateTime(text(r.path("effectiveDateTime")));
        String performer = textAt(r, "performer.0.display");
        Encounter enc = linked(r, encs);
        String recId = SourceContext.id("REC", orgId, srcId);
        LabResult lab = new LabResult(SourceContext.id("LAB", orgId, srcId), recId, pid, orgId, code, name, value, null,
                unit, low, high, refText, flag, when);
        out.labs.add(lab);
        out.records.add(ctx.record(recId, pid, orgId, enc == null ? null : enc.encounterId(), RecordType.LAB_RESULT,
                name, when, performer, "Laboratory", LabText.describe(lab), FORMAT, srcId, write(r), LabText.tags(lab)));
    }

    private void condition(JsonNode r, String orgId, String pid, SourceContext ctx, NormalizedBatch out,
                           Map<String, Encounter> encs) {
        String srcId = text(r.path("id"));
        String code = textAt(r, "code.coding.0.code");
        String system = textAt(r, "code.coding.0.system");
        String display = textAt(r, "code.coding.0.display");
        String status = textAt(r, "clinicalStatus.coding.0.code");
        var onset = ctx.date(text(r.path("onsetDateTime")));
        String recorder = textAt(r, "recorder.display");
        String specialty = text(r.path("specialty"));
        String recId = SourceContext.id("REC", orgId, srcId);
        // A diagnosis documented during a specific stay keeps that link (FHIR Condition.encounter).
        Encounter enc = linked(r, encs);
        out.conditions.add(new Condition(SourceContext.id("CND", orgId, srcId), recId, pid, orgId, code, system, display,
                status, onset, recorder));
        Set<String> tags = new TreeSet<>();
        if (ClinicalTagger.fractureRelated(display)) tags.add("FRACTURE");
        String content = "Condition: %s (%s %s). Clinical status: %s. Onset: %s. Recorded by %s.".formatted(
                display, system, code, status, onset, recorder);
        out.records.add(ctx.record(recId, pid, orgId, enc == null ? null : enc.encounterId(), RecordType.CONDITION, display,
                onset == null ? null : onset.atStartOfDay(), recorder, specialty, content, FORMAT, srcId, write(r), tags));
    }

    private void medication(JsonNode r, String orgId, String pid, SourceContext ctx, NormalizedBatch out) {
        String srcId = text(r.path("id"));
        String name = textAt(r, "medicationCodeableConcept.text");
        String dose = textAt(r, "dosage.0.text");
        String route = textAt(r, "dosage.0.route.text");
        String status = text(r.path("status"));
        var start = ctx.date(textAt(r, "effectivePeriod.start"));
        var end = ctx.date(textAt(r, "effectivePeriod.end"));
        String prescriber = textAt(r, "informationSource.display");
        String recId = SourceContext.id("REC", orgId, srcId);
        out.medications.add(new Medication(SourceContext.id("MED", orgId, srcId), recId, pid, orgId, name, dose, route,
                null, status, start, end, prescriber));
        String content = "Medication: %s. Dosage: %s (%s). Status: %s. Start: %s%s. Source: %s.".formatted(
                name, dose, route, status, start, end == null ? "" : ", end: " + end, prescriber);
        out.records.add(ctx.record(recId, pid, orgId, null, RecordType.MEDICATION, name,
                start == null ? null : start.atStartOfDay(), prescriber, null, content, FORMAT, srcId, write(r), null));
    }

    private void document(JsonNode r, String orgId, String pid, SourceContext ctx, NormalizedBatch out,
                          Map<String, Encounter> encs) {
        String srcId = text(r.path("id"));
        String title = textAt(r, "type.text");
        String author = textAt(r, "author.0.display");
        String specialty = textAt(r, "context.practiceSetting.text");
        String body = textAt(r, "content.0.attachment.text");
        LocalDateTime when = ctx.dateTime(text(r.path("date")));
        String encRef = textAt(r, "context.encounter.0.reference");
        Encounter enc = encRef == null ? null : encs.get(encRef.replaceFirst("^Encounter/", ""));
        Set<String> tags = new TreeSet<>();
        if (enc != null && enc.fractureRelated()) tags.add("FRACTURE");
        out.records.add(ctx.record(SourceContext.id("REC", orgId, srcId), pid, orgId, enc == null ? null : enc.encounterId(),
                RecordType.NOTE, title, when, author, specialty, body, FORMAT, srcId, write(r), tags));
    }

    private void diagnosticReport(JsonNode r, String orgId, String pid, SourceContext ctx, NormalizedBatch out,
                                  Map<String, Encounter> encs) {
        String srcId = text(r.path("id"));
        String title = textAt(r, "code.text");
        String performer = textAt(r, "performer.0.display");
        String conclusion = text(r.path("conclusion"));
        LocalDateTime when = ctx.dateTime(text(r.path("effectiveDateTime")));
        Encounter enc = linked(r, encs);
        out.records.add(ctx.record(SourceContext.id("REC", orgId, srcId), pid, orgId, enc == null ? null : enc.encounterId(),
                RecordType.IMAGING, title, when, performer, text(r.path("specialty")), "Impression: " + conclusion,
                FORMAT, srcId, write(r), null));
    }

    private void appointment(JsonNode r, String orgId, String pid, SourceContext ctx, NormalizedBatch out) {
        String srcId = text(r.path("id"));
        String type = textAt(r, "appointmentType.text");
        String category = textAt(r, "serviceCategory.text");
        String dept = text(r.path("department"));
        String provider = textAt(r, "participant.0.actor.display");
        String desc = text(r.path("description"));
        String status = text(r.path("status"));
        LocalDateTime start = ctx.dateTime(text(r.path("start")));
        String recId = SourceContext.id("REC", orgId, srcId);
        boolean ob = ClinicalTagger.concepts(type + " " + category + " " + dept).contains("OBSTETRIC");
        out.appointments.add(new Appointment(SourceContext.id("APT", orgId, srcId), recId, pid, orgId, type, dept, start,
                status, provider, desc, ob));
        String content = "Scheduled %s on %s at %s (%s). Status: %s. Provider: %s. %s".formatted(
                type, start, dept, category, status, provider, desc == null ? "" : desc);
        out.records.add(ctx.record(recId, pid, orgId, null, RecordType.APPOINTMENT, type, start, provider, category,
                content, FORMAT, srcId, write(r), null));
    }

    private static Encounter linked(JsonNode r, Map<String, Encounter> encs) {
        String ref = textAt(r, "encounter.reference");
        return ref == null ? null : encs.get(ref.replaceFirst("^Encounter/", ""));
    }

    static String fmt(Double d) {
        if (d == null) return "";
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }
}
