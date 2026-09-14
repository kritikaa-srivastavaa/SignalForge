package com.kritika.signalforge.event;

import com.kritika.signalforge.incident.IncidentDetectionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {

	private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

	private final IncidentDetectionService incidentDetectionService;

	public EventConsumer(IncidentDetectionService incidentDetectionService) {
		this.incidentDetectionService = incidentDetectionService;
	}

	@KafkaListener(topics = EventPublisher.TOPIC, groupId = "${spring.kafka.consumer.group-id}")
	public void consume(EventMessage event) {
		log.info("Processing event id={} service={} type={} severity={}",
				event.id(), event.service(), event.type(), event.severity());
		incidentDetectionService.process(event);
	}
}
