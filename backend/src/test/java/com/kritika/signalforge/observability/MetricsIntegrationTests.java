package com.kritika.signalforge.observability;

import java.time.Instant;
import java.util.UUID;
import com.kritika.signalforge.event.*;
import com.kritika.signalforge.event.processing.*;
import com.kritika.signalforge.incident.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricsIntegrationTests {
	@Autowired private MockMvc mvc;
	@Autowired private MeterRegistry registry;
	@Autowired private EventProcessingService processing;
	@Autowired private EventConsumer consumer;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private SignalForgeMetrics metrics;
	@MockitoBean private EventPublisher publisher;
	@MockitoBean private EventRateLimiter limiter;
	private final String service = "metrics-" + UUID.randomUUID();
	private final java.util.List<UUID> ids = new java.util.ArrayList<>();

	@AfterEach
	void cleanupOwnRows() {
		jdbc.update("delete from incidents where service = ?", service);
		jdbc.update("delete from events where service = ?", service);
		for (UUID id : ids) jdbc.update("delete from processed_events where event_id = ?", id);
	}

	@Test
	void successfulPostCountsButRejectedAndInvalidPostsDoNot() throws Exception {
		when(limiter.tryAcquire(anyString())).thenReturn(true, false);
		String json = """
				{"service":"%s","type":"ERROR","severity":"LOW","message":"Test","timestamp":"2026-09-16T12:00:00Z"}
				""".formatted(service);
		double rejectedBefore = total("signalforge.ingestion.rate_limit.rejections");
		mvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated());
		assertThat(count("signalforge.events.ingested")).isEqualTo(1);
		mvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isTooManyRequests());
		assertThat(count("signalforge.events.ingested")).isEqualTo(1);
		assertThat(total("signalforge.ingestion.rate_limit.rejections")).isEqualTo(rejectedBefore + 1);
		mvc.perform(post("/events").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType(MediaType.APPLICATION_JSON).content(json.replace("\"message\":\"Test\"", "\"message\":\"\""))).andExpect(status().isBadRequest());
		assertThat(count("signalforge.events.ingested")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from events where service = ?", Long.class, service)).isEqualTo(1);
		verify(publisher).publish(any());
	}

	@Test
	void processingDuplicatesAndIncidentCooldownCountAccurately() {
		EventMessage first = event(0);
		processing.process(first);
		assertThat(count("signalforge.events.processed")).isEqualTo(1);
		processing.process(first);
		assertThat(count("signalforge.events.duplicates")).isEqualTo(1);
		assertThat(count("signalforge.events.processed")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Long.class, first.id())).isEqualTo(1);
		processing.process(event(20));
		assertThat(count("signalforge.incidents.created")).isZero();
		processing.process(event(40));
		assertThat(count("signalforge.incidents.created")).isEqualTo(1);
		processing.process(event(41));
		assertThat(count("signalforge.incidents.created")).isEqualTo(1);
		assertThat(count("signalforge.events.processed")).isEqualTo(4);
		assertThat(jdbc.queryForObject("select count(*) from incidents where service = ?", Long.class, service)).isEqualTo(1);
	}

	@Test
	void failedAttemptPropagatesAndDoesNotCountSuccess() {
		EventMessage event = new EventMessage(null, service, "ERROR", "LOW", "Test", Instant.EPOCH, Instant.EPOCH);
		double failures = total("signalforge.processing.failures");
		assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(RuntimeException.class);
		assertThat(total("signalforge.processing.failures")).isEqualTo(failures + 1);
		assertThat(count("signalforge.events.processed")).isZero();
	}

	@Test
	void prometheusAndHealthAreExposed() throws Exception {
		metrics.recordRateLimitRejection();
		mvc.perform(get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN))
				.andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
				.andExpect(content().string(containsString("signalforge_ingestion_rate_limit_rejections_total")));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void incidentCounterUsesThePrometheusNameQueriedByGrafana() throws Exception {
		metrics.recordIncidentCreated(service, "ERROR", "HIGH");
		String scrape = mvc.perform(get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		// Prometheus reserves the _created suffix; the registry exports incidents_total.
		String dashboard = java.nio.file.Files.readString(java.nio.file.Path.of(
				System.getProperty("basedir", "."), "..", "infrastructure", "grafana",
				"provisioning", "dashboards", "signalforge-dashboard.json"));
		var incidentMetricNames = java.util.regex.Pattern.compile("signalforge_incidents[a-z_]*")
				.matcher(dashboard).results().map(java.util.regex.MatchResult::group).distinct().toList();
		assertThat(incidentMetricNames).isNotEmpty();
		for (String name : incidentMetricNames) {
			assertThat(scrape).contains("# TYPE " + name + " counter");
		}
		assertThat(scrape).contains("# TYPE signalforge_incidents_total counter")
				.doesNotContain("signalforge_incidents_created_total");
		assertThat(scrape.lines().filter(line -> line.startsWith("signalforge_incidents_total{")
				&& line.contains(service))).singleElement().asString().endsWith(" 1.0");
	}
	private EventMessage event(int seconds) {
		UUID id = UUID.randomUUID();
		ids.add(id);
		Instant time = Instant.parse("2026-09-16T12:00:00Z").plusSeconds(seconds);
		return new EventMessage(id, service, "ERROR", "LOW", "Test", time, time);
	}

	private double count(String name) {
		return registry.find(name).tag("service", service).counters().stream().mapToDouble(c -> c.count()).sum();
	}

	private double total(String name) {
		return registry.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
	}
}
