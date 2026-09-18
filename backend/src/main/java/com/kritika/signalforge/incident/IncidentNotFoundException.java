package com.kritika.signalforge.incident;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class IncidentNotFoundException extends RuntimeException {

	public IncidentNotFoundException(UUID id) {
		super("Incident not found: " + id);
	}
}
