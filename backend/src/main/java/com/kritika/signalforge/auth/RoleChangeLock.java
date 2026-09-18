package com.kritika.signalforge.auth;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RoleChangeLock {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public RoleChangeLock(JdbcTemplate jdbc, EntityManager entities) {
        this.jdbc = jdbc;
        this.entities = entities;
    }

    // Call inside a transaction. PostgreSQL releases this single role-management lock at commit/rollback.
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void acquire() {
        jdbc.execute("SELECT pg_advisory_xact_lock(73402624)");
        // A request filter may have loaded the actor before waiting; discard that stale JPA snapshot.
        entities.clear();
    }
}
