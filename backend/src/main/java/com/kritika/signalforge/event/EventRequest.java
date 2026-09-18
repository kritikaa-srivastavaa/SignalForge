package com.kritika.signalforge.event;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EventRequest(
		@NotBlank String service,
		@NotBlank String type,
		@NotBlank String severity,
		@NotBlank String message,
		@NotNull Instant timestamp) {
}
