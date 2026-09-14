package com.kritika.signalforge.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.kritika.signalforge.event.processing.EventProcessingService;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventConsumerTests {

	private final EventProcessingService processingService = mock(EventProcessingService.class);

	@Test
	void propagatesProcessingFailure() {
		EventMessage event = exampleEvent();
		RuntimeException failure = new RuntimeException("Test processing failure");
		doThrow(failure).when(processingService).process(event);
		assertThatThrownBy(() -> new EventConsumer(processingService).consume(event)).isSameAs(failure);
	}

	@Test
	void acceptsEventMessage() {
		EventMessage event = exampleEvent();
		assertThatCode(() -> new EventConsumer(processingService).consume(event)).doesNotThrowAnyException();
		verify(processingService).process(event);
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
			assertThatCode(() -> new EventConsumer(processingService).consume(received)).doesNotThrowAnyException();
			verify(processingService).process(received);
		}
	}

	private EventMessage exampleEvent() {
		return new EventMessage(UUID.randomUUID(), "payment-service", "API_ERROR", "HIGH",
				"Payment gateway timed out", Instant.parse("2026-09-14T10:30:00Z"),
				Instant.parse("2026-09-14T10:30:01.123456Z"));
	}
}
