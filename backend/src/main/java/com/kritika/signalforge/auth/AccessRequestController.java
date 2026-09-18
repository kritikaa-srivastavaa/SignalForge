package com.kritika.signalforge.auth;

import java.security.Principal;
import java.util.UUID;
import jakarta.validation.Valid;
import com.kritika.signalforge.common.PageResponse;
import com.kritika.signalforge.auth.AdminAccessRequestController.ReviewRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/access-requests")
public class AccessRequestController {
    private final AccessRequestService service;
    public AccessRequestController(AccessRequestService service) { this.service = service; }

    // No request body is bound: all security-controlled values are server-owned.
    @PostMapping
    public ResponseEntity<AccessRequestResponse> create(Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.getName()));
    }

    @GetMapping("/review")
    public PageResponse<AccessRequestResponse> groupQueue(Principal principal,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.groupQueue(principal.getName(), page, size);
    }

    @PatchMapping("/{id}/approve")
    public AccessRequestResponse approve(@PathVariable UUID id, Principal principal) {
        return service.review(principal.getName(), id, true, null);
    }

    @PatchMapping("/{id}/reject")
    public AccessRequestResponse reject(@PathVariable UUID id, Principal principal,
            @Valid @RequestBody(required = false) ReviewRequest input) {
        String reason = input == null || input.reason() == null || input.reason().isBlank() ? null : input.reason().trim();
        return service.review(principal.getName(), id, false, reason);
    }

    @GetMapping("/me")
    public ResponseEntity<AccessRequestResponse> latest(Principal principal) {
        return service.latest(principal.getName()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
