package com.camellia.model;

import java.time.LocalDateTime;

public record LabResult(String labId, String recordId, String patientId, String orgId, String testCode, String testName,
                        Double valueNum, String valueText, String unit, Double refLow, Double refHigh, String refText,
                        String abnormalFlag, LocalDateTime collectedAt) {

    /** Below the reporting laboratory's own reference range (documented fact, not a clinical threshold). */
    public boolean isBelowReference() {
        if (valueNum != null && refLow != null) return valueNum < refLow;
        return abnormalFlag != null && abnormalFlag.startsWith("L");
    }

    public boolean isAboveReference() {
        if (valueNum != null && refHigh != null) return valueNum > refHigh;
        return abnormalFlag != null && abnormalFlag.startsWith("H");
    }

    public boolean isOutOfReference() {
        if (valueNum != null && (refLow != null || refHigh != null)) return isBelowReference() || isAboveReference();
        return abnormalFlag != null && !abnormalFlag.isBlank() && !"N".equalsIgnoreCase(abnormalFlag);
    }
}
