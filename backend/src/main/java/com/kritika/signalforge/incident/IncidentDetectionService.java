package com.kritika.signalforge.incident;

import java.util.Optional;

import com.kritika.signalforge.event.EventMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentDetectionService {

	private static final Logger log = LoggerFactory.getLogger(IncidentDetectionService.class);

	private final IncidentRepository incidentRepository;

	public IncidentDetectionService(IncidentRepository incidentRepository) {
		this.incidentRepository = incidentRepository;
	}

	@Transactional
	public Optional<Incident> process(EventMessage event) {
		if (!"HIGH".equalsIgnoreCase(event.severity())) {
			return Optional.empty();
		}

		// Limit the generated title to the database column's 255 characters.
		String title = "High severity event detected in " + event.service();
		if (title.length() > 255) {
			title = title.substring(0, 255);
		}

		Incident incident = new Incident(event.id(), event.service(), event.type(), event.severity(), title);
		Incident saved = incidentRepository.save(incident);
		log.info("Created incident id={} for event id={}", saved.getId(), event.id());
		return Optional.of(saved);
	}
}
