package com.camellia.analysis;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Routing details for a care-coordination alert produced alongside a flag. */
public record AlertSpec(String alertType, String title, String assignedTeam, String appointmentId,
                        LocalDateTime encounterDate, LocalDate reviewDueDate, String concernSummary,
                        String involvedSpecialties) {}
