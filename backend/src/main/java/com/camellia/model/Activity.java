package com.camellia.model;

import java.time.LocalDateTime;

public record Activity(String activityId, String entityType, String entityId, String patientId, String action,
                       String detail, String actor, LocalDateTime createdAt) {}
