package com.kritika.signalforge.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

// Only stable, non-secret identity is kept in the session. Roles are reloaded on requests.
public record SessionIdentity(UUID id, String email) implements Principal, Serializable {
    @Override public String getName() { return email; }
}
