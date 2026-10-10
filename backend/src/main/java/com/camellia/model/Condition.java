package com.camellia.model;

import java.time.LocalDate;

public record Condition(String conditionId, String recordId, String patientId, String orgId, String code,
                        String codeSystem, String display, String clinicalStatus, LocalDate onsetDate,
                        String recordedBy) {

    public boolean isActive() {
        return clinicalStatus == null || !clinicalStatus.equalsIgnoreCase("resolved");
    }
}
