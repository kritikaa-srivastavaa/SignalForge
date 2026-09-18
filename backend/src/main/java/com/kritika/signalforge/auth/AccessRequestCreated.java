package com.kritika.signalforge.auth;

import java.time.Instant;
import java.util.UUID;

// Immutable notification data; never carry a JPA user, password, or session.
public record AccessRequestCreated(UUID requestId, String requesterEmail, Instant requestedAt, UserRole requestedRole) { }
