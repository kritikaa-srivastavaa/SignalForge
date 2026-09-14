package com.kritika.signalforge.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class EventRateLimiter {

	private static final int LIMIT = 10;
	private static final Duration WINDOW = Duration.ofSeconds(60);

	private final Clock clock;
	// Local instance state, lost on restart; scaling requires shared or gateway enforcement.
	private final Map<String, Window> windows = new HashMap<>();

	private record Window(Instant start, int count) {
	}

	public EventRateLimiter() {
		this(Clock.systemUTC());
	}

	EventRateLimiter(Clock clock) {
		this.clock = clock;
	}

	public synchronized boolean tryAcquire(String clientId) {
		Instant now = clock.instant();
		Window window = windows.get(clientId);
		if (window == null || !now.isBefore(window.start().plus(WINDOW))) {
			windows.put(clientId, new Window(now, 1));
			return true;
		}
		if (window.count() >= LIMIT) {
			return false;
		}
		windows.put(clientId, new Window(window.start(), window.count() + 1));
		return true;
	}
}
