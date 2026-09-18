package com.kritika.signalforge.incident;

import com.kritika.signalforge.common.PageResponse;
import com.kritika.signalforge.common.Pagination;
import java.util.UUID;
import com.kritika.signalforge.audit.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

	private final IncidentRepository incidentRepository;
    private final AuditService audit;

	public IncidentService(IncidentRepository incidentRepository, AuditService audit) {
		this.incidentRepository = incidentRepository;
        this.audit = audit;
	}

	@Transactional(readOnly = true)
	public PageResponse<IncidentResponse> getIncidents(String service, String type, String severity, IncidentStatus status, int page, int size) {
		return PageResponse.from(incidentRepository.findFiltered(
				Pagination.filter(service), Pagination.filter(type), Pagination.filter(severity), status,
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
				incident.getStatus().name(), incident.getCreatedAt());
	}

	@Transactional
	public IncidentResponse acknowledgeIncident(UUID id) {
		Incident incident = incidentRepository.findById(id)
				.orElseThrow(() -> new IncidentNotFoundException(id));
		if (incident.getStatus() == IncidentStatus.RESOLVED) {
			throw new InvalidIncidentTransitionException(incident.getStatus(), IncidentStatus.ACKNOWLEDGED);
		}
		if (incident.getStatus() != IncidentStatus.ACKNOWLEDGED) {
            String oldStatus = incident.getStatus().name();
            incident.changeStatus(IncidentStatus.ACKNOWLEDGED);
            // Flush the versioned transition before recording success; both still roll back together.
            incidentRepository.flush();
            audit.recordCurrentActor(AuditAction.INCIDENT_ACKNOWLEDGED, AuditTarget.INCIDENT,
                    incident.getId(), oldStatus, IncidentStatus.ACKNOWLEDGED.name());
        }
		return toResponse(incident);
	}

	@Transactional
	public IncidentResponse resolveIncident(UUID id) {
		Incident incident = incidentRepository.findById(id)
				.orElseThrow(() -> new IncidentNotFoundException(id));
		if (incident.getStatus() != IncidentStatus.RESOLVED) {
            String oldStatus = incident.getStatus().name();
            incident.changeStatus(IncidentStatus.RESOLVED);
            // Flush the versioned transition before recording success; both still roll back together.
            incidentRepository.flush();
            audit.recordCurrentActor(AuditAction.INCIDENT_RESOLVED, AuditTarget.INCIDENT,
                    incident.getId(), oldStatus, IncidentStatus.RESOLVED.name());
        }
		return toResponse(incident);
	}
}
