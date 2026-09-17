package com.kritika.signalforge.event;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static java.time.temporal.ChronoUnit.MICROS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@org.springframework.security.test.context.support.WithMockUser
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

	@MockitoBean
	private EventPublisher eventPublisher;

	@MockitoBean
	private EventRateLimiter eventRateLimiter;

	@BeforeEach
	void freshRateLimitState() {
		// Delegate to a real limiter with fresh state and fixed time for every test.
		EventRateLimiter limiter = new EventRateLimiter(Clock.fixed(
				Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC));
		when(eventRateLimiter.tryAcquire(anyString())).thenAnswer(call -> limiter.tryAcquire(call.getArgument(0)));
	}

	@Test
	void limitsPostsWithoutPersistingOrPublishingRejectedRequests() throws Exception {
		String request = """
				{"service":"payment-service","type":"API_ERROR","severity":"LOW",
				 "message":"Request failed","timestamp":"2026-09-15T12:00:00Z"}
				""";
		long before = eventRepository.count();
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(http -> { http.setRemoteAddr("192.0.2.1"); return http; })
					.contentType(MediaType.APPLICATION_JSON).content(request))
					.andExpect(status().isCreated());
		}
		assertThat(eventRepository.count()).isEqualTo(before + 10);
		verify(eventPublisher, times(10)).publish(any(EventMessage.class));
		clearInvocations(eventPublisher);

		mockMvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(http -> { http.setRemoteAddr("192.0.2.1"); return http; })
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isTooManyRequests());
		entityManager.flush();
		entityManager.clear();
		assertThat(eventRepository.count()).isEqualTo(before + 10);
		verifyNoInteractions(eventPublisher);

		mockMvc.perform(get("/events").with(http -> { http.setRemoteAddr("192.0.2.1"); return http; }))
				.andExpect(status().isOk());
		mockMvc.perform(get("/incidents").with(http -> { http.setRemoteAddr("192.0.2.1"); return http; }))
				.andExpect(status().isOk());
		mockMvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(http -> { http.setRemoteAddr("192.0.2.2"); return http; })
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isCreated());
		assertThat(eventRepository.count()).isEqualTo(before + 11);
		verify(eventPublisher).publish(any(EventMessage.class));
	}

	@Test
	void returnsAllEvents() throws Exception {
		Instant timestamp = Instant.parse("2026-09-14T10:30:00Z");
		Event first = eventRepository.save(new Event("payment-service", "LIST_TEST", "HIGH",
				"Payment gateway timed out", timestamp));
		Event second = eventRepository.save(new Event("order-service", "LIST_TEST", "LOW",
				"Order received", timestamp));
		entityManager.flush();
		entityManager.clear();

		// Existing database rows and result order do not affect this assertion.
		mockMvc.perform(get("/events").param("type", "LIST_TEST"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].id", hasItems(first.getId().toString(), second.getId().toString())));
	}

	@Test
	void returnsEventById() throws Exception {
		Instant timestamp = Instant.parse("2026-09-14T10:30:00Z");
		Event event = eventRepository.saveAndFlush(new Event("payment-service", "API_ERROR", "HIGH",
				"Payment gateway timed out", timestamp));
		entityManager.clear();

		String responseBody = mockMvc.perform(get("/events/{id}", event.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(event.getId().toString()))
				.andExpect(jsonPath("$.service").value("payment-service"))
				.andExpect(jsonPath("$.type").value("API_ERROR"))
				.andExpect(jsonPath("$.severity").value("HIGH"))
				.andExpect(jsonPath("$.message").value("Payment gateway timed out"))
				.andExpect(jsonPath("$.timestamp").value(timestamp.toString()))
				.andExpect(jsonPath("$.receivedAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		EventResponse response = objectMapper.readValue(responseBody, EventResponse.class);
		assertThat(response.receivedAt()).isCloseTo(event.getReceivedAt(), within(1, MICROS));
	}

	@Test
	void returnsNotFoundForMissingEvent() throws Exception {
		mockMvc.perform(get("/events/{id}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

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

		String responseBody = mockMvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
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

		ArgumentCaptor<EventMessage> messageCaptor = ArgumentCaptor.forClass(EventMessage.class);
		verify(eventPublisher).publish(messageCaptor.capture());
		EventMessage published = messageCaptor.getValue();
		assertThat(published.id()).isEqualTo(persisted.getId());
		assertThat(published.receivedAt()).isNotNull().isEqualTo(response.receivedAt());
		assertThat(published.service()).isEqualTo("payment-service");
		assertThat(published.type()).isEqualTo("API_ERROR");
		assertThat(published.severity()).isEqualTo("HIGH");
		assertThat(published.message()).isEqualTo("Payment gateway timed out");
		assertThat(published.timestamp()).isEqualTo(Instant.parse("2026-09-14T10:30:00Z"));
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

		mockMvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isBadRequest());

		entityManager.flush();
		entityManager.clear();
		assertThat(eventRepository.count()).isEqualTo(countBefore);
		verifyNoInteractions(eventPublisher);
	}
}
