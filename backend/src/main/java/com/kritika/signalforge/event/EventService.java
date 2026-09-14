package com.kritika.signalforge.event;

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

		return new EventResponse(saved.getId(), saved.getService(), saved.getType(),
				saved.getSeverity(), saved.getMessage(), saved.getTimestamp(), saved.getReceivedAt());
	}
}
