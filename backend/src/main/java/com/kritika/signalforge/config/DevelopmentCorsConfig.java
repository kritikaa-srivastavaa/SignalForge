package com.kritika.signalforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DevelopmentCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        allow(registry, "/events", "GET", "POST");
        allow(registry, "/access-requests", "POST");
        allow(registry, "/access-requests/me", "GET");
        allow(registry, "/admin/access-requests", "GET");
        allow(registry, "/admin/access-requests/{id}/approve", "PATCH");
        allow(registry, "/admin/access-requests/{id}/reject", "PATCH");
        allow(registry, "/admin/audit", "GET");
        allow(registry, "/admin/users", "GET");
        allow(registry, "/admin/users/{id}/role", "PATCH");
        for (String path : new String[] {"/events/{id}", "/incidents", "/incidents/{id}", "/auth/me", "/auth/csrf"}) {
            allow(registry, path, "GET");
        }
        for (String path : new String[] {"/incidents/{id}/acknowledge", "/incidents/{id}/resolve"}) {
            allow(registry, path, "PATCH");
        }
        for (String path : new String[] {"/auth/register", "/auth/login", "/auth/logout"}) {
            allow(registry, path, "POST");
        }
    }

    private void allow(CorsRegistry registry, String path, String... methods) {
        registry.addMapping(path).allowedOrigins("http://localhost:5173")
                .allowedMethods(methods).allowedHeaders("Content-Type", "X-CSRF-TOKEN")
                .allowCredentials(true);
    }
}