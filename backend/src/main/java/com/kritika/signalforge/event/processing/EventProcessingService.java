package com.kritika.signalforge.event.processing;

import java.time.Instant;

import com.kritika.signalforge.event.EventMessage;
import com.kritika.signalforge.incident.IncidentDetectionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventProcessingService {

	private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);

	private final ProcessedEventRepository processedEventRepository;
	private final IncidentDetectionService incidentDetectionService;

	public EventProcessingService(ProcessedEventRepository processedEventRepository,
			IncidentDetectionService incidentDetectionService) {
		this.processedEventRepository = processedEventRepository;
		this.incidentDetectionService = incidentDetectionService;
	}

	@Transactional
	public void process(EventMessage event) {
		// The marker and any incident write share one PostgreSQL transaction.
		int registered = processedEventRepository.registerIfAbsent(event.id(), Instant.now());
		if (registered == 0) {
			log.info("Skipping already processed event id={}", event.id());
			return;
		}
		incidentDetectionService.process(event);
	}
}
