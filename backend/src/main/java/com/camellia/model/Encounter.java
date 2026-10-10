package com.camellia.model;

import java.time.LocalDateTime;

public record Encounter(String encounterId, String recordId, String patientId, String orgId, String encounterType,
                        String department, String specialty, String providerName, LocalDateTime startTime,
                        String reason, String symptomCategory, boolean fractureRelated) {}
