package com.kritika.signalforge.event.processing;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

	@Id
	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "processed_at", nullable = false, updatable = false)
	private Instant processedAt;

	protected ProcessedEvent() {
		// Required by JPA; registration uses the repository's atomic insert.
	}

	public UUID getEventId() {
		return eventId;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}
}
