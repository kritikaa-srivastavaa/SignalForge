package com.kritika.signalforge.event;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
public class EventController {

	private final EventService eventService;
	private final EventRateLimiter eventRateLimiter;

	public EventController(EventService eventService, EventRateLimiter eventRateLimiter) {
		this.eventService = eventService;
		this.eventRateLimiter = eventRateLimiter;
	}

	@PostMapping
	public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request,
			HttpServletRequest servletRequest) {
		// Remote address only; reverse-proxy-aware identification is a later concern.
		if (!eventRateLimiter.tryAcquire(servletRequest.getRemoteAddr())) {
			throw new RateLimitExceededException();
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(eventService.createEvent(request));
	}

	@GetMapping
	public List<EventResponse> getAllEvents() {
		return eventService.getAllEvents();
	}

	@GetMapping("/{id}")
	public EventResponse getEventById(@PathVariable UUID id) {
		return eventService.getEventById(id);
	}
}
