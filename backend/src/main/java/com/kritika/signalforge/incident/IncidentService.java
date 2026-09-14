package com.kritika.signalforge.incident;

import java.util.List;
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
	public List<IncidentResponse> getAllIncidents() {
		return incidentRepository.findAll().stream()
				.map(this::toResponse)
				.toList();
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
