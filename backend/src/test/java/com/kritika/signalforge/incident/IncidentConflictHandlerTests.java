package com.kritika.signalforge.incident;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentConflictHandlerTests {
	@Test
	void optimisticLockFailureReturns409() throws Exception {
		IncidentService service = mock(IncidentService.class);
		UUID id = UUID.randomUUID();
		when(service.resolveIncident(id)).thenThrow(new ObjectOptimisticLockingFailureException(Incident.class, id));
		MockMvcBuilders.standaloneSetup(new IncidentController(service))
				.setControllerAdvice(new IncidentConflictHandler()).build()
				.perform(patch("/incidents/{id}/resolve", id)).andExpect(status().isConflict());
	}
}
