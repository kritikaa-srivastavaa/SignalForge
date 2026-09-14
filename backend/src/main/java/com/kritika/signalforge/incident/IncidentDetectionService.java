package com.kritika.signalforge.incident;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.kritika.signalforge.event.EventMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentDetectionService {

	private static final Logger log = LoggerFactory.getLogger(IncidentDetectionService.class);
	private static final int THRESHOLD = 3;
	private static final Duration WINDOW = Duration.ofSeconds(60);
	private static final Duration COOLDOWN = Duration.ofSeconds(60);

	// Local to this application instance; horizontal scaling needs a different state design.
	private final Map<DetectionKey, Deque<EventMessage>> windows = new HashMap<>();
	private final Map<DetectionKey, Instant> lastIncidentTimes = new HashMap<>();

	private record DetectionKey(String service, String type) {
	}

	private final IncidentRepository incidentRepository;

	public IncidentDetectionService(IncidentRepository incidentRepository) {
		this.incidentRepository = incidentRepository;
	}

	@Transactional
	public synchronized Optional<Incident> process(EventMessage event) {
		DetectionKey key = new DetectionKey(event.service(), event.type());
		Instant now = event.timestamp();
		Deque<EventMessage> window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		window.removeIf(previous -> previous.timestamp().isBefore(now.minus(WINDOW)));
		window.addLast(event);

		// Exclude future timestamps if messages arrive out of order. Previously evicted
		// history is not reconstructed; full late-event handling is a later milestone.
		long count = window.stream().filter(previous -> !previous.timestamp().isAfter(now)).count();
		Instant lastIncident = lastIncidentTimes.get(key);
		if (count < THRESHOLD || (lastIncident != null && now.isBefore(lastIncident.plus(COOLDOWN)))) {
			return Optional.empty();
		}

		// Limit the generated title to the database column's 255 characters.
		String title = "Event spike detected for " + event.type() + " in " + event.service();
		if (title.length() > 255) {
			title = title.substring(0, 255);
		}

		Incident incident = new Incident(event.id(), event.service(), event.type(), event.severity(), title);
		Incident saved = incidentRepository.save(incident);
		lastIncidentTimes.put(key, now);
		log.info("Created incident id={} for event id={}", saved.getId(), event.id());
		return Optional.of(saved);
	}
}
