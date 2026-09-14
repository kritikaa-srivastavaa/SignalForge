package com.kritika.signalforge.incident;

import java.time.Instant;
import java.util.UUID;

import com.kritika.signalforge.event.EventMessage;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Transactional
class IncidentDetectionServiceIntegrationTests {

	@Autowired
	private IncidentDetectionService detectionService;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private EntityManager entityManager;

	@ParameterizedTest
	@ValueSource(strings = {"HIGH", "high", "HiGh"})
	void persistsIncidentForHighSeverity(String severity) {
		EventMessage event = event(severity);
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
		assertThat(reloaded.getTitle()).isEqualTo("High severity event detected in payment-service");
		assertThat(reloaded.getCreatedAt()).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"MEDIUM", "LOW"})
	void ignoresNonHighSeverity(String severity) {
		long countBefore = incidentRepository.count();
		assertThat(detectionService.process(event(severity))).isEmpty();
		entityManager.flush();
		entityManager.clear();
		assertThat(incidentRepository.count()).isEqualTo(countBefore);
	}

	private EventMessage event(String severity) {
		return new EventMessage(UUID.randomUUID(), "payment-service", "API_ERROR", severity,
				"Payment gateway timed out", Instant.parse("2026-09-14T10:30:00Z"), Instant.now());
	}
}
