package com.kritika.signalforge.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    long countByRole(UserRole role);
    Optional<AppUser> findByEmail(String email);
}
