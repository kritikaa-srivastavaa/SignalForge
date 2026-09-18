package com.kritika.signalforge.incident;

import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
class IncidentOptimisticLockIntegrationTests {
	@Autowired private EntityManagerFactory factory;

	@Test
	void staleUpdateCannotOverwriteCommittedResolution() {
		UUID id = null;
		try (EntityManager first = factory.createEntityManager();
				EntityManager second = factory.createEntityManager()) {
			first.getTransaction().begin();
			Incident incident = new Incident(UUID.randomUUID(), "locking-test", "API_ERROR", "LOW", "Test");
			first.persist(incident);
			first.getTransaction().commit();
			id = incident.getId();
			first.clear();

			first.getTransaction().begin();
			second.getTransaction().begin();
			Incident current = first.find(Incident.class, id);
			Incident stale = second.find(Incident.class, id);
			current.changeStatus(IncidentStatus.RESOLVED);
			first.getTransaction().commit();
			stale.changeStatus(IncidentStatus.ACKNOWLEDGED);
			assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
			second.getTransaction().rollback();
			first.clear();
			assertThat(first.find(Incident.class, id).getStatus()).isEqualTo(IncidentStatus.RESOLVED);
		} finally {
			// This test commits to exercise separate transactions; remove only its own fixture.
			if (id != null) {
				try (EntityManager cleanup = factory.createEntityManager()) {
					cleanup.getTransaction().begin();
					cleanup.remove(cleanup.find(Incident.class, id));
					cleanup.getTransaction().commit();
				}
			}
		}
	}
}
