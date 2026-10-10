package com.camellia.ingestion;

import com.camellia.model.ClinicalRecord;
import com.camellia.model.RecordType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared state for normalizing one source: the demo date anchor, the master patient index
 * (org + source MRN -> internal patient id) and helpers that build consistently identified records.
 */
public class SourceContext {

    private final LocalDate anchor;
    private final Map<String, String> patientIndex;
    private final LocalDateTime ingestedAt;

    public SourceContext(LocalDate anchor, Map<String, String> patientIndex, LocalDateTime ingestedAt) {
        this.anchor = anchor;
        this.patientIndex = patientIndex;
        this.ingestedAt = ingestedAt;
    }

    public static String indexKey(String orgId, String mrn) {
        return orgId + "|" + mrn;
    }

    /** Internal patient id for a source MRN, or null when the identity cannot be matched. */
    public String patientFor(String orgId, String mrn) {
        return mrn == null ? null : patientIndex.get(indexKey(orgId, mrn.trim()));
    }

    public LocalDateTime dateTime(String token) {
        return RelativeDates.dateTime(token, anchor);
    }

    public LocalDate date(String token) {
        return RelativeDates.date(token, anchor);
    }

    public LocalDate anchor() {
        return anchor;
    }

    public static String id(String prefix, String orgId, String sourceId) {
        return prefix + "-" + orgId + "-" + sourceId.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
    }

    public ClinicalRecord record(String recordId, String patientId, String orgId, String encounterId, RecordType type,
                                 String title, LocalDateTime date, String author, String specialty, String content,
                                 String sourceFormat, String sourceId, String raw, Set<String> extraTags) {
        Set<String> tags = new TreeSet<>(ClinicalTagger.concepts(title + "\n" + (specialty == null ? "" : specialty) + "\n" + content));
        if (extraTags != null) tags.addAll(extraTags);
        if (type == RecordType.MEDICATION) tags.add("MEDICATION");
        return new ClinicalRecord(recordId, patientId, orgId, encounterId, type, title, date, author, specialty, content,
                sourceFormat, sourceId, raw, ClinicalTagger.encode(tags), ingestedAt);
    }
}
