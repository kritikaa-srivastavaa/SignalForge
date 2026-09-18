package com.kritika.signalforge.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessRequestNotificationServiceTests {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final AccessRequestCreated request = new AccessRequestCreated(UUID.randomUUID(),
            "viewer@example.test", Instant.parse("2026-09-18T10:00:00Z"), UserRole.OPERATOR);
    private AccessRequestNotificationService service(boolean enabled, String from) {
        return new AccessRequestNotificationService(users, mail, enabled, from, "http://localhost:5173/");
    }

    @Test void disabledMailDoesNotLookUpRecipientsOrSend() {
        service(false, "").notifyReviewers(request);
        verifyNoInteractions(users, mail);
    }

    @Test void sendsPrivateMessagesWithOnlyNecessaryRequestInformation() {
        when(users.findEmailsByRole(UserRole.ADMIN)).thenReturn(List.of("admin-a@example.test", "admin-b@example.test"));
        service(true, "notifications@example.test").notifyReviewers(request);
        var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(2)).send(messages.capture());
        assertThat(messages.getAllValues()).extracting(m -> m.getTo()[0])
                .containsExactly("admin-a@example.test", "admin-b@example.test");
        for (var message : messages.getAllValues()) {
            assertThat(message.getTo()).hasSize(1);
            assertThat(message.getSubject()).isEqualTo("SignalForge: Operator access request");
            assertThat(message.getText()).contains(request.requesterEmail(), request.requestedAt().toString(),
                    "http://localhost:5173/admin/access-requests").doesNotContain("password", "CSRF", "cookie", "session");
        }
        verify(users).findEmailsByRole(UserRole.ADMIN);
        verify(users).findEmailsByRole(UserRole.OPERATOR);
        verifyNoMoreInteractions(users);
    }

    @Test void failureForOneAdminDoesNotStopTheOthers() {
        when(users.findEmailsByRole(UserRole.ADMIN)).thenReturn(List.of("admin-a@example.test", "admin-b@example.test"));
        doThrow(new MailSendException("provider details must not be logged")).doNothing()
                .when(mail).send(any(SimpleMailMessage.class));
        assertThatCode(() -> service(true, "notifications@example.test").notifyReviewers(request)).doesNotThrowAnyException();
        verify(mail, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test void zeroAdminsDoesNotCrashOrSend() {
        when(users.findEmailsByRole(UserRole.ADMIN)).thenReturn(List.of());
        assertThatCode(() -> service(true, "notifications@example.test").notifyReviewers(request)).doesNotThrowAnyException();
        verifyNoInteractions(mail);
    }

    @Test void recipientLookupFailureIsContained() {
        when(users.findEmailsByRole(UserRole.ADMIN)).thenThrow(new IllegalStateException("unavailable"));
        assertThatCode(() -> service(true, "notifications@example.test").notifyReviewers(request)).doesNotThrowAnyException();
        verifyNoInteractions(mail);
    }

    @Test void missingFromSkipsMailSafely() {
        when(users.findEmailsByRole(UserRole.ADMIN)).thenReturn(List.of("admin@example.test"));
        service(true, "").notifyReviewers(request);
        verifyNoInteractions(mail);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void populatedGroupReceivesMailWithoutAdminAndRequesterOrDuplicates(UserRole target) {
        when(users.findEmailsByRole(target)).thenReturn(List.of("member-a@example.test", "member-b@example.test",
                "member-a@example.test", request.requesterEmail()));
        var value = new AccessRequestCreated(request.requestId(), request.requesterEmail(), request.requestedAt(), target);
        service(true, "notifications@example.test").notifyReviewers(value);
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(m -> m.getTo()[0])
                .containsExactly("member-a@example.test", "member-b@example.test");
        assertThat(sent.getValue().getText()).contains(target.name(), "/access/review");
        verify(users, never()).findEmailsByRole(UserRole.ADMIN);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void emptyTargetGroupFallsBackOnlyToAdmins(UserRole target) {
        when(users.findEmailsByRole(target)).thenReturn(List.of());
        when(users.findEmailsByRole(UserRole.ADMIN)).thenReturn(List.of("admin@example.test"));
        service(true, "notifications@example.test").notifyReviewers(new AccessRequestCreated(request.requestId(),
                request.requesterEmail(), request.requestedAt(), target));
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("admin@example.test");
        assertThat(sent.getValue().getText()).contains(target.name(), "/admin/access-requests");
    }
}
