package com.camellia.model;

import java.time.LocalDateTime;

/** {@code endTime} is the discharge/end time (null when ongoing or not recorded by the source). */
public record Encounter(String encounterId, String recordId, String patientId, String orgId, String encounterType,
                        String department, String specialty, String providerName, LocalDateTime startTime,
                        String reason, String symptomCategory, boolean fractureRelated, LocalDateTime endTime) {

    public Encounter(String encounterId, String recordId, String patientId, String orgId, String encounterType,
                     String department, String specialty, String providerName, LocalDateTime startTime,
                     String reason, String symptomCategory, boolean fractureRelated) {
        this(encounterId, recordId, patientId, orgId, encounterType, department, specialty, providerName, startTime, reason,
                symptomCategory, fractureRelated, null);
    }

    public boolean inpatient() {
        return "Inpatient".equalsIgnoreCase(encounterType);
    }

    /** Many source systems never send a discharge time; such a stay only counts as current for this long. */
    public static final int MAX_OPEN_STAY_DAYS = 30;

    /**
     * True while the stay is in progress at {@code at}: started, and either not yet ended or — when the source recorded
     * no end time — started within {@link #MAX_OPEN_STAY_DAYS} days. Without that bound an old admission with a missing
     * discharge time would be reported as a current hospitalization.
     */
    public boolean ongoingAt(LocalDateTime at) {
        if (startTime == null || startTime.isAfter(at)) return false;
        return endTime != null ? endTime.isAfter(at) : !startTime.isBefore(at.minusDays(MAX_OPEN_STAY_DAYS));
    }
}
