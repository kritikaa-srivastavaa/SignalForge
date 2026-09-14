package com.kritika.signalforge.incident;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/incidents")
public class IncidentController {

	private final IncidentService incidentService;

	public IncidentController(IncidentService incidentService) {
		this.incidentService = incidentService;
	}

	@GetMapping
	public List<IncidentResponse> getAllIncidents() {
		return incidentService.getAllIncidents();
	}

	@GetMapping("/{id}")
	public IncidentResponse getIncidentById(@PathVariable UUID id) {
		return incidentService.getIncidentById(id);
	}
}
