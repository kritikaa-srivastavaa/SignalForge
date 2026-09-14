package com.kritika.signalforge.event;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "events")
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(nullable = false)
	private String service;

	@Column(nullable = false)
	private String type;

	@Column(nullable = false)
	private String severity;

	@Column(nullable = false, columnDefinition = "text")
	private String message;

	@Column(nullable = false)
	private Instant timestamp;

	@Column(name = "received_at", nullable = false, updatable = false)
	private Instant receivedAt;

	protected Event() {
		// Required by JPA when loading events from the database.
	}

	public Event(String service, String type, String severity, String message, Instant timestamp) {
		this.service = service;
		this.type = type;
		this.severity = severity;
		this.message = message;
		this.timestamp = timestamp;
	}

	@PrePersist
	private void onCreate() {
		receivedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getService() {
		return service;
	}

	public String getType() {
		return type;
	}

	public String getSeverity() {
		return severity;
	}

	public String getMessage() {
		return message;
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}
}
