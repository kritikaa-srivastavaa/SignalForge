package com.kritika.signalforge.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_requests")
public class AccessRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false, updatable = false)
    private AppUser requester;
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_role", nullable = false, updatable = false, length = 16)
    private UserRole requestedRole;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccessRequestStatus status = AccessRequestStatus.PENDING;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;
    @Column(name = "review_reason", length = 300)
    private String reviewReason;
    @Version private long version;

    protected AccessRequest() { }
    AccessRequest(AppUser requester, UserRole requestedRole) {
        this.requester = requester; this.requestedRole = requestedRole;
    }
    @PrePersist private void onCreate() { createdAt = Instant.now(); }

    void review(AccessRequestStatus status, AppUser reviewer, String reason) {
        this.status = status;
        reviewedBy = reviewer;
        reviewedAt = Instant.now();
        reviewReason = reason;
    }
    public UUID getId() { return id; }
    public AppUser getRequester() { return requester; }
    public UserRole getRequestedRole() { return requestedRole; }
    public AccessRequestStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public AppUser getReviewedBy() { return reviewedBy; }
    public String getReviewReason() { return reviewReason; }
}
