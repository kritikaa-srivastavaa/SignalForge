package com.kritika.signalforge.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class CurrentRoleFilter extends OncePerRequestFilter {
    private final AppUserRepository users;

    public CurrentRoleFilter(AppUserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // Production login always creates SessionIdentity. Spring's mock-user test support does not.
        if (authentication != null && authentication.getPrincipal() instanceof SessionIdentity identity) {
            var user = users.findById(identity.id());
            var context = SecurityContextHolder.createEmptyContext();
            if (user.isPresent()) {
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        identity, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.get().getRole().name()))));
            }
            // Do not mutate the shared session context: concurrent requests each get a fresh snapshot.
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
