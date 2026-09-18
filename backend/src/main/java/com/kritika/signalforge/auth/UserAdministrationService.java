package com.kritika.signalforge.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAdministrationService {
    private final AppUserRepository users;
    private final RoleChangeLock roleChanges;

    public UserAdministrationService(AppUserRepository users, RoleChangeLock roleChanges) {
        this.users = users;
        this.roleChanges = roleChanges;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return users.findAll(Sort.by("email")).stream().map(UserResponse::from).toList();
    }

    @Transactional
    public UserResponse changeRole(String actorEmail, UUID targetId, UserRole role) {
        roleChanges.acquire();
        // Recheck after the lock: another transaction may have demoted this actor while it waited.
        AppUser actor = users.findByEmail(actorEmail).orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (actor.getRole() != UserRole.ADMIN) throw new AccessDeniedException("Access denied");
        AppUser target = users.findById(targetId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (target.getRole() == UserRole.ADMIN && role != UserRole.ADMIN
                && users.countByRole(UserRole.ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The last administrator cannot be demoted");
        }
        target.changeRole(role);
        return UserResponse.from(users.saveAndFlush(target));
    }
}
