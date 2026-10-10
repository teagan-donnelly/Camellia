package com.camellia.ingestion;

import com.camellia.model.Condition;
import com.camellia.model.Encounter;
import com.camellia.model.RecordType;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Normalizes dictation-style plain-text exports: "KEY: value" header lines, a "---" separator, a free-text body,
 * and "=== END ===" between notes.
 */
public class PlainTextNoteNormalizer {

    public static final String FORMAT = "PLAINTEXT_NOTE";

    public NormalizedBatch normalize(String text, String orgId, SourceContext ctx) {
        NormalizedBatch out = new NormalizedBatch();
        for (String block : text.split("=== END ===")) {
            if (block.isBlank()) continue;
            parseBlock(block.trim() + "\n=== END ===", orgId, ctx, out);
        }
        return out;
    }

    private void parseBlock(String block, String orgId, SourceContext ctx, NormalizedBatch out) {
        Map<String, String> header = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        for (String line : block.split("\n")) {
            if (line.startsWith("===")) continue;
            if (!inBody && line.trim().equals("---")) {
                inBody = true;
                continue;
            }
            if (inBody) {
                body.append(line).append('\n');
            } else {
                int idx = line.indexOf(':');
                if (idx > 0) header.put(line.substring(0, idx).trim().toUpperCase(), line.substring(idx + 1).trim());
            }
        }
        String noteId = header.get("NOTE-ID");
        String mrn = header.get("MRN");
        String pid = ctx.patientFor(orgId, mrn);
        if (noteId == null || pid == null) {
            out.warnings.add("Unmatched or incomplete plain-text note " + noteId + " (" + orgId + "/" + mrn + ") — skipped");
            return;
        }
        LocalDateTime when = ctx.dateTime(header.get("DATE"));
        String provider = header.get("PROVIDER");
        String specialty = header.getOrDefault("SPECIALTY", "Urgent Care");
        String visitType = header.getOrDefault("VISIT-TYPE", "Visit");
        String complaint = header.get("CHIEF-COMPLAINT");
        String diagnosis = header.get("DIAGNOSIS");
        String dxCode = null, dxText = null;
        if (diagnosis != null && diagnosis.contains("|")) {
            dxCode = diagnosis.substring(0, diagnosis.indexOf('|')).trim();
            dxText = diagnosis.substring(diagnosis.indexOf('|') + 1).trim();
        }
        String reason = complaint + (dxText == null ? "" : "; " + dxText);
        String symptom = ClinicalTagger.symptomCategory(complaint);
        boolean fracture = ClinicalTagger.fractureRelated(reason);
        String encId = SourceContext.id("ENC", orgId, noteId);
        String encRecId = SourceContext.id("REC", orgId, noteId + "-VISIT");

        out.encounters.add(new Encounter(encId, encRecId, pid, orgId, "Urgent care", specialty, specialty, provider, when,
                reason, symptom, fracture));
        Set<String> tags = new TreeSet<>();
        if (fracture) tags.add("FRACTURE");
        if (symptom != null) tags.add("SYMPTOM_" + symptom);
        out.records.add(ctx.record(encRecId, pid, orgId, encId, RecordType.ENCOUNTER, visitType + " — " + complaint, when,
                provider, specialty, "%s. Chief complaint: %s. Diagnosis: %s. Provider: %s.".formatted(
                        visitType, complaint, diagnosis, provider), FORMAT, noteId, block, tags));
        out.records.add(ctx.record(SourceContext.id("REC", orgId, noteId), pid, orgId, encId, RecordType.NOTE,
                specialty + " visit note — " + complaint, when, provider, specialty, body.toString().trim(), FORMAT, noteId,
                block, tags));
        if (dxCode != null) {
            String src = noteId + "-DX";
            String recId = SourceContext.id("REC", orgId, src);
            out.conditions.add(new Condition(SourceContext.id("CND", orgId, src), recId, pid, orgId, dxCode, "ICD-10-CM",
                    dxText, fracture ? "resolved" : "active", when.toLocalDate(), provider));
            out.records.add(ctx.record(recId, pid, orgId, encId, RecordType.CONDITION, dxText, when, provider, specialty,
                    "Diagnosis: %s (ICD-10-CM %s). Documented by %s.".formatted(dxText, dxCode, provider), FORMAT, src,
                    diagnosis, fracture ? Set.of("FRACTURE") : null));
        }
    }
}
