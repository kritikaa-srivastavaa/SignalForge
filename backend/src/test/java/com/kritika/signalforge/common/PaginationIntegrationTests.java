package com.kritika.signalforge.common;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kritika.signalforge.event.Event;
import com.kritika.signalforge.event.EventRepository;
import com.kritika.signalforge.incident.Incident;
import com.kritika.signalforge.incident.IncidentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@org.springframework.security.test.context.support.WithMockUser
@AutoConfigureMockMvc
@Transactional
class PaginationIntegrationTests {

	@Autowired private MockMvc mvc;
	@Autowired private EventRepository events;
	@Autowired private IncidentRepository incidents;
	@Autowired private EntityManager entityManager;

	private final List<UUID> eventIds = new ArrayList<>();
	private final List<UUID> incidentIds = new ArrayList<>();
	private long eventTotal;
	private long incidentTotal;
	private static final String SERVICE = "pagination-fixture";
	private static final String TYPE = "PAGINATION_TEST";

	@BeforeEach
	void seed() {
		Instant base = Instant.parse("2026-09-15T12:00:00Z");
		for (int i = 0; i < 25; i++) {
			String severity = i % 2 == 0 ? "LOW" : "MEDIUM";
			eventIds.add(events.save(new Event(SERVICE, TYPE, severity, "Test", base.plusSeconds(i))).getId());
			Incident incident = incidents.saveAndFlush(new Incident(UUID.randomUUID(), SERVICE, TYPE, severity, "Test"));
			incidentIds.add(incident.getId());
			// Set explicit timestamps for deterministic ordering without sleeps.
			entityManager.createNativeQuery("update incidents set created_at = :time where id = :id")
					.setParameter("time", base.plusSeconds(i)).setParameter("id", incident.getId()).executeUpdate();
		}
		entityManager.flush();
		entityManager.clear();
		eventTotal = events.count();
		incidentTotal = incidents.count();
	}

	@Test
	void defaultMetadataForBothCollections() throws Exception {
		for (String path : List.of("/events", "/incidents")) {
			long total = path.equals("/events") ? eventTotal : incidentTotal;
			mvc.perform(get(path)).andExpect(status().isOk())
					.andExpect(jsonPath("$.content", hasSize(20)))
					.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
					.andExpect(jsonPath("$.totalElements").value(total))
					.andExpect(jsonPath("$.totalPages").value((int) ((total + 19) / 20)))
					.andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(false));
		}
	}

	@Test
	void pagesAreSortedAndHaveCorrectSubsets() throws Exception {
		for (String path : List.of("/events", "/incidents")) {
			List<UUID> ids = path.equals("/events") ? eventIds : incidentIds;
			mvc.perform(get(path).param("service", SERVICE).param("page", "1").param("size", "10"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(10)))
					.andExpect(jsonPath("$.content[0].id").value(ids.get(14).toString()))
					.andExpect(jsonPath("$.content[9].id").value(ids.get(5).toString()))
					.andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(10))
					.andExpect(jsonPath("$.totalElements").value(25)).andExpect(jsonPath("$.totalPages").value(3))
					.andExpect(jsonPath("$.first").value(false)).andExpect(jsonPath("$.last").value(false));
			mvc.perform(get(path).param("service", SERVICE).param("page", "2").param("size", "10"))
					.andExpect(jsonPath("$.content", hasSize(5))).andExpect(jsonPath("$.last").value(true));
		}
	}

	@Test
	void normalizesPageAndSize() throws Exception {
		for (String path : List.of("/events", "/incidents")) {
			mvc.perform(get(path).param("size", "500")).andExpect(jsonPath("$.size").value(100));
			for (String size : List.of("0", "-5")) {
				mvc.perform(get(path).param("page", "-1").param("size", size))
						.andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
						.andExpect(jsonPath("$.size").value(20));
			}
		}
	}

	@Test
	void exactFiltersCombineWithAnd() throws Exception {
		for (String path : List.of("/events", "/incidents")) {
			mvc.perform(get(path).param("service", SERVICE)).andExpect(jsonPath("$.totalElements").value(25));
			mvc.perform(get(path).param("type", TYPE)).andExpect(jsonPath("$.totalElements").value(25));
			mvc.perform(get(path).param("service", SERVICE).param("type", TYPE).param("severity", "LOW"))
					.andExpect(jsonPath("$.totalElements").value(13));
			mvc.perform(get(path).param("service", SERVICE).param("type", "other"))
					.andExpect(jsonPath("$.totalElements").value(0));
			mvc.perform(get(path).param("service", SERVICE).param("severity", "low"))
					.andExpect(jsonPath("$.totalElements").value(0));
			mvc.perform(get(path).param("service", "no-such-pagination-service"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)))
					.andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
		}
		mvc.perform(get("/incidents").param("service", SERVICE).param("status", "OPEN"))
				.andExpect(jsonPath("$.totalElements").value(25));
		mvc.perform(get("/incidents").param("service", SERVICE).param("status", "RESOLVED"))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void standaloneSeverityAndStatusFilters() throws Exception {
		long matchingEvents = entityManager.createQuery("select count(e) from Event e where severity = 'LOW'", Long.class).getSingleResult();
		long openIncidents = entityManager.createQuery("select count(i) from Incident i where status = 'OPEN'", Long.class).getSingleResult();
		mvc.perform(get("/events").param("severity", "LOW"))
				.andExpect(jsonPath("$.totalElements").value(matchingEvents));
		mvc.perform(get("/incidents").param("status", "OPEN"))
				.andExpect(jsonPath("$.totalElements").value(openIncidents));
	}
}
