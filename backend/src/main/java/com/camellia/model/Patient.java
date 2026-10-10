package com.camellia.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record Patient(String patientId, String displayId, String firstName, String lastName, LocalDate dateOfBirth,
                      String sex, boolean obstetric, LocalDate estimatedDueDate, String gestationalNote,
                      String primaryOrgId, String summary, String demoScenario, LocalDateTime createdAt) {

    public String fullName() {
        return firstName + " " + lastName;
    }
}
