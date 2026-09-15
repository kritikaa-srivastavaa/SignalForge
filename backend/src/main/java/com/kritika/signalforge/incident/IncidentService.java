package com.kritika.signalforge.incident;

import com.kritika.signalforge.common.PageResponse;
import com.kritika.signalforge.common.Pagination;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

	private final IncidentRepository incidentRepository;

	public IncidentService(IncidentRepository incidentRepository) {
		this.incidentRepository = incidentRepository;
	}

	@Transactional(readOnly = true)
	public PageResponse<IncidentResponse> getIncidents(String service, String type, String severity, String status, int page, int size) {
		return PageResponse.from(incidentRepository.findFiltered(
				Pagination.filter(service), Pagination.filter(type), Pagination.filter(severity), Pagination.filter(status),
				Pagination.request(page, size, "createdAt")).map(this::toResponse));
	}

	@Transactional(readOnly = true)
	public IncidentResponse getIncidentById(UUID id) {
		Incident incident = incidentRepository.findById(id)
				.orElseThrow(() -> new IncidentNotFoundException(id));
		return toResponse(incident);
	}

	private IncidentResponse toResponse(Incident incident) {
		return new IncidentResponse(incident.getId(), incident.getSourceEventId(), incident.getService(),
				incident.getType(), incident.getSeverity(), incident.getTitle(),
				incident.getStatus(), incident.getCreatedAt());
	}
}
