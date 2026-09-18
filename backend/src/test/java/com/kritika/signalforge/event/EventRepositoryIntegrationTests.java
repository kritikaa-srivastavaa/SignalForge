package com.kritika.signalforge.event;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static java.time.temporal.ChronoUnit.MICROS;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@Transactional
class EventRepositoryIntegrationTests {

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void savesAndReloadsEvent() {
		String service = "payment-service";
		String type = "PAYMENT_FAILED";
		String severity = "ERROR";
		String message = "Payment provider timeout. ".repeat(30);
		Instant timestamp = Instant.parse("2026-09-14T08:30:00.123456Z");
		Event event = new Event(service, type, severity, message, timestamp);

		Event saved = eventRepository.saveAndFlush(event);

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getReceivedAt()).isNotNull();

		// Clear JPA's cache so findById must read the row from PostgreSQL.
		entityManager.clear();
		Event reloaded = eventRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded).isNotSameAs(saved);
		assertThat(reloaded.getId()).isEqualTo(saved.getId());
		assertThat(reloaded.getService()).isEqualTo(service);
		assertThat(reloaded.getType()).isEqualTo(type);
		assertThat(reloaded.getSeverity()).isEqualTo(severity);
		assertThat(reloaded.getMessage()).isEqualTo(message);
		assertThat(reloaded.getTimestamp()).isEqualTo(timestamp);
		// PostgreSQL stores microseconds; Instant.now() can include nanoseconds.
		assertThat(reloaded.getReceivedAt()).isCloseTo(saved.getReceivedAt(), within(1, MICROS));
	}
}
