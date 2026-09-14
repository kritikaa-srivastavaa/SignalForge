package com.kritika.signalforge.incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
		UUID id,
		UUID sourceEventId,
		String service,
		String type,
		String severity,
		String title,
		String status,
		Instant createdAt) {
}
