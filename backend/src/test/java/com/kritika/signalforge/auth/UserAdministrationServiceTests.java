package com.kritika.signalforge.auth;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAdministrationServiceTests {
    @Test void lastAdminCannotDemoteSelf() {
        var users = mock(AppUserRepository.class); var lock = mock(RoleChangeLock.class);
        var admin = new AppUser("test@example.com", "test-only-hash", "Test");
        admin.changeRole(UserRole.ADMIN);
        var id = UUID.randomUUID();
        when(users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(users.findById(id)).thenReturn(Optional.of(admin));
        when(users.countByRole(UserRole.ADMIN)).thenReturn(1L);
        var service = new UserAdministrationService(users, lock);
        assertThatThrownBy(() -> service.changeRole(admin.getEmail(), id, UserRole.VIEWER))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        verify(lock).acquire();
        verify(users, never()).saveAndFlush(any());
    }

    @Test void demotedActorCannotDemoteTheOnlyRemainingAdmin() {
        var users = mock(AppUserRepository.class);
        var actor = new AppUser("actor@example.com", "test-only-hash", "Actor");
        var target = new AppUser("target@example.com", "test-only-hash", "Target"); target.changeRole(UserRole.ADMIN);
        UUID id = UUID.randomUUID();
        when(users.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));

        when(users.countByRole(UserRole.ADMIN)).thenReturn(1L);
        assertThatThrownBy(() -> new UserAdministrationService(users, mock(RoleChangeLock.class))
                .changeRole(actor.getEmail(), id, UserRole.OPERATOR)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(users, never()).saveAndFlush(any());
    }
}
