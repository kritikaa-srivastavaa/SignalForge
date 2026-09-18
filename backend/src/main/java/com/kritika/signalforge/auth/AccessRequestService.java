package com.kritika.signalforge.auth;

import com.kritika.signalforge.audit.*;
import com.kritika.signalforge.common.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessRequestService {
    private final AccessRequestRepository requests;
    private final AppUserRepository users;
    private final RoleChangeLock roleChanges;
    private final AuditService audit;

    public AccessRequestService(AccessRequestRepository requests, AppUserRepository users,
                                RoleChangeLock roleChanges, AuditService audit) {
        this.requests = requests; this.users = users; this.roleChanges = roleChanges; this.audit = audit;
    }

    @Transactional
    public AccessRequestResponse create(String email) {
        roleChanges.acquire();
        var requester = actor(email);
        if (requester.getRole() != UserRole.VIEWER)
            throw conflict("You already have operational access");
        if (requests.existsByRequesterIdAndStatus(requester.getId(), AccessRequestStatus.PENDING))
            throw conflict("An access request is already pending");
        var request = requests.saveAndFlush(new AccessRequest(requester));
        audit.record(requester, AuditAction.ACCESS_REQUEST_CREATED, AuditTarget.ACCESS_REQUEST,
                request.getId(), null, AccessRequestStatus.PENDING.name());
        return AccessRequestResponse.from(request);
    }

    @Transactional(readOnly = true)
    public Optional<AccessRequestResponse> latest(String email) {
        return requests.findFirstByRequesterIdOrderByCreatedAtDescIdDesc(actor(email).getId())
                .map(AccessRequestResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessRequestResponse> list(AccessRequestStatus status, int page, int size) {
        var pageable = Pagination.request(page, size, "createdAt");
        var result = status == null ? requests.findAll(pageable) : requests.findByStatus(status, pageable);
        return PageResponse.from(result.map(AccessRequestResponse::from));
    }

    @Transactional
    public AccessRequestResponse review(String email, UUID id, boolean approve, String reason) {
        // The same lock as manual role changes prevents stale reviewers and role overwrites.
        roleChanges.acquire();
        var reviewer = actor(email);
        if (reviewer.getRole() != UserRole.ADMIN) throw new AccessDeniedException("Access denied");
        var request = requests.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Access request not found"));
        if (request.getRequester().getId().equals(reviewer.getId()))
            throw new AccessDeniedException("You cannot review your own request");
        if (request.getStatus() != AccessRequestStatus.PENDING)
            throw conflict("This request has already been reviewed");
        var requester = request.getRequester(); // FK ensures the user still exists.
        var oldRole = requester.getRole();
        if (approve && oldRole == UserRole.VIEWER) {
            requester.changeRole(UserRole.OPERATOR);
            users.saveAndFlush(requester);
            audit.record(reviewer, AuditAction.USER_ROLE_CHANGED, AuditTarget.USER, requester.getId(),
                    oldRole.name(), UserRole.OPERATOR.name());
        }
        // Already OPERATOR/ADMIN: access is satisfied; never downgrade an administrator.
        var status = approve ? AccessRequestStatus.APPROVED : AccessRequestStatus.REJECTED;
        String explanation = approve && oldRole != UserRole.VIEWER
                ? "Operational access was already satisfied (" + oldRole + ")." : reason;
        request.review(status, reviewer, explanation);
        requests.saveAndFlush(request);
        audit.record(reviewer, approve ? AuditAction.ACCESS_REQUEST_APPROVED : AuditAction.ACCESS_REQUEST_REJECTED,
                AuditTarget.ACCESS_REQUEST, id, AccessRequestStatus.PENDING.name(), status.name());
        return AccessRequestResponse.from(request);
    }

    private AppUser actor(String email) {
        return users.findByEmail(email).orElseThrow(() -> new AccessDeniedException("Unknown actor"));
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
