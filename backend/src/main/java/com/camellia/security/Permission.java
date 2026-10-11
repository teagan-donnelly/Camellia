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
    ADD_COORDINATION_NOTES,
    /** Preview and import synthetic patient record files. */
    IMPORT_RECORDS,
    /** Reset the demo dataset (destructive for workflow state; imported data and uploads are kept). */
    MANAGE_DEMO_DATA,
    /** Upload PDF / Word documents to a patient (viewing them only needs access to the patient). */
    UPLOAD_DOCUMENTS,
    /** Generate and play Camellia Briefings for one's own simulated shifts. */
    CLINICAL_BRIEFING,
    /** Read encounter-scoped wearable monitoring data (within the user's patient scope). */
    VIEW_MONITORING,
    /** Nursing monitoring work queue and monitoring alert status changes. */
    NURSING_MONITORING,
    /** Document nursing interventions such as confirmed repositioning. */
    DOCUMENT_NURSING_INTERVENTIONS,
    /** Use the clearly labeled wristband simulator controls (synthetic data only). */
    SIMULATE_DEVICES
}
