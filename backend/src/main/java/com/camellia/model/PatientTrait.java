package com.camellia.model;

import java.time.LocalDateTime;

/** A structured, searchable patient trait derived from a coded diagnosis (see {@code TraitService}). */
public record PatientTrait(String traitId, String patientId, String encounterId, String traitCode, String traitLabel,
                           String codeSystem, String code, String codeDisplay, String orgId, String sourceRecordId,
                           String conditionId, String status, LocalDateTime recordedAt, String recordedBy,
                           LocalDateTime createdAt) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
