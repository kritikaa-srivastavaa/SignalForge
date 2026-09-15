package com.kritika.signalforge.event;

import com.kritika.signalforge.observability.SignalForgeMetrics;

import com.kritika.signalforge.common.PageResponse;
import com.kritika.signalforge.common.Pagination;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
	private final SignalForgeMetrics metrics;

	private final EventRepository eventRepository;
	private final EventPublisher eventPublisher;

	public EventService(EventRepository eventRepository, EventPublisher eventPublisher, SignalForgeMetrics metrics) {
		this.metrics = metrics;
		this.eventRepository = eventRepository;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public EventResponse createEvent(EventRequest request) {
		Event event = new Event(request.service(), request.type(), request.severity(),
				request.message(), request.timestamp());
		// Execute the INSERT before publishing; the transaction commits when this method returns.
		Event saved = eventRepository.saveAndFlush(event);
		eventPublisher.publish(new EventMessage(saved.getId(), saved.getService(), saved.getType(),
				saved.getSeverity(), saved.getMessage(), saved.getTimestamp(), saved.getReceivedAt()));

		metrics.recordEventIngested(saved.getService(), saved.getType(), saved.getSeverity());
		return toResponse(saved);
	}

	@Transactional(readOnly = true)
	public PageResponse<EventResponse> getEvents(String service, String type, String severity, int page, int size) {
		return PageResponse.from(eventRepository.findFiltered(
				Pagination.filter(service), Pagination.filter(type), Pagination.filter(severity),
				Pagination.request(page, size, "timestamp")).map(this::toResponse));
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
