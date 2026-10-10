package com.camellia.model;

import java.time.LocalDateTime;

/** A FLAG_EVIDENCE or ALERT_EVIDENCE row: owner id + cited record. */
public record EvidenceLink(String ownerId, String recordId, String role, String description, LocalDateTime addedAt) {}
