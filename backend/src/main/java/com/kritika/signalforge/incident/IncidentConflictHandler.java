package com.kritika.signalforge.incident;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IncidentController.class)
public class IncidentConflictHandler {
	@ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
	@ResponseStatus(HttpStatus.CONFLICT)
	public void handleOptimisticLockConflict() {
	}
}
