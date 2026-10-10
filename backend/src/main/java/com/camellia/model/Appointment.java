package com.camellia.model;

import java.time.LocalDateTime;

public record Appointment(String appointmentId, String recordId, String patientId, String orgId, String appointmentType,
                          String department, LocalDateTime scheduledStart, String status, String providerName,
                          String description, boolean obstetric) {}
