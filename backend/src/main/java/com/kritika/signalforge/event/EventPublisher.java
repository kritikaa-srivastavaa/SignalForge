package com.kritika.signalforge.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventPublisher {

	public static final String TOPIC = "signalforge.events";

	private final KafkaTemplate<String, EventMessage> kafkaTemplate;

	public EventPublisher(KafkaTemplate<String, EventMessage> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	public void publish(EventMessage message) {
		// Wait for the broker to acknowledge the send before returning.
		kafkaTemplate.send(TOPIC, message.id().toString(), message).join();
	}
}
