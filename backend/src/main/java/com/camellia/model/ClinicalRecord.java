package com.camellia.model;

import java.time.LocalDateTime;

/**
 * Universal, searchable representation of every source item. RECORD_ID is the citation namespace used
 * by flags, alerts and AI answers.
 */
public record ClinicalRecord(String recordId, String patientId, String orgId, String encounterId, RecordType recordType,
                             String title, LocalDateTime recordDate, String author, String specialty, String contentText,
                             String sourceFormat, String sourceRecordId, String rawContent, String categoryTags,
                             LocalDateTime ingestedAt) {

    public boolean hasTag(String tag) {
        return categoryTags != null && categoryTags.contains("|" + tag + "|");
    }
}
