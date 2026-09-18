package com.kritika.signalforge.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(@NotBlank @Size(max = 254) String email,
                           @NotBlank @Size(max = 72) String password) {
    public LoginRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() { return "LoginRequest[redacted]"; }
}
