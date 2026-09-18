package com.kritika.signalforge.auth;

import com.kritika.signalforge.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/access-requests")
public class AdminAccessRequestController {
    private final AccessRequestService service;
    public AdminAccessRequestController(AccessRequestService service) { this.service = service; }

    public record ReviewRequest(@Size(max = 300) String reason) { }

    @GetMapping
    public PageResponse<AccessRequestResponse> list(@RequestParam(required = false) AccessRequestStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
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
}
