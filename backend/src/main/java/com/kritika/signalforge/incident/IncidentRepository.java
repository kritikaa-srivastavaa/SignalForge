package com.kritika.signalforge.incident;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

	@Query("""
			select item from Incident item
			where (:service is null or item.service = :service)
			and (:type is null or item.type = :type)
			and (:severity is null or item.severity = :severity)
			and (:status is null or item.status = :status)
			""")
	Page<Incident> findFiltered(@Param("service") String service, @Param("type") String type, @Param("severity") String severity, @Param("status") IncidentStatus status,
			Pageable pageable);
}
