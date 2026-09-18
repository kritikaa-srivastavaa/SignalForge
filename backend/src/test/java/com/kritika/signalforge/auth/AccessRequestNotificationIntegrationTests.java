package com.kritika.signalforge.auth;

import com.kritika.signalforge.event.EventPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "signalforge.mail.enabled=true", "signalforge.mail.from=notifications@example.test"})
class AccessRequestNotificationIntegrationTests {
    @Autowired AccessRequestService service;
    @Autowired AccessRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean AppUserRepository users;
    @MockitoBean JavaMailSender mail;
    @MockitoBean EventPublisher publisher;
    private final List<UUID> ids = new ArrayList<>();

    private AppUser user(UserRole role) {
        // Isolated test identities; no live credentials or real email deliveries.
        var user = new AppUser("mail-test-" + UUID.randomUUID() + "@example.test", "test-only-hash", "Mail Test");
        user.changeRole(role);
        users.saveAndFlush(user); ids.add(user.getId()); return user;
    }

    @AfterEach void cleanup() {
        reset(users);
        for (UUID id : ids) jdbc.update("DELETE FROM audit_records WHERE actor_id = ?", id);
        for (UUID id : ids) jdbc.update("DELETE FROM access_requests WHERE requester_id = ?", id);
        users.deleteAllById(ids);
    }

    @Test void committedRequestNotifiesOperatorsAndNoOtherRoles() {
        user(UserRole.ADMIN); user(UserRole.ADMIN);
        var operator = user(UserRole.ADMIN); user(UserRole.OPERATOR); user(UserRole.OPERATOR); var viewer = user(UserRole.VIEWER);
        var expected = users.findEmailsByRole(UserRole.OPERATOR);
        var result = service.create(viewer.getEmail());
        assertThat(result.status()).isEqualTo(AccessRequestStatus.PENDING);
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(expected.size())).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(m -> m.getTo()[0])
                .containsExactlyInAnyOrderElementsOf(expected).doesNotContain(operator.getEmail(), viewer.getEmail());
        assertThat(requests.findById(result.id())).isPresent();
    }

    @Test void notificationWaitsUntilOuterTransactionCommits() {
        var viewer = user(UserRole.VIEWER); user(UserRole.ADMIN);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.create(viewer.getEmail());
            verifyNoInteractions(mail);
        });
        verify(mail, atLeastOnce()).send(any(SimpleMailMessage.class));
    }

    @Test void rolledBackCreationDoesNotNotifyOrLeaveARequest() {
        var viewer = user(UserRole.VIEWER); user(UserRole.ADMIN);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.create(viewer.getEmail());
            tx.setRollbackOnly();
        });
        verifyNoInteractions(mail);
        assertThat(requests.existsByRequesterIdAndStatus(viewer.getId(), AccessRequestStatus.PENDING)).isFalse();
    }

    @Test void mailFailureLeavesCommittedRequestPendingAndInAdminQueue() {
        var viewer = user(UserRole.VIEWER); user(UserRole.ADMIN);
        doThrow(new MailSendException("sensitive provider details")).when(mail).send(any(SimpleMailMessage.class));
        var result = service.create(viewer.getEmail());
        assertThat(result.status()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(service.latest(viewer.getEmail()).orElseThrow().id()).isEqualTo(result.id());
        assertThat(requests.findById(result.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(service.list(AccessRequestStatus.PENDING, 0, 100).content())
                .extracting(AccessRequestResponse::id).contains(result.id());
    }

    @Test void zeroAdminNotificationStillAllowsCreation() {
        var viewer = user(UserRole.VIEWER);
        // Do not remove real administrators to simulate this edge case.
        doReturn(List.of()).when(users).findEmailsByRole(UserRole.ADMIN);
        doReturn(List.of()).when(users).findEmailsByRole(UserRole.OPERATOR);
        var result = service.create(viewer.getEmail());
        assertThat(requests.findById(result.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        verifyNoInteractions(mail);
    }

    @Test void duplicateAndPrivilegedFailuresDoNotNotify() {
        var viewer = user(UserRole.VIEWER); user(UserRole.ADMIN);
        service.create(viewer.getEmail()); reset(mail);
        assertThatThrownBy(() -> service.create(viewer.getEmail())).isInstanceOf(ResponseStatusException.class);
        var operator = user(UserRole.OPERATOR);
        assertThatThrownBy(() -> service.create(operator.getEmail())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mail);
    }

    @Test void rejectedRequestCanBeSubmittedAgainAndOnlyNewCreationNotifies() {
        var viewer = user(UserRole.VIEWER); var admin = user(UserRole.ADMIN);
        var first = service.create(viewer.getEmail()); reset(mail);
        service.review(admin.getEmail(), first.id(), false, "Please clarify duties.");
        verifyNoInteractions(mail);
        var second = service.create(viewer.getEmail());
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(requests.findById(first.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.REJECTED);
        verify(mail, atLeastOnce()).send(any(SimpleMailMessage.class));
    }
}
