package com.kritika.signalforge.auth;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(401).body(Map.of("message", "Invalid email or password"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> duplicateEmail(DataIntegrityViolationException exception) {
        // Only the email uniqueness violation is an expected registration conflict.
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "uq_app_users_email".equals(constraint.getConstraintName())) {
                return ResponseEntity.status(409).body(Map.of("message", "An account with this email already exists"));
            }
            cause = cause.getCause();
        }
        return ResponseEntity.internalServerError().body(Map.of("message", "Unable to create account"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidInput() {
        // Never serialize binding errors: their rejected values can contain passwords.
        return ResponseEntity.badRequest().body(Map.of("message", "Check email, display name and password requirements"));
    }
}
