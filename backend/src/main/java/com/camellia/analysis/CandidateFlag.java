package com.camellia.analysis;

import java.util.List;

/** Output of a screening rule before de-duplication and persistence. */
public record CandidateFlag(String ruleId, String ruleVersion, String patientId, String title, String detectionReason,
                            String reviewPriority, String dedupKey, List<EvidenceItem> evidence, AlertSpec alert) {}
