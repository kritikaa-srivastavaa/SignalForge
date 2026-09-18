package com.kritika.signalforge.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, UUID> {
    boolean existsByRequesterIdAndStatus(UUID requesterId, AccessRequestStatus status);
    Optional<AccessRequest> findFirstByRequesterIdOrderByCreatedAtDescIdDesc(UUID requesterId);
    Page<AccessRequest> findByRequestedRoleAndStatus(UserRole requestedRole, AccessRequestStatus status, Pageable pageable);
    Page<AccessRequest> findByStatus(AccessRequestStatus status, Pageable pageable);
}
