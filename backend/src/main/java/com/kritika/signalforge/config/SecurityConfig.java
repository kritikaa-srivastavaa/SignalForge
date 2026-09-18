package com.kritika.signalforge.config;

import com.kritika.signalforge.auth.AppUserRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    AuthenticationManager authenticationManager(AppUserRepository users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(email -> {
            var user = users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
            return User.withUsername(user.getEmail()).password(user.getPasswordHash())
                    .roles(user.getRole().name()).build();
        });
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
                                           CsrfTokenRepository tokens, AppUserRepository users) throws Exception {
        AccessDeniedHandler denied = (request, response, failure) -> {
            // CSRF runs before authorization. Protected anonymous mutations still return 401.
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean protectedApi = path.equals("/events") || path.startsWith("/events/")
                    || path.equals("/incidents") || path.startsWith("/incidents/") || path.startsWith("/admin/");
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean anonymous = authentication == null || new org.springframework.security.authentication.AuthenticationTrustResolverImpl().isAnonymous(authentication);
            if (protectedApi && anonymous) json(response, 401, "Authentication required");
            else if (failure instanceof CsrfException) securityError(response, "CSRF_INVALID", "Invalid or missing CSRF token");
            else securityError(response, "FORBIDDEN", "Insufficient permissions");
        };
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(tokens))
                .securityContext(context -> context.securityContextRepository(contexts))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new com.kritika.signalforge.auth.CurrentRoleFilter(users),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/auth/csrf", "/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/auth/register", "/auth/login").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH,
                                "/incidents/*/acknowledge", "/incidents/*/resolve").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/events", "/events/**", "/incidents", "/incidents/**").hasAnyRole("VIEWER", "OPERATOR", "ADMIN")
                        // Preserve Prompt 23 ingestion authentication; human roles are not machine credentials.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/events").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> json(response, 401, "Authentication required"))
                        .accessDeniedHandler(denied))
                .logout(logout -> logout.logoutUrl("/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }

    private static void securityError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private static void json(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
