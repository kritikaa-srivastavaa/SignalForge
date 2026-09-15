package com.kritika.signalforge.event.processing;
import com.kritika.signalforge.observability.SignalForgeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.UUID;

import com.kritika.signalforge.event.EventMessage;
import com.kritika.signalforge.incident.IncidentDetectionService;
import com.kritika.signalforge.incident.IncidentRepository;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@Transactional
class EventProcessingSpikeIntegrationTests {

	@Autowired
	private ProcessedEventRepository processedEvents;

	@Autowired
	private IncidentRepository incidents;

	@Autowired
	private EntityManager entityManager;

	private EventProcessingService processingService;
 private final SignalForgeMetrics metrics = new SignalForgeMetrics(new SimpleMeterRegistry());

	@BeforeEach
	void freshWindowState() {
		// The test transaction covers both repositories; each test has a fresh rolling window.
		processingService = new EventProcessingService(processedEvents, new IncidentDetectionService(incidents, metrics), metrics);
	}

	@Test
	void duplicateCannotContributeToThresholdOrRepeatCrossing() {
		long incidentCount = incidents.count();
		long processedCount = processedEvents.count();
		EventMessage first = event(1, 0);
		EventMessage second = event(2, 20);
		EventMessage third = event(3, 40);

		processingService.process(first);
		processingService.process(second);
		processingService.process(second);
		entityManager.flush();
		assertThat(incidents.count()).isEqualTo(incidentCount);

		processingService.process(third);
		entityManager.flush();
		assertThat(incidents.count()).isEqualTo(incidentCount + 1);
		processingService.process(third);
		entityManager.flush();
		entityManager.clear();
		assertThat(incidents.count()).isEqualTo(incidentCount + 1);
		assertThat(processedEvents.count()).isEqualTo(processedCount + 3);
	}

	private EventMessage event(long id, long seconds) {
		Instant time = Instant.parse("2026-09-14T12:00:00Z").plusSeconds(seconds);
		return new EventMessage(new UUID(901, id), "unique-spike-test", "API_ERROR", "LOW",
				"Request failed", time, time);
	}
}
