package com.kritika.signalforge.event;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

	@Query("""
			select item from Event item
			where (:service is null or item.service = :service)
			and (:type is null or item.type = :type)
			and (:severity is null or item.severity = :severity)
			""")
	Page<Event> findFiltered(@Param("service") String service, @Param("type") String type, @Param("severity") String severity,
			Pageable pageable);
}
