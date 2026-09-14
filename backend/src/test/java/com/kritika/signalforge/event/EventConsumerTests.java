package com.kritika.signalforge.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.kritika.signalforge.incident.IncidentDetectionService;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventConsumerTests {

	private final IncidentDetectionService detectionService = mock(IncidentDetectionService.class);

	@Test
	void acceptsEventMessage() {
		EventMessage event = exampleEvent();
		assertThatCode(() -> new EventConsumer(detectionService).consume(event)).doesNotThrowAnyException();
		verify(detectionService).process(event);
	}

	@Test
	void deserializesAllFieldsBeforeConsumption() {
		EventMessage original = exampleEvent();
		try (var serializer = new JacksonJsonSerializer<EventMessage>();
				var deserializer = new JacksonJsonDeserializer<EventMessage>()) {
			deserializer.configure(Map.of(
					JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.kritika.signalforge.event",
					JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, EventMessage.class.getName(),
					JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false), false);

			byte[] json = serializer.serialize(EventPublisher.TOPIC, original);
			EventMessage received = deserializer.deserialize(EventPublisher.TOPIC, json);

			// Record equality checks every field, including UUID and both Instant values.
			assertThat(received).isEqualTo(original);
			assertThatCode(() -> new EventConsumer(detectionService).consume(received)).doesNotThrowAnyException();
			verify(detectionService).process(received);
		}
	}

	private EventMessage exampleEvent() {
		return new EventMessage(UUID.randomUUID(), "payment-service", "API_ERROR", "HIGH",
				"Payment gateway timed out", Instant.parse("2026-09-14T10:30:00Z"),
				Instant.parse("2026-09-14T10:30:01.123456Z"));
	}
}
