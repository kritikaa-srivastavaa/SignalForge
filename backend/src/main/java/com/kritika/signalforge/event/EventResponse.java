package com.kritika.signalforge.event;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(
		UUID id,
		String service,
		String type,
		String severity,
		String message,
		Instant timestamp,
		Instant receivedAt) {
}
