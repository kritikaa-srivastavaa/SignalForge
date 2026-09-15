package com.kritika.signalforge.incident;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static java.time.temporal.ChronoUnit.MICROS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
@Transactional
class IncidentControllerIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void returnsAllIncidents() throws Exception {
		Incident first = incidentRepository.save(new Incident(UUID.randomUUID(), "payment-service",
				"LIST_TEST", "HIGH", "High severity event detected in payment-service"));
		Incident second = incidentRepository.save(new Incident(UUID.randomUUID(), "order-service",
				"LIST_TEST", "high", "High severity event detected in order-service"));
		entityManager.flush();
		entityManager.clear();

		String body = mockMvc.perform(get("/incidents").param("type", "LIST_TEST"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		IncidentResponse[] responses = objectMapper.treeToValue(objectMapper.readTree(body).get("content"), IncidentResponse[].class);

		// Check our rows without assuming an empty database or a particular order.
		IncidentResponse firstResponse = java.util.Arrays.stream(responses)
				.filter(response -> response.id().equals(first.getId())).findFirst().orElseThrow();
		IncidentResponse secondResponse = java.util.Arrays.stream(responses)
				.filter(response -> response.id().equals(second.getId())).findFirst().orElseThrow();
		assertResponseMatches(firstResponse, first);
		assertResponseMatches(secondResponse, second);
	}

	@Test
	void returnsIncidentById() throws Exception {
		Incident incident = incidentRepository.saveAndFlush(new Incident(UUID.randomUUID(), "payment-service",
				"API_ERROR", "HIGH", "High severity event detected in payment-service"));
		entityManager.clear();

		String body = mockMvc.perform(get("/incidents/{id}", incident.getId()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		IncidentResponse response = objectMapper.readValue(body, IncidentResponse.class);
		assertResponseMatches(response, incident);
	}

	@Test
	void returnsNotFoundForMissingIncident() throws Exception {
		mockMvc.perform(get("/incidents/{id}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	private void assertResponseMatches(IncidentResponse response, Incident incident) {
		assertThat(response.id()).isEqualTo(incident.getId());
		assertThat(response.sourceEventId()).isEqualTo(incident.getSourceEventId());
		assertThat(response.service()).isEqualTo(incident.getService());
		assertThat(response.type()).isEqualTo(incident.getType());
		assertThat(response.severity()).isEqualTo(incident.getSeverity());
		assertThat(response.title()).isEqualTo(incident.getTitle());
		assertThat(response.status()).isEqualTo("OPEN");
		// PostgreSQL stores timestamps with microsecond precision.
		assertThat(response.createdAt()).isCloseTo(incident.getCreatedAt(), within(1, MICROS));
	}
}
