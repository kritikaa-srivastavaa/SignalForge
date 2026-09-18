package com.kritika.signalforge.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    // A fresh read after commit avoids reusing the completed request transaction.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @Query("select u.email from AppUser u where u.role = :role")
    List<String> findEmailsByRole(UserRole role);

    long countByRole(UserRole role);
    Optional<AppUser> findByEmail(String email);
}
