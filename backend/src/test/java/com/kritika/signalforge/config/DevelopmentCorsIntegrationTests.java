package com.kritika.signalforge.config;

import java.util.UUID;
import com.kritika.signalforge.incident.Incident;
import com.kritika.signalforge.incident.IncidentRepository;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
@Transactional
class DevelopmentCorsIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private IncidentRepository repository;

    @Test
    void allowsLocalDashboardToReadBothCollections() throws Exception {
        for (String path : new String[] {"/events", "/incidents"}) {
            mvc.perform(get(path).header("Origin", "http://localhost:5173"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
    }

    @Test
    void rejectsOtherOrigins() throws Exception {
        mvc.perform(get("/events").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void allowsCollectionReadPreflightButNotEventCreation() throws Exception {
        mvc.perform(options("/incidents").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", "GET"));
        mvc.perform(options("/events").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
    @Test
    void allowsDetailAndLifecyclePreflights() throws Exception {
        String path = "/incidents/" + UUID.randomUUID();
        for (String suffix : new String[] {"", "/acknowledge", "/resolve"}) {
            String method = suffix.isEmpty() ? "GET" : "PATCH";
            mvc.perform(options(path + suffix).header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", method))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().string("Access-Control-Allow-Methods", method));
        }
    }

    @Test
    void allowsActualDetailAndBothLifecycleActions() throws Exception {
        Incident incident = repository.saveAndFlush(new Incident(UUID.randomUUID(),
                "cors-test", "API_ERROR", "HIGH", "CORS test"));
        String path = "/incidents/" + incident.getId();
        mvc.perform(get(path).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(patch(path + "/acknowledge").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(patch(path + "/resolve").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(patch(path + "/acknowledge").header("Origin", "http://localhost:5173"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void rejectsUntrustedDetailAndLifecycleRequests() throws Exception {
        String path = "/incidents/" + UUID.randomUUID();
        mvc.perform(get(path).header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        for (String action : new String[] {"acknowledge", "resolve"}) {
            mvc.perform(options(path + "/" + action).header("Origin", "https://untrusted.example")
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(patch(path + "/" + action).header("Origin", "https://untrusted.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void rejectsUnrelatedMethodsAndPaths() throws Exception {
        String path = "/incidents/" + UUID.randomUUID();
        for (String method : new String[] {"POST", "PUT", "DELETE"}) {
            mvc.perform(options(path + "/resolve").header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", method))
                    .andExpect(status().isForbidden());
        }
        for (String unrelated : new String[] {"/events", "/incidents", path}) {
            mvc.perform(options(unrelated).header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void unrelatedPathsDoNotGrantCorsPermission() throws Exception {
        // OPTIONS on an unmapped route can be 200; without CORS headers the browser rejects it.
        for (String path : new String[] {"/incidents/" + UUID.randomUUID() + "/other", "/actuator/health"}) {
            mvc.perform(options(path).header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Methods"));
        }
    }

    @Test
    void missingDetailStillIncludesCorsHeader() throws Exception {
        mvc.perform(get("/incidents/" + UUID.randomUUID()).header("Origin", "http://localhost:5173"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}