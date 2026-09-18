package com.kritika.signalforge.auth;

import jakarta.validation.Validator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapAdminService {
    private final AppUserRepository users;
    private final RoleChangeLock roleChanges;
    private final PasswordEncoder passwords;
    private final Validator validator;

    public BootstrapAdminService(AppUserRepository users, RoleChangeLock roleChanges,
                                 PasswordEncoder passwords, Validator validator) {
        this.users = users;
        this.roleChanges = roleChanges;
        this.passwords = passwords;
        this.validator = validator;
    }

    @Transactional
    public void bootstrap(String email, String password, String displayName) {
        var input = new RegisterRequest(email, password, displayName);
        if (!validator.validate(input).isEmpty()) {
            throw new IllegalStateException("Invalid bootstrap configuration; follow the documented account requirements");
        }
        roleChanges.acquire();
        var existing = users.findByEmail(input.email());
        if (existing.isPresent()) {
            // Never claim a public registration, reset a password, or override a later demotion.
            if (existing.get().getRole() != UserRole.ADMIN
                    || !passwords.matches(input.password(), existing.get().getPasswordHash())) {
                throw new IllegalStateException("Bootstrap identity already exists with incompatible access or credentials");
            }
            return;
        }
        var user = new AppUser(input.email(), passwords.encode(input.password()), input.displayName());
        user.changeRole(UserRole.ADMIN);
        users.saveAndFlush(user);
    }
}
