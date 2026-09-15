package com.kritika.signalforge.incident;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidIncidentTransitionException extends RuntimeException {
	public InvalidIncidentTransitionException(IncidentStatus from, IncidentStatus to) {
		super("Cannot transition incident from " + from + " to " + to);
	}
}
