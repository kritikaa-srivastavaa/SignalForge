package com.kritika.signalforge.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditResponse(UUID id, UUID actorId, String actorEmail, AuditAction action,
        AuditTarget targetType, UUID targetId, Instant timestamp, String oldValue, String newValue) { }
