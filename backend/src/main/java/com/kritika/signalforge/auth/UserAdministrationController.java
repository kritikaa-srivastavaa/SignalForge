package com.kritika.signalforge.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/users")
public class UserAdministrationController {
    private final UserAdministrationService service;

    public UserAdministrationController(UserAdministrationService service) { this.service = service; }

    @GetMapping
    public List<UserResponse> users() { return service.listUsers(); }

    @PatchMapping("/{id}/role")
    public UserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest request, Principal actor) {
        return service.changeRole(actor.getName(), id, request.role());
    }

    public record RoleRequest(@NotNull UserRole role) { }
}
