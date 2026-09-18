package com.kritika.signalforge.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AppUserRepository users;
    private final PasswordEncoder passwords;

    public AuthService(AppUserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        // The database unique constraint also handles concurrent registrations.
        return UserResponse.from(users.saveAndFlush(new AppUser(
                request.email(), passwords.encode(request.password()), request.displayName())));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String email) {
        return UserResponse.from(users.findByEmail(email).orElseThrow(
                () -> new org.springframework.security.authentication.BadCredentialsException("Invalid email or password")));
    }
}
