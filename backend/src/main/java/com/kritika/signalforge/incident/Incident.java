package com.kritika.signalforge.incident;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Version;

@Entity
@Table(name = "incidents")
public class Incident {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "source_event_id", nullable = false)
	private UUID sourceEventId;

	@Column(nullable = false)
	private String service;

	@Column(nullable = false)
	private String type;

	@Column(nullable = false)
	private String severity;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private IncidentStatus status;

	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Incident() {
		// Required by JPA.
	}

	public Incident(UUID sourceEventId, String service, String type, String severity, String title) {
		this.sourceEventId = sourceEventId;
		this.service = service;
		this.type = type;
		this.severity = severity;
		this.title = title;
		this.status = IncidentStatus.OPEN;
	}

	@PrePersist
	private void onCreate() {
		createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getSourceEventId() {
		return sourceEventId;
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

	public String getTitle() {
		return title;
	}

	public IncidentStatus getStatus() {
		return status;
	}

	void changeStatus(IncidentStatus status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
