package com.kritika.signalforge.auth;

import com.kritika.signalforge.event.*;
import com.kritika.signalforge.incident.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
class RbacIntegrationTests {
    private static final String PASSWORD = "rbac-test-password";
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired UserAdministrationService administration;
    @Autowired BootstrapAdminService bootstrap;
    @Autowired IncidentRepository incidents;
    @Autowired EventRepository events;
    @MockitoBean EventPublisher publisher;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();
    private record Client(AppUser user, MockHttpSession session, String token) { }

    @AfterEach void cleanup() {
        for (UUID id : userIds) jdbc.update("DELETE FROM audit_records WHERE actor_id = ?", id);
        incidents.deleteAllById(incidentIds); events.deleteAllById(eventIds); users.deleteAllById(userIds);
    }
    private AppUser create(UserRole role) {
        var user = new AppUser("rbac-test-" + UUID.randomUUID() + "@example.com", passwords.encode(PASSWORD), "RBAC Test");
        user.changeRole(role);
        users.saveAndFlush(user); userIds.add(user.getId()); return user;
    }
    private Client login(AppUser user) throws Exception {
        var csrf = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession();
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(user.getRole().name()));
        token = json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).get("token").asText();
        return new Client(user, session, token);
    }
    private ResultActions role(Client actor, UUID target, String value) throws Exception {
        return mvc.perform(patch("/admin/users/" + target + "/role").session(actor.session()).header("X-CSRF-TOKEN", actor.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":" + value + "}"));
    }
    private Incident incident() {
        var incident = incidents.saveAndFlush(new Incident(UUID.randomUUID(), "rbac-test", "RBAC_TEST", "HIGH", "RBAC test"));
        incidentIds.add(incident.getId()); return incident;
    }

    @ParameterizedTest @EnumSource(UserRole.class)
    void everyRoleReadsEventsAndIncidentsAndRestoresCurrentRole(UserRole role) throws Exception {
        var client = login(create(role)); var incident = incident();
        var event = events.saveAndFlush(new Event("rbac-test", "RBAC_TEST", "HIGH", "Read test", Instant.now()));
        eventIds.add(event.getId());
        for (String path : List.of("/events", "/events/" + event.getId(), "/incidents", "/incidents/" + incident.getId())) {
            mvc.perform(get(path).session(client.session())).andExpect(status().isOk());
        }
        mvc.perform(get("/auth/me").session(client.session())).andExpect(jsonPath("$.role").value(role.name()));
        assertThat(users.findById(client.user().getId()).orElseThrow().getRole()).isEqualTo(role);
    }

    @ParameterizedTest @EnumSource(UserRole.class)
    void lifecyclePermissionsAreEnforcedWithValidCsrf(UserRole role) throws Exception {
        var client = login(create(role)); var incident = incident();
        for (String action : List.of("acknowledge", "resolve")) {
            var result = mvc.perform(patch("/incidents/" + incident.getId() + "/" + action)
                    .session(client.session()).header("X-CSRF-TOKEN", client.token()));
            if (role == UserRole.VIEWER) result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            else result.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(action.equals("resolve") ? "RESOLVED" : "ACKNOWLEDGED"));
        }
        assertThat(incidents.findById(incident.getId()).orElseThrow().getStatus())
                .isEqualTo(role == UserRole.VIEWER ? IncidentStatus.OPEN : IncidentStatus.RESOLVED);
    }

    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"VIEWER", "OPERATOR"})
    void nonAdminsCannotListOrChangeTheirOwnOrAnotherRole(UserRole role) throws Exception {
        var actor = login(create(role)); var target = create(UserRole.VIEWER);
        mvc.perform(get("/admin/users").session(actor.session())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        role(actor, actor.user().getId(), "\"ADMIN\"").andExpect(status().isForbidden());
        role(actor, target.getId(), "\"ADMIN\"").andExpect(status().isForbidden());
        assertThat(users.findById(actor.user().getId()).orElseThrow().getRole()).isEqualTo(role);
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(UserRole.VIEWER);
    }

    @Test void adminListsOnlySafeDataAndChangesRoles() throws Exception {
        var admin = login(create(UserRole.ADMIN)); var target = create(UserRole.VIEWER);
        mvc.perform(get("/admin/users").session(admin.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].passwordHash").isEmpty()).andExpect(jsonPath("$[*].password").isEmpty());
        for (UserRole role : List.of(UserRole.OPERATOR, UserRole.VIEWER, UserRole.ADMIN)) {
            role(admin, target.getId(), "\"" + role + "\"").andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role.name()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"\"SUPERADMIN\"", "\"ROOT\"", "\"\"", "null", "1", "\"ROLE_ADMIN\""})
    void invalidRoleValuesReturn400(String value) throws Exception {
        var admin = login(create(UserRole.ADMIN)); var target = create(UserRole.VIEWER);
        role(admin, target.getId(), value).andExpect(status().isBadRequest());
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(UserRole.VIEWER);
    }

    @Test void missingTargetReturns404() throws Exception {
        role(login(create(UserRole.ADMIN)), UUID.randomUUID(), "\"VIEWER\"").andExpect(status().isNotFound());
    }

    @Test void anonymousAdminEndpointsReturn401IncludingMutationWithoutCsrf() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/admin/users/" + UUID.randomUUID() + "/role")).andExpect(status().isUnauthorized());
    }

    @Test void csrfFailureIsDistinctFromInsufficientPermissions() throws Exception {
        var admin = login(create(UserRole.ADMIN));
        mvc.perform(patch("/admin/users/" + admin.user().getId() + "/role").session(admin.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "OPERATOR"})
    void registrationCannotMassAssignAnElevatedRole(String requested) throws Exception {
        String email = "rbac-test-" + UUID.randomUUID() + "@example.com";
        var csrf = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) csrf.getRequest().getSession();
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        try {
            mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                            "email", email, "password", PASSWORD, "displayName", "Test", "role", requested))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("VIEWER"));
            assertThat(users.findByEmail(email).orElseThrow().getRole()).isEqualTo(UserRole.VIEWER);
        } finally { users.findByEmail(email).ifPresent(user -> userIds.add(user.getId())); }
    }

    @Test void demotionAndPromotionAffectAnAlreadyAuthenticatedSession() throws Exception {
        var admin = login(create(UserRole.ADMIN)); var active = login(create(UserRole.ADMIN));
        role(admin, active.user().getId(), "\"VIEWER\"").andExpect(status().isOk());
        mvc.perform(get("/admin/users").session(active.session())).andExpect(status().isForbidden());
        mvc.perform(get("/auth/me").session(active.session())).andExpect(jsonPath("$.role").value("VIEWER"));
        role(active, admin.user().getId(), "\"VIEWER\"").andExpect(status().isForbidden());
        role(admin, active.user().getId(), "\"OPERATOR\"").andExpect(status().isOk());
        var incident = incident();
        mvc.perform(patch("/incidents/" + incident.getId() + "/acknowledge").session(active.session())
                .header("X-CSRF-TOKEN", active.token())).andExpect(status().isOk());
    }

    @Test void adminCanDemoteSelfWhenAnotherAdminRemainsWithoutLosingSession() throws Exception {
        var self = login(create(UserRole.ADMIN)); create(UserRole.ADMIN);
        role(self, self.user().getId(), "\"VIEWER\"").andExpect(status().isOk());
        mvc.perform(get("/auth/me").session(self.session())).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"));
        mvc.perform(get("/admin/users").session(self.session())).andExpect(status().isForbidden());
    }

    @Test void concurrentCrossDemotionsRecheckTheActorAfterTheTransactionLock() throws Exception {
        var a = create(UserRole.ADMIN); var b = create(UserRole.ADMIN);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (var pair : List.of(List.of(a, b), List.of(b, a))) {
                attempts.add(pool.submit(() -> {
                    ready.countDown(); start.await();
                    try { administration.changeRole(pair.get(0).getEmail(), pair.get(1).getId(), UserRole.VIEWER); return true; }
                    catch (AccessDeniedException expected) { return false; }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            int successes = 0;
            for (var attempt : attempts) if (attempt.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(List.of(users.findById(a.getId()).orElseThrow().getRole(), users.findById(b.getId()).orElseThrow().getRole()))
                    .containsExactlyInAnyOrder(UserRole.ADMIN, UserRole.VIEWER);
        }
    }

    @Test void bootstrapIsIdempotentAndDoesNotResetCredentials() {
        String email = "rbac-bootstrap-" + UUID.randomUUID() + "@example.com";
        try {
            bootstrap.bootstrap(email, PASSWORD, "Bootstrap Test");
            var first = users.findByEmail(email).orElseThrow();
            bootstrap.bootstrap(email, PASSWORD, "Changed name ignored");
            var second = users.findByEmail(email).orElseThrow();
            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(second.getRole()).isEqualTo(UserRole.ADMIN);
            assertThat(second.getPasswordHash()).isEqualTo(first.getPasswordHash());
            assertThat(second.getDisplayName()).isEqualTo("Bootstrap Test");
        } finally { users.findByEmail(email).ifPresent(user -> userIds.add(user.getId())); }
    }

    @Test void bootstrapRefusesToClaimAnExistingPublicAccount() {
        var viewer = create(UserRole.VIEWER);
        assertThatThrownBy(() -> bootstrap.bootstrap(viewer.getEmail(), PASSWORD, "Bootstrap"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(viewer.getId()).orElseThrow().getRole()).isEqualTo(UserRole.VIEWER);
    }

    @Test void bootstrapRejectsInvalidConfigurationWithoutLeakingPassword() {
        assertThatThrownBy(() -> bootstrap.bootstrap("bad-email", "secret", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
    }
}
