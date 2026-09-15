package com.kritika.signalforge.incident;

import com.kritika.signalforge.common.PageResponse;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;

@RestController
@RequestMapping("/incidents")
public class IncidentController {

	private final IncidentService incidentService;

	public IncidentController(IncidentService incidentService) {
		this.incidentService = incidentService;
	}

	@GetMapping
	public PageResponse<IncidentResponse> getIncidents(
			@RequestParam(required = false) String service,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String severity,
			@RequestParam(required = false) IncidentStatus status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return incidentService.getIncidents(service, type, severity, status, page, size);
	}

	@GetMapping("/{id}")
	public IncidentResponse getIncidentById(@PathVariable UUID id) {
		return incidentService.getIncidentById(id);
	}

	@PatchMapping("/{id}/acknowledge")
	public IncidentResponse acknowledgeIncident(@PathVariable UUID id) {
		return incidentService.acknowledgeIncident(id);
	}

	@PatchMapping("/{id}/resolve")
	public IncidentResponse resolveIncident(@PathVariable UUID id) {
		return incidentService.resolveIncident(id);
	}
}
