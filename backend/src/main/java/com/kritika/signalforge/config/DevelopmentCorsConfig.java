package com.kritika.signalforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DevelopmentCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // The read-only local dashboard needs only these two collection endpoints.
        for (String path : new String[] {"/events", "/incidents"}) {
            registry.addMapping(path)
                    .allowedOrigins("http://localhost:5173")
                    .allowedMethods("GET");
        }
    }
}
