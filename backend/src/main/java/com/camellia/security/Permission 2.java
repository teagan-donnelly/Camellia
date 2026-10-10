package com.camellia.security;

/** Application functions a simulated role may use. Enforced server-side by {@link AuthorizationService}. */
public enum Permission {
    VIEW_PATIENTS,
    CLINICAL_SEARCH,
    CLINICAL_ALERTS,
    /** Change review status of general clinical flags. */
    REVIEW_FLAGS,
    GEMINI_ASSISTANT,
    /** View the specialized Anesthesia OB Alerts workflow (queue, workspace, coordination board, OB anesthesia flags). */
    ANESTHESIA_OB_ALERTS,
    /** Acknowledge alerts and change coordination workflow state. */
    UPDATE_COORDINATION,
    ADD_COORDINATION_NOTES
}
