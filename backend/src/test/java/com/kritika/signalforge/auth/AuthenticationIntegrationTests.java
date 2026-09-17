package com.kritika.signalforge.auth;

import com.kritika.signalforge.event.*;
import com.kritika.signalforge.incident.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
@AutoConfigureMockMvc
class AuthenticationIntegrationTests {
    private static final String PASSWORD = "test-only-password";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired EventRepository events;
    @Autowired IncidentRepository incidents;
    @MockitoBean EventPublisher publisher;
    private String email;
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();

    @BeforeEach void setup() { email = "auth-test-" + UUID.randomUUID() + "@example.com"; }
    @AfterEach void cleanup() {
        // Delete only records created by this test, never pre-existing development data.
        incidents.deleteAllById(incidentIds);
        events.deleteAllById(eventIds);
        users.findByEmail(email).ifPresent(users::delete);
    }

    private record Client(MockHttpSession session, String token) { }

    private Client csrf(MockHttpSession session) throws Exception {
        var request = get("/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Client((MockHttpSession) result.getRequest().getSession(),
                json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }

    private ResultActions register(String address, String password, String name) throws Exception {
        Client client = csrf(null);
        return mvc.perform(post("/auth/register").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        Map.of("email", address, "password", password, "displayName", name))));
    }

    private Client login() throws Exception {
        register(email, PASSWORD, "Test User").andExpect(status().isCreated());
        Client client = csrf(null);
        String previousId = client.session().getId();
        mvc.perform(post("/auth/login").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(client.session().getId()).isNotEqualTo(previousId);
        return csrf(client.session());
    }

    @Test void registrationHashesPasswordAndReturnsOnlySafeNormalizedIdentity() throws Exception {
        String body = register("  " + email.toUpperCase(Locale.ROOT) + "  ", PASSWORD, "  Test User  ")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).size()).isEqualTo(4);
        var user = users.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(body).doesNotContain(PASSWORD, user.getPasswordHash(), "password");
    }

    @Test void registrationDoesNotAuthenticate() throws Exception {
        Client client = csrf(null);
        mvc.perform(post("/auth/register").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD, "displayName", "Test"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/auth/me").session(client.session())).andExpect(status().isUnauthorized());
    }

    @Test void databaseRejectsDuplicateNormalizedEmailCleanly() throws Exception {
        register(email, PASSWORD, "Test").andExpect(status().isCreated());
        register(" " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD, "Other")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("An account with this email already exists"));
        assertThat(users.findByEmail(email).orElseThrow().getDisplayName()).isEqualTo("Test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "", "spaces", "long", "unicode", "email", "name", "longName", "longEmail"})
    void rejectsInvalidRegistrationWithoutEchoingPassword(String kind) throws Exception {
        String password = switch (kind) {
            case "short" -> "short";
            case "" -> "";
            case "spaces" -> "        ";
            case "long" -> "a".repeat(73);
            case "unicode" -> "界".repeat(25);
            default -> PASSWORD;
        };
        String address = kind.equals("email") ? "not-an-email" : kind.equals("longEmail") ? "a".repeat(250) + "@example.com" : email;
        String name = kind.equals("name") ? "   " : kind.equals("longName") ? "a".repeat(101) : "Test";
        register(address, password, name).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").doesNotExist());
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test void loginRotatesSessionAndRestoresCurrentUser() throws Exception {
        Client client = login();
        mvc.perform(get("/auth/me").session(client.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test void wrongPasswordAndUnknownEmailHaveIdenticalGenericResponse() throws Exception {
        register(email, PASSWORD, "Test").andExpect(status().isCreated());
        List<String> responses = new ArrayList<>();
        for (String address : List.of(email, "unknown-" + email)) {
            Client client = csrf(null);
            responses.add(mvc.perform(post("/auth/login").session(client.session()).header("X-CSRF-TOKEN", client.token())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                            Map.of("email", address, "password", "wrong-password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password"))
                    .andReturn().getResponse().getContentAsString());
        }
        assertThat(responses.get(0)).isEqualTo(responses.get(1));
    }

    @Test void logoutInvalidatesSessionAndClearsCookie() throws Exception {
        Client client = login();
        mvc.perform(post("/auth/logout").session(client.session()).header("X-CSRF-TOKEN", client.token()))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("JSESSIONID", 0));
        assertThat(client.session().isInvalid()).isTrue();
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/me", "/events", "/events/00000000-0000-0000-0000-000000000001",
            "/incidents", "/incidents/00000000-0000-0000-0000-000000000001", "/actuator/info"})
    void anonymousReadsReturnJson401(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test void anonymousMutationsReturn401EvenWithoutCsrf() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        for (String action : List.of("acknowledge", "resolve")) {
            mvc.perform(patch("/incidents/" + UUID.randomUUID() + "/" + action)).andExpect(status().isUnauthorized());
        }
    }

    @Test void authenticatedMutationRequiresValidCsrfAndPersistsSubmittedData() throws Exception {
        Client client = login();
        String body = json.writeValueAsString(Map.of("service", email, "type", "AUTH_TEST", "severity", "HIGH",
                "message", "Authenticated event", "timestamp", "2026-09-17T10:00:00Z"));
        mvc.perform(post("/events").session(client.session()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/events").session(client.session()).header("X-CSRF-TOKEN", "invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String result = mvc.perform(post("/events").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receivedAt").isNotEmpty()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(result).get("id").asText());
        eventIds.add(id);
        assertThat(events.findById(id).orElseThrow().getMessage()).isEqualTo("Authenticated event");
        mvc.perform(get("/events/" + id).session(client.session())).andExpect(status().isOk()).andExpect(jsonPath("$.service").value(email));
        mvc.perform(get("/events").session(client.session())).andExpect(status().isOk());
    }

    @Test void authenticatedIncidentReadsAndLifecycleWork() throws Exception {
        Client client = login();
        Incident incident = incidents.saveAndFlush(new Incident(UUID.randomUUID(), email, "AUTH_TEST", "HIGH", "Auth test"));
        incidentIds.add(incident.getId());
        String path = "/incidents/" + incident.getId();
        mvc.perform(get("/incidents").session(client.session())).andExpect(status().isOk());
        mvc.perform(get(path).session(client.session())).andExpect(status().isOk());
        mvc.perform(patch(path + "/acknowledge").session(client.session())).andExpect(status().isForbidden());
        mvc.perform(patch(path + "/acknowledge").session(client.session()).header("X-CSRF-TOKEN", client.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(patch(path + "/resolve").session(client.session()).header("X-CSRF-TOKEN", client.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/login", "/auth/register", "/auth/logout"})
    void authMutationsAlsoRequireCsrf(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test void loginInvalidatesPreAuthenticationCsrfToken() throws Exception {
        register(email, PASSWORD, "Test").andExpect(status().isCreated());
        Client client = csrf(null);
        mvc.perform(post("/auth/login").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/logout").session(client.session()).header("X-CSRF-TOKEN", client.token()))
                .andExpect(status().isForbidden());
        Client refreshed = csrf(client.session());
        mvc.perform(post("/auth/logout").session(refreshed.session()).header("X-CSRF-TOKEN", refreshed.token()))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/prometheus"})
    void infrastructureRemainsPublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test void credentialedCorsPermitsOnlyExplicitOriginAndRequiredHeaders() throws Exception {
        mvc.perform(options("/auth/login").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/auth/login").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
        mvc.perform(options("/auth/login").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-Unrelated"))
                .andExpect(status().isForbidden());
    }

    @Test void malformedAuthJsonReturnsSafeValidationError() throws Exception {
        Client client = csrf(null);
        mvc.perform(post("/auth/login").session(client.session()).header("X-CSRF-TOKEN", client.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":invalid-sensitive-value}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-sensitive-value"))));
    }

    @Test void passwordDtosHaveRedactedLogRepresentation() {
        assertThat(new RegisterRequest(email, PASSWORD, "Test").toString()).doesNotContain(PASSWORD);
        assertThat(new LoginRequest(email, PASSWORD).toString()).doesNotContain(PASSWORD);
    }
}
