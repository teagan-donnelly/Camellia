package com.camellia.security;

import java.util.EnumSet;
import java.util.Set;

import static com.camellia.security.Permission.*;

/**
 * Simulated professional roles. {@code networkScope=false} means the user may only see patients registered to one of
 * their practices; {@code true} means every synthetic patient in the prototype network (a prototype simplification —
 * production access would also depend on care relationships and organization policy).
 */
public enum Role {
    PHYSICIAN("Physician", false,
            EnumSet.of(VIEW_PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, REVIEW_FLAGS, GEMINI_ASSISTANT, CLINICAL_BRIEFING,
                    VIEW_MONITORING, UPLOAD_DOCUMENTS)),
    REGISTERED_NURSE("Registered Nurse", true,
            EnumSet.of(VIEW_PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, GEMINI_ASSISTANT, CLINICAL_BRIEFING, VIEW_MONITORING,
                    NURSING_MONITORING, DOCUMENT_NURSING_INTERVENTIONS, SIMULATE_DEVICES, UPLOAD_DOCUMENTS)),
    LD_NURSE("Labor & Delivery Nurse", true,
            EnumSet.of(VIEW_PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, GEMINI_ASSISTANT, ANESTHESIA_OB_ALERTS,
                    ADD_COORDINATION_NOTES, CLINICAL_BRIEFING, VIEW_MONITORING, NURSING_MONITORING,
                    DOCUMENT_NURSING_INTERVENTIONS, SIMULATE_DEVICES, UPLOAD_DOCUMENTS)),
    CRNA("Nurse Anesthetist (CRNA)", true,
            EnumSet.of(VIEW_PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, REVIEW_FLAGS, GEMINI_ASSISTANT, ANESTHESIA_OB_ALERTS,
                    UPDATE_COORDINATION, ADD_COORDINATION_NOTES, CLINICAL_BRIEFING, VIEW_MONITORING)),
    ANESTHESIOLOGIST("Anesthesiologist", true,
            EnumSet.of(VIEW_PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, REVIEW_FLAGS, GEMINI_ASSISTANT, ANESTHESIA_OB_ALERTS,
                    UPDATE_COORDINATION, ADD_COORDINATION_NOTES, CLINICAL_BRIEFING, VIEW_MONITORING)),
    /** Administrative demo role: brings synthetic records into Camellia. No clinical workflows or AI. */
    DATA_COORDINATOR("Clinical Data Coordinator", true,
            EnumSet.of(VIEW_PATIENTS, IMPORT_RECORDS, UPLOAD_DOCUMENTS));

    private final String label;
    private final boolean networkScope;
    private final Set<Permission> permissions;

    Role(String label, boolean networkScope, Set<Permission> permissions) {
        this.label = label;
        this.networkScope = networkScope;
        this.permissions = permissions;
    }

    public String label() { return label; }
    public boolean networkScope() { return networkScope; }
    public Set<Permission> permissions() { return permissions; }

    /** True for the five clinical roles (not the administrative data coordinator). */
    public boolean clinical() { return this != DATA_COORDINATOR; }
}
