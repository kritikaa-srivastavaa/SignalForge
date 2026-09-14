package com.kritika.signalforge.event.processing;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

	@Modifying
	@Transactional
	@Query(value = """
			INSERT INTO processed_events (event_id, processed_at)
			VALUES (:eventId, :processedAt)
			ON CONFLICT (event_id) DO NOTHING
			""", nativeQuery = true)
	int registerIfAbsent(@Param("eventId") UUID eventId, @Param("processedAt") Instant processedAt);
}
