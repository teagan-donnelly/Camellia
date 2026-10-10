package com.camellia.ingestion;

import com.camellia.model.*;

import java.util.ArrayList;
import java.util.List;

/** Output of the normalization layer: internal entities ready to persist. */
public class NormalizedBatch {
    public final List<ClinicalRecord> records = new ArrayList<>();
    public final List<Encounter> encounters = new ArrayList<>();
    public final List<LabResult> labs = new ArrayList<>();
    public final List<Condition> conditions = new ArrayList<>();
    public final List<Medication> medications = new ArrayList<>();
    public final List<Appointment> appointments = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public void addAll(NormalizedBatch other) {
        records.addAll(other.records);
        encounters.addAll(other.encounters);
        labs.addAll(other.labs);
        conditions.addAll(other.conditions);
        medications.addAll(other.medications);
        appointments.addAll(other.appointments);
        warnings.addAll(other.warnings);
    }

    public int size() {
        return records.size();
    }
}
