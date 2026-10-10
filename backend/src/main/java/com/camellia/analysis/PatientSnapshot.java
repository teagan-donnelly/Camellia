package com.camellia.analysis;

import com.camellia.model.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Everything a rule may look at for one patient, evaluated as of {@code now}. */
public record PatientSnapshot(Patient patient, List<ClinicalRecord> records, List<Encounter> encounters, List<LabResult> labs,
                              List<Condition> conditions, List<Medication> medications, List<Appointment> appointments,
                              LocalDateTime now) {

    public Map<String, ClinicalRecord> recordsById() {
        return records.stream().collect(Collectors.toMap(ClinicalRecord::recordId, Function.identity(), (a, b) -> a));
    }

    public String patientId() {
        return patient.patientId();
    }
}
