package com.kritika.signalforge.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class AccessRequestNotificationService {
    private static final Logger log = LoggerFactory.getLogger(AccessRequestNotificationService.class);
    private final AppUserRepository users;
    private final JavaMailSender mail;
    private final boolean enabled;
    private final String from;
    private final String publicUrl;

    public AccessRequestNotificationService(AppUserRepository users, JavaMailSender mail,
            @Value("${signalforge.mail.enabled:false}") boolean enabled,
            @Value("${signalforge.mail.from:}") String from,
            @Value("${signalforge.public-url:http://localhost:5173}") String publicUrl) {
        this.users = users; this.mail = mail; this.enabled = enabled;
        this.from = from; this.publicUrl = publicUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyReviewers(AccessRequestCreated request) {
        if (!enabled) return;
        try {
            var members = users.findEmailsByRole(request.requestedRole());
            var recipients = (members.isEmpty() ? users.findEmailsByRole(UserRole.ADMIN) : members)
                    .stream().filter(email -> !email.equalsIgnoreCase(request.requesterEmail())).distinct().toList();
            if (recipients.isEmpty()) {
                log.warn("No eligible recipients to notify for access request {}", request.requestId());
                return;
            }
            if (from.isBlank()) {
                log.warn("Access request {} notification skipped: MAIL_FROM is not configured", request.requestId());
                return;
            }
            for (String recipient : recipients) {
                try {
                    var message = new SimpleMailMessage();
                    message.setFrom(from);
                    message.setTo(recipient); // Separate messages keep recipients private.
                    message.setSubject("SignalForge: " + (request.requestedRole() == UserRole.VIEWER ? "Viewer" : "Operator") + " access request");
                    message.setText("A SignalForge user requested " + request.requestedRole() + " access.\n\n"
                            + "Requester: " + request.requesterEmail() + "\n"
                            + "Requested at: " + request.requestedAt() + " (UTC)\n\n"
                            + "Review this request in SignalForge's Access Requests page:\n"
                            + publicUrl.replaceAll("/+$", "") + (members.isEmpty() ? "/admin/access-requests" : "/access/review") + "\n\n"
                            + "The Access Requests queue is the source of truth.");
                    mail.send(message);
                } catch (RuntimeException failure) {
                    // Provider exception messages/stack traces can contain credentials.
                    log.warn("Email delivery failed for access request {} ({})",
                            request.requestId(), failure.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Notification failed for committed access request {} ({})",
                    request.requestId(), failure.getClass().getSimpleName());
        }
    }
}
