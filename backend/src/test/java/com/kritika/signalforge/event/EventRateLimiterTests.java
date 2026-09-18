package com.kritika.signalforge.event;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventRateLimiterTests {

	private final Clock clock = mock(Clock.class);
	private final Instant start = Instant.parse("2026-09-15T12:00:00Z");
	private final EventRateLimiter limiter = new EventRateLimiter(clock);

	@Test
	void allowsTenThenRejectsAndIsolatesClients() {
		when(clock.instant()).thenReturn(start);
		for (int i = 0; i < 10; i++) {
			assertThat(limiter.tryAcquire("192.0.2.1")).isTrue();
		}
		assertThat(limiter.tryAcquire("192.0.2.1")).isFalse();
		assertThat(limiter.tryAcquire("192.0.2.2")).isTrue();
	}

	@Test
	void resetsExactlyAtSixtySeconds() {
		when(clock.instant()).thenReturn(start);
		for (int i = 0; i < 10; i++) {
			limiter.tryAcquire("client");
		}
		when(clock.instant()).thenReturn(start.plusSeconds(60).minusNanos(1));
		assertThat(limiter.tryAcquire("client")).isFalse();
		when(clock.instant()).thenReturn(start.plusSeconds(60));
		for (int i = 0; i < 10; i++) {
			assertThat(limiter.tryAcquire("client")).isTrue();
		}
		assertThat(limiter.tryAcquire("client")).isFalse();
		when(clock.instant()).thenReturn(start.plusSeconds(121));
		assertThat(limiter.tryAcquire("client")).isTrue();
	}

	@Test
	void concurrentRequestsCannotExceedLimit() throws Exception {
		when(clock.instant()).thenReturn(start);
		try (var executor = Executors.newFixedThreadPool(8)) {
			var requests = new ArrayList<Callable<Boolean>>();
			for (int i = 0; i < 40; i++) {
				requests.add(() -> limiter.tryAcquire("client"));
			}
			int allowed = 0;
			for (var result : executor.invokeAll(requests)) {
				if (result.get()) {
					allowed++;
				}
			}
			assertThat(allowed).isEqualTo(10);
		}
	}
}
