package com.camellia.model;

import java.time.LocalDate;

public record Medication(String medicationId, String recordId, String patientId, String orgId, String name, String dose,
                         String route, String frequency, String status, LocalDate startDate, LocalDate endDate,
                         String prescriber) {

    public boolean isActive() {
        return status == null || status.equalsIgnoreCase("active");
    }
}
