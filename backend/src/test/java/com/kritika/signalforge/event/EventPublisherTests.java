package com.kritika.signalforge.event;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPublisherTests {

	@Mock
	private KafkaTemplate<String, EventMessage> kafkaTemplate;

	@Test
	void sendsEventToTopicWithIdAsKey() {
		EventMessage message = new EventMessage(UUID.randomUUID(), "payment-service", "API_ERROR",
				"HIGH", "Payment gateway timed out", Instant.now(), Instant.now());
		when(kafkaTemplate.send(EventPublisher.TOPIC, message.id().toString(), message))
				.thenReturn(CompletableFuture.completedFuture(null));

		new EventPublisher(kafkaTemplate).publish(message);

		verify(kafkaTemplate).send(EventPublisher.TOPIC, message.id().toString(), message);
	}
}
