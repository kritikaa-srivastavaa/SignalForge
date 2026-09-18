package com.kritika.signalforge.event.processing;

import java.time.Instant;
import java.util.UUID;

import com.kritika.signalforge.event.EventMessage;
import com.kritika.signalforge.incident.IncidentDetectionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@Transactional
class EventProcessingServiceIntegrationTests {

	@Autowired
	private EventProcessingService processingService;

	@Autowired
	private ProcessedEventRepository repository;

	@MockitoBean
	private IncidentDetectionService detectionService;

	@Test
	void firstDeliveryRegistersAndDelegates() {
		EventMessage event = event(1);
		processingService.process(event);
		assertThat(repository.findById(event.id()).orElseThrow().getProcessedAt()).isNotNull();
		verify(detectionService).process(event);
	}

	@Test
	void duplicateDeliverySkipsDetection() {
		long before = repository.count();
		EventMessage event = event(2);
		processingService.process(event);
		processingService.process(event);
		assertThat(repository.count()).isEqualTo(before + 1);
		verify(detectionService, times(1)).process(event);
	}

	@Test
	void differentIdsWithIdenticalPayloadsAreProcessed() {
		long before = repository.count();
		EventMessage first = event(3);
		EventMessage second = event(4);
		processingService.process(first);
		processingService.process(second);
		verify(detectionService).process(first);
		verify(detectionService).process(second);
		assertThat(repository.count()).isEqualTo(before + 2);
	}

	@Test
	void databaseRejectsDuplicateRegistrationWithoutError() {
		long before = repository.count();
		UUID id = event(5).id();
		Instant time = Instant.parse("2026-09-14T12:00:00Z");
		assertThat(repository.registerIfAbsent(id, time)).isEqualTo(1);
		assertThat(repository.registerIfAbsent(id, time.plusSeconds(1))).isZero();
		assertThat(repository.count()).isEqualTo(before + 1);
		assertThat(repository.findById(id).orElseThrow().getProcessedAt()).isEqualTo(time);
	}

	@Test
	void processingFailureRollsBackMarker() {
		EventMessage event = event(6);
		doThrow(new IllegalStateException("Processing failed")).when(detectionService).process(event);
		assertThatThrownBy(() -> processingService.process(event)).isInstanceOf(IllegalStateException.class);
		TestTransaction.flagForRollback();
		TestTransaction.end();
		assertThat(repository.existsById(event.id())).isFalse();
		reset(detectionService);
		TestTransaction.start();
		processingService.process(event);
		assertThat(repository.existsById(event.id())).isTrue();
		verify(detectionService).process(event);
	}

	private EventMessage event(long id) {
		Instant time = Instant.parse("2026-09-14T12:00:00Z");
		return new EventMessage(new UUID(900, id), "idempotency-test", "API_ERROR", "LOW",
				"Request failed", time, time);
	}
}
