package com.kritika.signalforge.audit;

import com.kritika.signalforge.auth.AppUser;
import com.kritika.signalforge.auth.AppUserRepository;
import com.kritika.signalforge.common.PageResponse;
import com.kritika.signalforge.common.Pagination;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    public AuditService(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    // Mandatory joins the caller's business transaction. A failed insert rolls it back.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AppUser actor, AuditAction action, AuditTarget target, UUID targetId,
                       String oldValue, String newValue) {
        jdbc.update("""
                INSERT INTO audit_records
                (id, actor_id, actor_email, action, target_type, target_id, timestamp, old_value, new_value)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.getId(), actor.getEmail(), action.name(), target.name(),
                targetId, Timestamp.from(Instant.now()), oldValue, newValue);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCurrentActor(AuditAction action, AuditTarget target, UUID targetId,
                                   String oldValue, String newValue) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) throw new AccessDeniedException("Authentication required");
        var actor = users.findByEmail(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Unknown actor"));
        record(actor, action, target, targetId, oldValue, newValue);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditResponse> list(AuditAction action, int page, int size) {
        var pageable = Pagination.request(page, size, "timestamp");
        String where = action == null ? "" : " WHERE action = ?";
        Object[] filter = action == null ? new Object[0] : new Object[] {action.name()};
        long total = jdbc.queryForObject("SELECT count(*) FROM audit_records" + where, Long.class, filter);
        var arguments = new java.util.ArrayList<Object>(java.util.Arrays.asList(filter));
        arguments.add(pageable.getPageSize());
        arguments.add(pageable.getOffset());
        var rows = jdbc.query("SELECT * FROM audit_records" + where
                + " ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?", (row, index) ->
                new AuditResponse(row.getObject("id", UUID.class), row.getObject("actor_id", UUID.class),
                        row.getString("actor_email"), AuditAction.valueOf(row.getString("action")),
                        AuditTarget.valueOf(row.getString("target_type")), row.getObject("target_id", UUID.class),
                        row.getTimestamp("timestamp").toInstant(), row.getString("old_value"), row.getString("new_value")),
                arguments.toArray());
        return PageResponse.from(new PageImpl<>(rows, pageable, total));
    }
}
