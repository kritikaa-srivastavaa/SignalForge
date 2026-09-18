package com.kritika.signalforge.auth;

import com.kritika.signalforge.audit.*;
import com.kritika.signalforge.common.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    public AccessRequestService(AccessRequestRepository requests, AppUserRepository users,
                                RoleChangeLock roleChanges, AuditService audit, ApplicationEventPublisher events) {
        this.requests = requests; this.users = users; this.roleChanges = roleChanges; this.audit = audit; this.events = events;
    }

    @Transactional
    public AccessRequestResponse create(String email) {
        roleChanges.acquire();
        var requester = actor(email);
        UserRole requestedRole = switch (requester.getRole()) {
            case NO_ACCESS -> UserRole.VIEWER;
            case VIEWER -> UserRole.OPERATOR;
            default -> throw conflict("You already have operational access");
        };
        if (requests.existsByRequesterIdAndStatus(requester.getId(), AccessRequestStatus.PENDING))
            throw conflict("An access request is already pending");
        var request = requests.saveAndFlush(new AccessRequest(requester, requestedRole));
        audit.record(requester, AuditAction.ACCESS_REQUEST_CREATED, AuditTarget.ACCESS_REQUEST,
                request.getId(), null, AccessRequestStatus.PENDING.name());
        events.publishEvent(new AccessRequestCreated(request.getId(), requester.getEmail(), request.getCreatedAt(), requestedRole));
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

    @Transactional(readOnly = true)
    public PageResponse<AccessRequestResponse> groupQueue(String email, int page, int size) {
        UserRole role = actor(email).getRole();
        if (role != UserRole.VIEWER && role != UserRole.OPERATOR)
            throw new AccessDeniedException("Access denied");
        var result = requests.findByRequestedRoleAndStatus(role, AccessRequestStatus.PENDING,
                Pagination.request(page, size, "createdAt"));
        return PageResponse.from(result.map(AccessRequestResponse::from));
    }

    @Transactional
    public AccessRequestResponse review(String email, UUID id, boolean approve, String reason) {
        // The same lock as manual role changes prevents stale reviewers and role overwrites.
        roleChanges.acquire();
        var reviewer = actor(email);
        var request = requests.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Access request not found"));
        if (reviewer.getRole() != UserRole.ADMIN && reviewer.getRole() != request.getRequestedRole())
            throw new AccessDeniedException("You cannot review requests for this access group");
        if (request.getRequester().getId().equals(reviewer.getId()))
            throw new AccessDeniedException("You cannot review your own request");
        if (request.getStatus() != AccessRequestStatus.PENDING)
            throw conflict("This request has already been reviewed");
        var requester = request.getRequester(); // FK ensures the user still exists.
        var oldRole = requester.getRole();
        UserRole target = request.getRequestedRole();
        boolean eligible = target == UserRole.VIEWER ? oldRole == UserRole.NO_ACCESS : oldRole == UserRole.VIEWER;
        boolean satisfied = oldRole == UserRole.ADMIN || oldRole == UserRole.OPERATOR
                || (target == UserRole.VIEWER && oldRole == UserRole.VIEWER);
        if (approve && !eligible && !satisfied)
            throw conflict("Current access is no longer eligible for this request");
        if (approve && eligible) {
            requester.changeRole(target);
            users.saveAndFlush(requester);
            audit.record(reviewer, AuditAction.USER_ROLE_CHANGED, AuditTarget.USER, requester.getId(),
                    oldRole.name(), target.name());
        }
        // Already OPERATOR/ADMIN: access is satisfied; never downgrade an administrator.
        var status = approve ? AccessRequestStatus.APPROVED : AccessRequestStatus.REJECTED;
        String explanation = approve && satisfied
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
