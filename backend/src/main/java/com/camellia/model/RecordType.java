package com.camellia.model;

/** MONITORING is never stored as a clinical record: it labels evidence built on the fly from simulated wearable data. */
public enum RecordType {
    NOTE, LAB_RESULT, CONDITION, MEDICATION, ENCOUNTER, APPOINTMENT, IMAGING, MONITORING
}
