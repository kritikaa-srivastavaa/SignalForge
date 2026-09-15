package com.kritika.signalforge.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;

class SignalForgeMetricsTests {
	@Test
	void incrementsAllSixCountersWithOnlyExpectedTags() {
		{ var registry = new SimpleMeterRegistry();
			var metrics = new SignalForgeMetrics(registry);
			assertThat(registry.get("signalforge.ingestion.rate_limit.rejections").counter().count()).isZero();
			metrics.recordEventIngested("payment", "ERROR", "HIGH");
			metrics.recordEventProcessed("payment", "ERROR");
			metrics.recordDuplicateEvent("payment", "ERROR");
			metrics.recordIncidentCreated("payment", "ERROR", "HIGH");
			metrics.recordRateLimitRejection();
			metrics.recordProcessingFailure(new IllegalStateException("not a tag"));
			for (String name : java.util.List.of("events.ingested", "events.processed", "events.duplicates", "incidents.created")) {
				var counter = registry.get("signalforge." + name).tags("service", "payment", "type", "ERROR").counter();
				assertThat(counter.count()).isEqualTo(1);
				assertThat(counter.getId().getTags()).hasSize(name.equals("events.ingested") || name.equals("incidents.created") ? 3 : 2);
			}
			assertThat(registry.get("signalforge.events.ingested").tag("severity", "HIGH").counter().count()).isEqualTo(1);
			assertThat(registry.get("signalforge.incidents.created").tag("severity", "HIGH").counter().count()).isEqualTo(1);
			assertThat(registry.get("signalforge.processing.failures").tag("exception", "IllegalStateException").counter().count()).isEqualTo(1);
			assertThat(registry.get("signalforge.ingestion.rate_limit.rejections").counter().count()).isEqualTo(1);
			metrics.recordEventIngested("payment", "ERROR", "HIGH");
			assertThat(registry.get("signalforge.events.ingested").counter().count()).isEqualTo(2);
		}
	}

	@Test
	void successCountersWaitForCommitAndDoNotRunOnRollback() {
		{ var registry = new SimpleMeterRegistry();
			var metrics = new SignalForgeMetrics(registry);
			TransactionSynchronizationManager.initSynchronization();
			TransactionSynchronizationManager.setActualTransactionActive(true);
			try {
				metrics.recordEventProcessed("payment", "ERROR");
				assertThat(registry.find("signalforge.events.processed").counter()).isNull();
				TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
				assertThat(registry.get("signalforge.events.processed").counter().count()).isEqualTo(1);
			} finally {
				TransactionSynchronizationManager.clear();
			}
			TransactionSynchronizationManager.initSynchronization();
			TransactionSynchronizationManager.setActualTransactionActive(true);
			try {
				metrics.recordIncidentCreated("payment", "ERROR", "LOW");
				TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
				assertThat(registry.find("signalforge.incidents.created").counter()).isNull();
			} finally {
				TransactionSynchronizationManager.clear();
			}
		}
	}

	@Test
	void capsRequestSuppliedTagValues() {
		{ var registry = new SimpleMeterRegistry();
			var metrics = new SignalForgeMetrics(registry);
			for (int i = 0; i < 100; i++) metrics.recordEventProcessed("service-" + i, "type");
			assertThat(registry.find("signalforge.events.processed").counters()).hasSize(21);
			assertThat(registry.get("signalforge.events.processed").tag("service", "other").counter().count()).isEqualTo(80);
		}
	}
}
