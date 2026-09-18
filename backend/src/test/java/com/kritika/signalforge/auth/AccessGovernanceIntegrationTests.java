package com.kritika.signalforge.auth;

import com.kritika.signalforge.audit.*;
import com.kritika.signalforge.event.EventPublisher;
import com.kritika.signalforge.incident.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
class AccessGovernanceIntegrationTests {
    private static final String PASSWORD = "governance-test-password";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessRequestService service;
    @Autowired AccessRequestRepository requests;
    @Autowired UserAdministrationService administration;
    @Autowired IncidentRepository incidents;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource datasource;
    @MockitoBean EventPublisher publisher;
    @MockitoSpyBean AuditService audit;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();
    private record Client(AppUser user, MockHttpSession session, String token) { }

    private AuditService auditTarget() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit); }

    @AfterEach void cleanup() {
        reset(auditTarget());
        for (UUID id : userIds) jdbc.update("DELETE FROM audit_records WHERE actor_id = ?", id);
        for (UUID id : userIds) jdbc.update("DELETE FROM access_requests WHERE requester_id = ?", id);
        incidents.deleteAllById(incidentIds);
        users.deleteAllById(userIds);
    }
    private AppUser create(UserRole role) {
        var user = new AppUser("governance-test-" + UUID.randomUUID() + "@example.com", passwords.encode(PASSWORD), "Governance Test");
        user.changeRole(role); users.saveAndFlush(user); userIds.add(user.getId()); return user;
    }
    private Client login(AppUser user) throws Exception {
        var csrf = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession();
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk());
        token = json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).get("token").asText();
        return new Client(user, session, token);
    }
    private ResultActions submit(Client client, String body) throws Exception {
        return mvc.perform(post("/access-requests").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions review(Client client, UUID id, String action, String body) throws Exception {
        return mvc.perform(patch("/admin/access-requests/" + id + "/" + action).session(client.session())
                .header("X-CSRF-TOKEN", client.token()).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private long count(AuditAction action, UUID target) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_records WHERE action = ? AND target_id = ?", Long.class, action.name(), target);
    }
    private Incident incident() {
        var value = incidents.saveAndFlush(new Incident(UUID.randomUUID(), "governance-test", "TEST", "HIGH", "Test"));
        incidentIds.add(value.getId()); return value;
    }

    @Test void creationIgnoresAllClientSecurityFieldsAndLatestIsPrivate() throws Exception {
        var viewer = login(create(UserRole.VIEWER)); var other = login(create(UserRole.VIEWER));
        mvc.perform(get("/access-requests/me").session(viewer.session())).andExpect(status().isNoContent());
        var result = submit(viewer, json.writeValueAsString(Map.of("userId", other.user().getId(), "status", "APPROVED",
                "requestedRole", "ADMIN", "reviewedBy", other.user().getId(), "reviewedAt", "2026-09-18T00:00:00Z")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requesterId").value(viewer.user().getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.requestedRole").value("OPERATOR"))
                .andExpect(jsonPath("$.reviewedBy").isEmpty()).andReturn();
        UUID id = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        assertThat(count(AuditAction.ACCESS_REQUEST_CREATED, id)).isEqualTo(1);
        mvc.perform(get("/access-requests/me").session(other.session())).andExpect(status().isNoContent());
        mvc.perform(get("/access-requests/me").session(viewer.session())).andExpect(jsonPath("$.id").value(id.toString()));
        submit(viewer, "{}").andExpect(status().isConflict());
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"OPERATOR", "ADMIN"})
    void alreadyPrivilegedUsersCannotRequest(UserRole role) throws Exception {
        var client = login(create(role));
        submit(client, "{}").andExpect(status().isConflict());
        assertThat(requests.existsByRequesterIdAndStatus(client.user().getId(), AccessRequestStatus.PENDING)).isFalse();
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void nonAdminsCannotReviewListOrAuditWithValidCsrf(UserRole role) throws Exception {
        var viewer = create(UserRole.VIEWER); var request = service.create(viewer.getEmail());
        var client = login(create(role));
        for (String path : List.of("/admin/access-requests", "/admin/audit"))
            mvc.perform(get(path).session(client.session())).andExpect(status().isForbidden());
        for (String action : List.of("approve", "reject"))
            review(client, request.id(), action, "{}").andExpect(status().isForbidden());
        assertThat(count(AuditAction.ACCESS_REQUEST_APPROVED, request.id())).isZero();
        assertThat(count(AuditAction.ACCESS_REQUEST_REJECTED, request.id())).isZero();
    }

    @Test void csrfAndAnonymousProtection() throws Exception {
        var viewer = login(create(UserRole.VIEWER)); var admin = login(create(UserRole.ADMIN));
        var request = service.create(viewer.user().getEmail());
        mvc.perform(post("/access-requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/access-requests/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/audit")).andExpect(status().isUnauthorized());
        mvc.perform(post("/access-requests").session(viewer.session())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        for (String action : List.of("approve", "reject")) {
            mvc.perform(patch("/admin/access-requests/" + request.id() + "/" + action)).andExpect(status().isUnauthorized());
            mvc.perform(patch("/admin/access-requests/" + request.id() + "/" + action).session(admin.session()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        assertThat(requests.findById(request.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
    }

    @Test void approvalGrantsRoleAndAuditsAtomicallyAndUpdatesExistingSession() throws Exception {
        var viewer = login(create(UserRole.VIEWER)); var admin = login(create(UserRole.ADMIN));
        var request = service.create(viewer.user().getEmail());
        review(admin, request.id(), "approve", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.reviewedAt").isNotEmpty())
                .andExpect(jsonPath("$.reviewedBy").value(admin.user().getId().toString()));
        assertThat(users.findById(viewer.user().getId()).orElseThrow().getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(count(AuditAction.ACCESS_REQUEST_APPROVED, request.id())).isEqualTo(1);
        assertThat(count(AuditAction.USER_ROLE_CHANGED, viewer.user().getId())).isEqualTo(1);
        mvc.perform(get("/auth/me").session(viewer.session())).andExpect(jsonPath("$.role").value("OPERATOR"));
        var incident = incident();
        mvc.perform(patch("/incidents/" + incident.getId() + "/acknowledge").session(viewer.session())
                .header("X-CSRF-TOKEN", viewer.token())).andExpect(status().isOk());
        review(admin, request.id(), "approve", "{}").andExpect(status().isConflict());
        review(admin, request.id(), "reject", "{}").andExpect(status().isConflict());
        assertThat(count(AuditAction.ACCESS_REQUEST_APPROVED, request.id())).isEqualTo(1);
    }

    @Test void rejectionPreservesHistoryAndAllowsNewRequest() throws Exception {
        var viewer = login(create(UserRole.VIEWER)); var admin = login(create(UserRole.ADMIN));
        var first = service.create(viewer.user().getEmail());
        review(admin, first.id(), "reject", "{\"reason\":\"Please describe your operational duties.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewReason").value("Please describe your operational duties."));
        assertThat(users.findById(viewer.user().getId()).orElseThrow().getRole()).isEqualTo(UserRole.VIEWER);
        submit(viewer, "{}").andExpect(status().isCreated());
        var latest = service.latest(viewer.user().getEmail()).orElseThrow();
        assertThat(latest.id()).isNotEqualTo(first.id());
        assertThat(latest.status()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(requests.findById(first.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.REJECTED);
        assertThat(count(AuditAction.ACCESS_REQUEST_REJECTED, first.id())).isEqualTo(1);
        assertThat(count(AuditAction.ACCESS_REQUEST_CREATED, latest.id())).isEqualTo(1);
        review(admin, first.id(), "reject", "{}").andExpect(status().isConflict());
    }

    @Test void validatesReviewReasonStatusAndMissingRequest() throws Exception {
        var admin = login(create(UserRole.ADMIN)); var viewer = create(UserRole.VIEWER);
        var request = service.create(viewer.getEmail());
        review(admin, request.id(), "reject", json.writeValueAsString(Map.of("reason", "x".repeat(301)))).andExpect(status().isBadRequest());
        review(admin, UUID.randomUUID(), "approve", "{}").andExpect(status().isNotFound());
        mvc.perform(get("/admin/access-requests?status=INVALID").session(admin.session())).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audit?action=INVALID").session(admin.session())).andExpect(status().isBadRequest());
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"OPERATOR", "ADMIN"})
    void alreadyPromotedRequesterIsApprovedWithoutDowngradeOrDuplicateRoleAudit(UserRole role) {
        var admin = create(UserRole.ADMIN); var viewer = create(UserRole.VIEWER);
        var request = service.create(viewer.getEmail());
        administration.changeRole(admin.getEmail(), viewer.getId(), role);
        var result = service.review(admin.getEmail(), request.id(), true, null);
        assertThat(result.status()).isEqualTo(AccessRequestStatus.APPROVED);
        assertThat(result.reviewReason()).contains("already satisfied");
        assertThat(users.findById(viewer.getId()).orElseThrow().getRole()).isEqualTo(role);
        assertThat(count(AuditAction.USER_ROLE_CHANGED, viewer.getId())).isEqualTo(1);
    }

    @Test void rejectionDoesNotRevokeManuallyGrantedAccess() {
        var admin = create(UserRole.ADMIN); var viewer = create(UserRole.VIEWER);
        var request = service.create(viewer.getEmail());
        administration.changeRole(admin.getEmail(), viewer.getId(), UserRole.OPERATOR);
        service.review(admin.getEmail(), request.id(), false, null);
        assertThat(users.findById(viewer.getId()).orElseThrow().getRole()).isEqualTo(UserRole.OPERATOR);
    }

    @Test void requesterPromotedToAdminStillCannotReviewOwnHistoricalRequest() throws Exception {
        var viewer = login(create(UserRole.VIEWER)); var admin = create(UserRole.ADMIN);
        var request = service.create(viewer.user().getEmail());
        administration.changeRole(admin.getEmail(), viewer.user().getId(), UserRole.ADMIN);
        for (String action : List.of("approve", "reject"))
            review(viewer, request.id(), action, "{}").andExpect(status().isForbidden());
        assertThat(requests.findById(request.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
    }

    @Test void concurrentCreationsHaveOneWinner() throws Exception {
        var viewer = create(UserRole.VIEWER);
        List<Integer> statuses = race(() -> service.create(viewer.getEmail()), () -> service.create(viewer.getEmail()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_requests WHERE requester_id = ? AND status = 'PENDING'", Long.class, viewer.getId())).isEqualTo(1);
    }

    @Test void databaseRejectsDuplicatePendingEvenOutsideService() {
        var viewer = create(UserRole.VIEWER);
        service.create(viewer.getEmail());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO access_requests(id,requester_id,requested_role,status,created_at)
                VALUES (?, ?, 'OPERATOR', 'PENDING', now())
                """, UUID.randomUUID(), viewer.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void concurrentReviewsHaveOneTerminalWinnerAndConsistentRole() throws Exception {
        var viewer = create(UserRole.VIEWER); var a = create(UserRole.ADMIN); var b = create(UserRole.ADMIN);
        var request = service.create(viewer.getEmail());
        assertThat(race(() -> service.review(a.getEmail(), request.id(), true, null),
                () -> service.review(b.getEmail(), request.id(), false, null))).containsExactlyInAnyOrder(200, 409);
        var approved = requests.findById(request.id()).orElseThrow().getStatus() == AccessRequestStatus.APPROVED;
        assertThat(users.findById(viewer.getId()).orElseThrow().getRole()).isEqualTo(approved ? UserRole.OPERATOR : UserRole.VIEWER);
        assertThat(count(AuditAction.ACCESS_REQUEST_APPROVED, request.id()) + count(AuditAction.ACCESS_REQUEST_REJECTED, request.id())).isEqualTo(1);
    }

    private List<Integer> race(Callable<?> first, Callable<?> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Integer>>();
            for (var work : List.of(first, second)) tasks.add(pool.submit(() -> {
                ready.countDown(); start.await();
                try { work.call(); return 200; } catch (ResponseStatusException error) { return error.getStatusCode().value(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test void auditFailureRollsBackApprovalRoleAndBothAuditFacts() {
        var viewer = create(UserRole.VIEWER); var admin = create(UserRole.ADMIN); var request = service.create(viewer.getEmail());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test audit failure")).when(auditTarget())
                .record(any(), eq(AuditAction.ACCESS_REQUEST_APPROVED), any(), any(), any(), any());
        assertThatThrownBy(() -> service.review(admin.getEmail(), request.id(), true, null)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(requests.findById(request.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(users.findById(viewer.getId()).orElseThrow().getRole()).isEqualTo(UserRole.VIEWER);
        assertThat(count(AuditAction.USER_ROLE_CHANGED, viewer.getId())).isZero();
    }

    @Test void auditFailureRollsBackManualRoleChangeAndRejection() {
        var viewer = create(UserRole.VIEWER); var admin = create(UserRole.ADMIN); var request = service.create(viewer.getEmail());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test audit failure")).when(auditTarget())
                .record(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> administration.changeRole(admin.getEmail(), viewer.getId(), UserRole.OPERATOR)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> service.review(admin.getEmail(), request.id(), false, null)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(users.findById(viewer.getId()).orElseThrow().getRole()).isEqualTo(UserRole.VIEWER);
        assertThat(requests.findById(request.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
    }

    @Test void lifecycleAuditsOnlyRealTransitionsAndForbiddenAttemptsDoNotAudit() throws Exception {
        var operator = login(create(UserRole.OPERATOR)); var viewer = login(create(UserRole.VIEWER)); var incident = incident();
        for (String action : List.of("acknowledge", "resolve")) {
            mvc.perform(patch("/incidents/" + incident.getId() + "/" + action).session(viewer.session())
                    .header("X-CSRF-TOKEN", viewer.token())).andExpect(status().isForbidden());
            for (int i = 0; i < 2; i++) mvc.perform(patch("/incidents/" + incident.getId() + "/" + action).session(operator.session())
                    .header("X-CSRF-TOKEN", operator.token())).andExpect(status().isOk());
        }
        assertThat(count(AuditAction.INCIDENT_ACKNOWLEDGED, incident.getId())).isEqualTo(1);
        assertThat(count(AuditAction.INCIDENT_RESOLVED, incident.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records WHERE actor_id = ?", Long.class, viewer.user().getId())).isZero();
    }

    @Test void directResolutionAuditsOpenToResolved() throws Exception {
        var operator = login(create(UserRole.OPERATOR)); var incident = incident();
        mvc.perform(patch("/incidents/" + incident.getId() + "/resolve").session(operator.session())
                .header("X-CSRF-TOKEN", operator.token())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT old_value FROM audit_records WHERE target_id = ?", String.class, incident.getId())).isEqualTo("OPEN");
    }

    @Test void auditFailureRollsBackIncidentTransition() throws Exception {
        var operator = login(create(UserRole.OPERATOR)); var incident = incident();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test audit failure")).when(auditTarget())
                .recordCurrentActor(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> mvc.perform(patch("/incidents/" + incident.getId() + "/resolve").session(operator.session())
                .header("X-CSRF-TOKEN", operator.token()))).hasRootCauseInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(incidents.findById(incident.getId()).orElseThrow().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(count(AuditAction.INCIDENT_RESOLVED, incident.getId())).isZero();
    }

    @Test void adminPaginationFiltersOrderingSafeDtosAndReadOnlyAuditApi() throws Exception {
        var admin = login(create(UserRole.ADMIN)); var a = create(UserRole.VIEWER); var b = create(UserRole.VIEWER);
        var first = service.create(a.getEmail()); var second = service.create(b.getEmail());
        service.review(admin.user().getEmail(), first.id(), false, null);
        var page = mvc.perform(get("/admin/access-requests?status=PENDING&size=1").session(admin.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PENDING")).andReturn();
        assertThat(page.getResponse().getContentAsString()).doesNotContain("password", "session", "csrf");
        administration.changeRole(admin.user().getEmail(), a.getId(), UserRole.OPERATOR);
        administration.changeRole(admin.user().getEmail(), a.getId(), UserRole.OPERATOR);
        assertThat(count(AuditAction.USER_ROLE_CHANGED, a.getId())).isEqualTo(1);
        var result = mvc.perform(get("/admin/audit?action=USER_ROLE_CHANGED&size=1").session(admin.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].targetId").value(a.getId().toString())).andReturn();
        var body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", "session", "csrf", "cookie");
        assertThat(json.readTree(body).get("content").get(0).properties()).hasSize(9);
        var next = mvc.perform(get("/admin/audit?action=USER_ROLE_CHANGED&size=1&page=1").session(admin.session())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(next.getResponse().getContentAsString()).get("page").asInt()).isEqualTo(1);
        var sorted = audit.list(null, 0, 100).content();
        assertThat(sorted).isSortedAccordingTo(Comparator.comparing(AuditResponse::timestamp).reversed()
                .thenComparing(r -> r.id().toString(), Comparator.reverseOrder()));
        for (String method : List.of("POST", "PATCH", "DELETE"))
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/admin/audit")
                    .session(admin.session()).header("X-CSRF-TOKEN", admin.token())).andExpect(status().isMethodNotAllowed());
    }

    @Test void migrationsApplyToAnEmptyIsolatedSchema() {
        String schema = "governance_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var flyway = org.flywaydb.core.Flyway.configure().dataSource(datasource)
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".access_requests", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".audit_records", Long.class)).isZero();
        } finally {
            // This unique, empty test schema is never public or a pre-existing database.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private UserRole lower(UserRole target) { return target == UserRole.VIEWER ? UserRole.NO_ACCESS : UserRole.VIEWER; }
    private ResultActions groupReview(Client client, UUID id, String action) throws Exception {
        return mvc.perform(patch("/access-requests/" + id + "/" + action).session(client.session())
                .header("X-CSRF-TOKEN", client.token()).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"NO_ACCESS", "VIEWER"})
    void onlyNextLevelIsDerivedEvenWithForgedEscalation(UserRole role) throws Exception {
        var client = login(create(role));
        var target = role == UserRole.NO_ACCESS ? UserRole.VIEWER : UserRole.OPERATOR;
        submit(client, "{\"requestedRole\":\"ADMIN\",\"status\":\"APPROVED\",\"reviewedBy\":\"forged\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requestedRole").value(target.name()))
                .andExpect(jsonPath("$.requesterId").value(client.user().getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.reviewedBy").isEmpty());
        submit(client, "{\"requestedRole\":\"OPERATOR\"}").andExpect(status().isConflict());
    }

    @Test void noAccessCannotReadIngestGovernOrReachAdminAndCsrfStillApplies() throws Exception {
        var client = login(create(UserRole.NO_ACCESS));
        for (String path : List.of("/events", "/events/" + UUID.randomUUID(), "/incidents", "/incidents/" + UUID.randomUUID(),
                "/access-requests/review", "/admin/users", "/admin/access-requests", "/admin/audit"))
            mvc.perform(get(path).session(client.session())).andExpect(status().isForbidden());
        mvc.perform(post("/events").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        groupReview(client, UUID.randomUUID(), "approve").andExpect(status().isForbidden());
        mvc.perform(post("/access-requests").session(client.session())).andExpect(status().isForbidden());
        mvc.perform(get("/auth/me").session(client.session())).andExpect(jsonPath("$.role").value("NO_ACCESS"));
        assertThat(requests.existsByRequesterIdAndStatus(client.user().getId(), AccessRequestStatus.PENDING)).isFalse();
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void exactGroupMemberApprovesWithCorrectAuditAndSessionRefresh(UserRole target) throws Exception {
        var requester = login(create(lower(target))); var reviewer = login(create(target));
        var request = service.create(requester.user().getEmail());
        var otherTarget = target == UserRole.VIEWER ? UserRole.OPERATOR : UserRole.VIEWER;
        var other = service.create(create(lower(otherTarget)).getEmail());
        var queue = mvc.perform(get("/access-requests/review?requestedRole=" + otherTarget)
                .session(reviewer.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(queue).contains(request.id().toString()).doesNotContain(other.id().toString());
        groupReview(reviewer, request.id(), "approve").andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedRole").value(target.name()))
                .andExpect(jsonPath("$.reviewedBy").value(reviewer.user().getId().toString()));
        mvc.perform(get("/auth/me").session(requester.session())).andExpect(jsonPath("$.role").value(target.name()));
        mvc.perform(get("/events").session(requester.session())).andExpect(status().isOk());
        assertThat(users.findById(requester.user().getId()).orElseThrow().getRole()).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_records WHERE action='ACCESS_REQUEST_APPROVED' AND target_id=?",
                UUID.class, request.id())).isEqualTo(reviewer.user().getId());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_records WHERE action='USER_ROLE_CHANGED' AND target_id=?",
                UUID.class, requester.user().getId())).isEqualTo(reviewer.user().getId());
        groupReview(reviewer, request.id(), "reject").andExpect(status().isConflict());
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void crossGroupAndNoAccessReviewsAreForbidden(UserRole target) throws Exception {
        var request = service.create(create(lower(target)).getEmail());
        for (UserRole role : List.of(target == UserRole.VIEWER ? UserRole.OPERATOR : UserRole.VIEWER, UserRole.NO_ACCESS)) {
            var client = login(create(role));
            groupReview(client, request.id(), "approve").andExpect(status().isForbidden());
            groupReview(client, request.id(), "reject").andExpect(status().isForbidden());
        }
        assertThat(requests.findById(request.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(count(AuditAction.ACCESS_REQUEST_APPROVED, request.id())).isZero();
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void groupRejectionKeepsLowerAccessAndAllowsRerequest(UserRole target) throws Exception {
        var requester = create(lower(target)); var reviewer = login(create(target));
        var request = service.create(requester.getEmail());
        mvc.perform(patch("/access-requests/" + request.id() + "/reject").session(reviewer.session())
                .header("X-CSRF-TOKEN", reviewer.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Please clarify duties.\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewReason").value("Please clarify duties."));
        assertThat(users.findById(requester.getId()).orElseThrow().getRole()).isEqualTo(lower(target));
        assertThat(service.create(requester.getEmail()).id()).isNotEqualTo(request.id());
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void adminSeesAllAndCanInterveneEvenWithGroupMembers(UserRole target) throws Exception {
        create(target); // Membership never removes ADMIN oversight.
        var admin = login(create(UserRole.ADMIN));
        var request = service.create(create(lower(target)).getEmail());
        var other = service.create(create(lower(target == UserRole.VIEWER ? UserRole.OPERATOR : UserRole.VIEWER)).getEmail());
        String body = mvc.perform(get("/admin/access-requests").session(admin.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(request.id().toString(), other.id().toString());
        review(admin, request.id(), "approve", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(users.findById(request.requesterId()).orElseThrow().getRole()).isEqualTo(target);
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void competingGroupReviewsHaveOneTerminalWinner(UserRole target) throws Exception {
        var requester = create(lower(target)); var a = create(target); var b = create(target);
        var request = service.create(requester.getEmail());
        assertThat(race(() -> service.review(a.getEmail(), request.id(), true, null),
                () -> service.review(b.getEmail(), request.id(), false, null))).containsExactlyInAnyOrder(200, 409);
        var saved = requests.findById(request.id()).orElseThrow();
        assertThat(users.findById(requester.getId()).orElseThrow().getRole()).isEqualTo(
                saved.getStatus() == AccessRequestStatus.APPROVED ? target : lower(target));
        assertThat(count(AuditAction.ACCESS_REQUEST_APPROVED, request.id()) + count(AuditAction.ACCESS_REQUEST_REJECTED, request.id())).isEqualTo(1);
    }

    @Test void demotedReviewerAndChangedRequesterCannotUseStaleGroupAuthority() throws Exception {
        var requester = create(UserRole.VIEWER); var reviewer = login(create(UserRole.OPERATOR)); var admin = create(UserRole.ADMIN);
        var request = service.create(requester.getEmail());
        administration.changeRole(admin.getEmail(), reviewer.user().getId(), UserRole.VIEWER);
        groupReview(reviewer, request.id(), "approve").andExpect(status().isForbidden());
        administration.changeRole(admin.getEmail(), requester.getId(), UserRole.NO_ACCESS);
        assertThatThrownBy(() -> service.review(admin.getEmail(), request.id(), true, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(requests.findById(request.id()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.PENDING);
    }
}
