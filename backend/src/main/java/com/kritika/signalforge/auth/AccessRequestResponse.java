package com.kritika.signalforge.auth;

import java.time.Instant;
import java.util.UUID;

public record AccessRequestResponse(UUID id, UUID requesterId, String requesterEmail,
        UserRole requestedRole, AccessRequestStatus status, Instant createdAt, Instant reviewedAt,
        UUID reviewedBy, String reviewReason) {
    static AccessRequestResponse from(AccessRequest request) {
        return new AccessRequestResponse(request.getId(), request.getRequester().getId(),
                request.getRequester().getEmail(), request.getRequestedRole(), request.getStatus(),
                request.getCreatedAt(), request.getReviewedAt(),
                request.getReviewedBy() == null ? null : request.getReviewedBy().getId(), request.getReviewReason());
    }
}
