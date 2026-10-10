package com.camellia.model;

import java.time.LocalDateTime;

public record CoordinationNote(String noteId, String alertId, String patientId, String authorName, String authorRole,
                               String noteType, String body, LocalDateTime createdAt) {}
