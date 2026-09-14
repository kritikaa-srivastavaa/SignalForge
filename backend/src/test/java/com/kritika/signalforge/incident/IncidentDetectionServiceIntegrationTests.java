package com.kritika.signalforge.incident;

import java.time.Instant;
import java.util.UUID;

import com.kritika.signalforge.event.EventMessage;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Transactional
class IncidentDetectionServiceIntegrationTests {

	private IncidentDetectionService detectionService;
	private long countBefore;
	private static final Instant BASE = Instant.parse("2026-09-14T12:00:00Z");

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void freshState() {
		// Fresh in-memory state; the surrounding test transaction rolls back database writes.
		detectionService = new IncidentDetectionService(incidentRepository);
		countBefore = incidentRepository.count();
	}

	@ParameterizedTest
	@ValueSource(strings = {"HIGH", "LOW", "MEDIUM"})
	void persistsIncidentAtThresholdRegardlessOfSeverity(String severity) {
		assertThat(detectionService.process(event(0, severity))).isEmpty();
		assertThat(detectionService.process(event(20, severity))).isEmpty();
		EventMessage event = event(40, severity);
		Incident created = detectionService.process(event).orElseThrow();
		assertThat(created.getId()).isNotNull();
		assertThat(created.getCreatedAt()).isNotNull();

		entityManager.flush();
		entityManager.clear();
		Incident reloaded = incidentRepository.findById(created.getId()).orElseThrow();
		assertThat(reloaded).isNotSameAs(created);
		assertThat(reloaded.getSourceEventId()).isEqualTo(event.id());
		assertThat(reloaded.getService()).isEqualTo(event.service());
		assertThat(reloaded.getType()).isEqualTo(event.type());
		assertThat(reloaded.getSeverity()).isEqualTo(severity);
		assertThat(reloaded.getStatus()).isEqualTo("OPEN");
		assertThat(reloaded.getTitle()).isEqualTo("Event spike detected for API_ERROR in payment-service");
		assertThat(reloaded.getCreatedAt()).isNotNull();
		assertCount(1);
	}

	@Test
	void doesNotTriggerBelowThreshold() {
		assertThat(detectionService.process(event(0, "HIGH"))).isEmpty();
		assertThat(detectionService.process(event(20, "HIGH"))).isEmpty();
		assertCount(0);
	}

	@Test
	void excludesEventsOutsideWindow() {
		detectionService.process(event(0, "HIGH"));
		detectionService.process(event(20, "HIGH"));
		assertThat(detectionService.process(event(120, "HIGH"))).isEmpty();
		assertCount(0);
	}

	@Test
	void separatesServices() {
		detectionService.process(event(0, "LOW"));
		detectionService.process(event(20, "LOW"));
		assertThat(detectionService.process(event(40, "LOW", "order-service", "API_ERROR"))).isEmpty();
		assertCount(0);
	}

	@Test
	void separatesTypes() {
		detectionService.process(event(0, "LOW"));
		detectionService.process(event(20, "LOW"));
		assertThat(detectionService.process(event(40, "LOW", "payment-service", "TIMEOUT"))).isEmpty();
		assertCount(0);
	}

	@Test
	void suppressesIncidentsUntilCooldownExpires() {
		detectionService.process(event(0, "LOW"));
		detectionService.process(event(20, "MEDIUM"));
		assertThat(detectionService.process(event(40, "HIGH")).orElseThrow().getSeverity()).isEqualTo("HIGH");
		assertThat(detectionService.process(event(41, "LOW"))).isEmpty();
		assertCount(1);
		assertThat(detectionService.process(event(80, "LOW"))).isEmpty();
		assertThat(detectionService.process(event(99, "MEDIUM"))).isEmpty();
		EventMessage crossing = event(100, "MEDIUM");
		Incident second = detectionService.process(crossing).orElseThrow();
		assertThat(second.getSourceEventId()).isEqualTo(crossing.id());
		assertThat(second.getSeverity()).isEqualTo("MEDIUM");
		assertCount(2);
	}

	@Test
	void includesEventsExactlySixtySecondsOld() {
		detectionService.process(event(0, "LOW"));
		detectionService.process(event(30, "MEDIUM"));
		assertThat(detectionService.process(event(60, "LOW"))).isPresent();
		assertCount(1);
	}

	@Test
	void doesNotCountFutureEventsInEarlierWindow() {
		detectionService.process(event(120, "LOW"));
		detectionService.process(event(130, "LOW"));
		assertThat(detectionService.process(event(0, "LOW"))).isEmpty();
		assertCount(0);
	}

	private void assertCount(long added) {
		entityManager.flush();
		entityManager.clear();
		assertThat(incidentRepository.count()).isEqualTo(countBefore + added);
	}

	private EventMessage event(long seconds, String severity) {
		return event(seconds, severity, "payment-service", "API_ERROR");
	}

	private EventMessage event(long seconds, String severity, String service, String type) {
		// Identical receivedAt values ensure the occurrence timestamp determines the window.
		return new EventMessage(UUID.randomUUID(), service, type, severity,
				"Payment gateway timed out", BASE.plusSeconds(seconds), BASE.plusSeconds(3600));
	}
}
