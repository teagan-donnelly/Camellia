package com.camellia.analysis;

/** A source record cited by a candidate flag. Roles: TRIGGER, SUPPORTING, UPCOMING_ENCOUNTER. */
public record EvidenceItem(String recordId, String role, String description) {
    public static final String TRIGGER = "TRIGGER";
    public static final String SUPPORTING = "SUPPORTING";
    public static final String UPCOMING_ENCOUNTER = "UPCOMING_ENCOUNTER";
}
