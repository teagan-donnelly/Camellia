package com.camellia.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CoordinationAlert(String alertId, String flagId, String patientId, String alertType, String title,
                                String assignedTeam, String status, String priority, String appointmentId,
                                LocalDateTime encounterDate, LocalDate reviewDueDate, String acknowledgedBy,
                                LocalDateTime acknowledgedAt, String contactStatus, boolean planningDiscussionOccurred,
                                boolean infoNeeded, String dismissReason, String concernSummary,
                                String involvedSpecialties, LocalDateTime createdAt, LocalDateTime updatedAt,
                                String updatedBy, LocalDateTime evidenceUpdatedAt) {}
