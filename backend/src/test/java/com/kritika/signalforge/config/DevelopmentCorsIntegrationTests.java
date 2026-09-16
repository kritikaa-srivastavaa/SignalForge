package com.kritika.signalforge.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
class DevelopmentCorsIntegrationTests {
    @Autowired private MockMvc mvc;

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
    void allowsReadPreflightButNotLifecycleWrites() throws Exception {
        mvc.perform(options("/incidents").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", "GET"));
        mvc.perform(options("/events").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
