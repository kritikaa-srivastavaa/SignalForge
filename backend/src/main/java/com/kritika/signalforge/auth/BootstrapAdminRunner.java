package com.kritika.signalforge.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "signalforge.bootstrap.enabled", havingValue = "true")
public class BootstrapAdminRunner implements ApplicationRunner {
    private final BootstrapAdminService service;
    private final String email;
    private final String password;
    private final String displayName;

    public BootstrapAdminRunner(BootstrapAdminService service,
                                @Value("${signalforge.bootstrap.email:}") String email,
                                @Value("${signalforge.bootstrap.password:}") String password,
                                @Value("${signalforge.bootstrap.display-name:Local Administrator}") String displayName) {
        this.service = service;
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.bootstrap(email, password, displayName);
    }
}
