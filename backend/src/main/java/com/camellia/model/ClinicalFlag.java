package com.camellia.model;

import java.time.LocalDateTime;

public record ClinicalFlag(String flagId, String patientId, String ruleId, String ruleVersion, String title,
                           String detectionReason, String reviewPriority, String status, String statusReason,
                           String dedupKey, String evidenceFingerprint, LocalDateTime createdAt, LocalDateTime updatedAt,
                           String updatedBy, LocalDateTime lastEvaluatedAt, LocalDateTime evidenceUpdatedAt,
                           String aiExplanationJson, String aiExplanationModel, LocalDateTime aiExplanationAt) {}
