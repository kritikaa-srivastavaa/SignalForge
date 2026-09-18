package com.kritika.signalforge.event;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {

	public RateLimitExceededException() {
		super("Event ingestion rate limit exceeded");
	}
}
