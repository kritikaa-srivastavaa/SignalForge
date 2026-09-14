package com.kritika.signalforge.event;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static java.time.temporal.ChronoUnit.MICROS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventControllerIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void createsAndPersistsEvent() throws Exception {
		String request = """
				{
				  "service": "payment-service",
				  "type": "API_ERROR",
				  "severity": "HIGH",
				  "message": "Payment gateway timed out",
				  "timestamp": "2026-09-14T10:30:00Z"
				}
				""";

		String responseBody = mockMvc.perform(post("/events")
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.receivedAt").isNotEmpty())
				.andExpect(jsonPath("$.service").value("payment-service"))
				.andExpect(jsonPath("$.type").value("API_ERROR"))
				.andExpect(jsonPath("$.severity").value("HIGH"))
				.andExpect(jsonPath("$.message").value("Payment gateway timed out"))
				.andExpect(jsonPath("$.timestamp").value("2026-09-14T10:30:00Z"))
				.andReturn().getResponse().getContentAsString();
		EventResponse response = objectMapper.readValue(responseBody, EventResponse.class);

		// Force the insert and reload from PostgreSQL instead of JPA's cache.
		entityManager.flush();
		entityManager.clear();
		Event persisted = eventRepository.findById(response.id()).orElseThrow();

		assertThat(persisted.getService()).isEqualTo("payment-service");
		assertThat(persisted.getType()).isEqualTo("API_ERROR");
		assertThat(persisted.getSeverity()).isEqualTo("HIGH");
		assertThat(persisted.getMessage()).isEqualTo("Payment gateway timed out");
		assertThat(persisted.getTimestamp()).isEqualTo(Instant.parse("2026-09-14T10:30:00Z"));
		// PostgreSQL stores timestamps with microsecond precision.
		assertThat(persisted.getReceivedAt()).isCloseTo(response.receivedAt(), within(1, MICROS));
	}

	@Test
	void rejectsBlankServiceWithoutPersistingEvent() throws Exception {
		long countBefore = eventRepository.count();
		String request = """
				{
				  "service": "   ",
				  "type": "API_ERROR",
				  "severity": "HIGH",
				  "message": "Payment gateway timed out",
				  "timestamp": "2026-09-14T10:30:00Z"
				}
				""";

		mockMvc.perform(post("/events")
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isBadRequest());

		entityManager.flush();
		entityManager.clear();
		assertThat(eventRepository.count()).isEqualTo(countBefore);
	}
}
