package com.kritika.signalforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DevelopmentCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        for (String path : new String[] {"/events", "/incidents", "/incidents/{id}"}) {
            registry.addMapping(path)
                    .allowedOrigins("http://localhost:5173")
                    .allowedMethods("GET");
        }
        for (String path : new String[] {"/incidents/{id}/acknowledge", "/incidents/{id}/resolve"}) {
            registry.addMapping(path)
                    .allowedOrigins("http://localhost:5173")
                    .allowedMethods("PATCH");
        }
    }
}