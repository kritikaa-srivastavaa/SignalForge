package com.kritika.signalforge.event;

import com.kritika.signalforge.observability.SignalForgeMetrics;

import com.kritika.signalforge.event.processing.EventProcessingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {
	private final SignalForgeMetrics metrics;

	private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

	private final EventProcessingService eventProcessingService;

	public EventConsumer(EventProcessingService eventProcessingService, SignalForgeMetrics metrics) {
		this.metrics = metrics;
		this.eventProcessingService = eventProcessingService;
	}

	@KafkaListener(topics = EventPublisher.TOPIC, groupId = "${spring.kafka.consumer.group-id}")
	public void consume(EventMessage event) {
		log.info("Processing event id={} service={} type={} severity={}",
				event.id(), event.service(), event.type(), event.severity());
		try {
			eventProcessingService.process(event);
		} catch (RuntimeException failure) {
			// Counts failed attempts, including retries, not distinct event IDs.
			metrics.recordProcessingFailure(failure);
			throw failure;
		}
	}
}
