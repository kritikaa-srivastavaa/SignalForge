package com.kritika.signalforge.observability;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SignalForgeMetrics {
	private final MeterRegistry registry;
	private final Map<String, Set<String>> tagValues = new HashMap<>();
	private static final int MAX_VALUES_PER_TAG = 20;

	public SignalForgeMetrics(MeterRegistry registry) {
		this.registry = registry;
		registry.counter("signalforge.ingestion.rate_limit.rejections");
	}

	public void recordEventIngested(String service, String type, String severity) {
		afterCommit(() -> eventCounter("signalforge.events.ingested", service, type, severity));
	}

	public void recordEventProcessed(String service, String type) {
		afterCommit(() -> registry.counter("signalforge.events.processed",
				"service", tag("service", service), "type", tag("type", type)).increment());
	}

	public void recordDuplicateEvent(String service, String type) {
		registry.counter("signalforge.events.duplicates",
				"service", tag("service", service), "type", tag("type", type)).increment();
	}

	public void recordIncidentCreated(String service, String type, String severity) {
		afterCommit(() -> eventCounter("signalforge.incidents.created", service, type, severity));
	}

	public void recordRateLimitRejection() {
		registry.counter("signalforge.ingestion.rate_limit.rejections").increment();
	}

	public void recordProcessingFailure(RuntimeException failure) {
		registry.counter("signalforge.processing.failures",
				"exception", tag("exception", failure.getClass().getSimpleName())).increment();
	}

	private void eventCounter(String name, String service, String type, String severity) {
		registry.counter(name, "service", tag("service", service), "type", tag("type", type),
				"severity", tag("severity", severity)).increment();
	}

	private synchronized String tag(String key, String value) {
		if (value == null || value.isBlank()) return "unknown";
		Set<String> known = tagValues.computeIfAbsent(key, ignored -> new HashSet<>());
		// Request-supplied dimensions must not create unlimited time series.
		if (known.contains(value)) return value;
		if (known.size() >= MAX_VALUES_PER_TAG) return "other";
		known.add(value);
		return value;
	}

	private void afterCommit(Runnable increment) {
		if (TransactionSynchronizationManager.isActualTransactionActive()
				&& TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					increment.run();
				}
			});
		} else {
			increment.run();
		}
	}
}
