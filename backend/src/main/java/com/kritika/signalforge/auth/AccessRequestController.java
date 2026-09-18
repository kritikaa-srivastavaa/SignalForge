package com.kritika.signalforge.auth;

import java.security.Principal;
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

    @GetMapping("/me")
    public ResponseEntity<AccessRequestResponse> latest(Principal principal) {
        return service.latest(principal.getName()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
