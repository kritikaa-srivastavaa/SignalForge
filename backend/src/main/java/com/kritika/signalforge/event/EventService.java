package com.kritika.signalforge.event;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

	private final EventRepository eventRepository;

	public EventService(EventRepository eventRepository) {
		this.eventRepository = eventRepository;
	}

	@Transactional
	public EventResponse createEvent(EventRequest request) {
		Event event = new Event(request.service(), request.type(), request.severity(),
				request.message(), request.timestamp());
		Event saved = eventRepository.save(event);

		return toResponse(saved);
	}

	@Transactional(readOnly = true)
	public List<EventResponse> getAllEvents() {
		return eventRepository.findAll().stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public EventResponse getEventById(UUID id) {
		Event event = eventRepository.findById(id)
				.orElseThrow(() -> new EventNotFoundException(id));
		return toResponse(event);
	}

	private EventResponse toResponse(Event event) {
		return new EventResponse(event.getId(), event.getService(), event.getType(),
				event.getSeverity(), event.getMessage(), event.getTimestamp(), event.getReceivedAt());
	}
}
