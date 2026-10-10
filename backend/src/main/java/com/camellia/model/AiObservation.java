package com.camellia.model;

import java.time.LocalDateTime;

public record AiObservation(String observationId, String patientId, String recordId, String category, String statement,
                            String evidenceQuote, String model, LocalDateTime createdAt) {}
