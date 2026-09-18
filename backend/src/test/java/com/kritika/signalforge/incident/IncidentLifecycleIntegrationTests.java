package com.kritika.signalforge.incident;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
@AutoConfigureMockMvc
@Transactional
class IncidentLifecycleIntegrationTests {
	@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.junit.jupiter.api.BeforeEach void actor() {
        jdbc.update("INSERT INTO app_users(id,email,password_hash,display_name,created_at,role) VALUES (?, 'user', 'test-only', 'Lifecycle Test', now(), 'OPERATOR')", UUID.randomUUID());
    }
    @Autowired private MockMvc mvc;
	@Autowired private IncidentRepository repository;
	@Autowired private EntityManager entityManager;

	@ParameterizedTest
	@CsvSource({
		"OPEN, acknowledge, ACKNOWLEDGED",
		"ACKNOWLEDGED, acknowledge, ACKNOWLEDGED",
		"OPEN, resolve, RESOLVED",
		"ACKNOWLEDGED, resolve, RESOLVED",
		"RESOLVED, resolve, RESOLVED"
	})
	void allowedAndIdempotentTransitions(IncidentStatus initial, String action, IncidentStatus expected) throws Exception {
		Incident incident = fixture(initial);
		mvc.perform(patch("/incidents/{id}/{action}", incident.getId(), action).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expected.name()))
				.andExpect(jsonPath("$.version").doesNotExist());
		entityManager.flush();
		entityManager.clear();
		assertThat(repository.findById(incident.getId()).orElseThrow().getStatus()).isEqualTo(expected);
	}

	@Test
	void resolvedCannotBeAcknowledged() throws Exception {
		Incident incident = fixture(IncidentStatus.RESOLVED);
		mvc.perform(patch("/incidents/{id}/acknowledge", incident.getId()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isConflict());
		entityManager.clear();
		assertThat(repository.findById(incident.getId()).orElseThrow().getStatus()).isEqualTo(IncidentStatus.RESOLVED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"acknowledge", "resolve"})
	void missingIncidentReturns404(String action) throws Exception {
		mvc.perform(patch("/incidents/{id}/{action}", UUID.randomUUID(), action).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNotFound());
	}

	@ParameterizedTest
	@EnumSource(IncidentStatus.class)
	void filtersByStatus(IncidentStatus expected) throws Exception {
		String service = "lifecycle-" + UUID.randomUUID();
		UUID expectedId = null;
		for (IncidentStatus state : IncidentStatus.values()) {
			Incident incident = new Incident(UUID.randomUUID(), service, "API_ERROR", "LOW", "Test");
			incident.changeStatus(state);
			repository.saveAndFlush(incident);
			if (state == expected) expectedId = incident.getId();
		}
		entityManager.clear();
		mvc.perform(get("/incidents").param("service", service).param("status", expected.name()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(expectedId.toString()))
				.andExpect(jsonPath("$.content[0].status").value(expected.name()));
	}

	@Test
	void invalidStatusReturns400() throws Exception {
		mvc.perform(get("/incidents").param("status", "INVALID")).andExpect(status().isBadRequest());
	}

	private Incident fixture(IncidentStatus state) {
		Incident incident = new Incident(UUID.randomUUID(), "lifecycle-test", "API_ERROR", "LOW", "Test");
		incident.changeStatus(state);
		return repository.saveAndFlush(incident);
	}
}
