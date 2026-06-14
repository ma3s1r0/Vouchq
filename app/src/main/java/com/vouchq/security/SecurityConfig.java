package com.vouchq.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import org.springframework.http.HttpStatus;

/**
 * Spring Security + RBAC for the control plane (MA3-71/MA3-93, 기획서 §10).
 *
 * <p><b>Auth mechanism:</b> DB-backed users over {@code app_user}
 * ({@link DbUserDetailsService}, BCrypt). Two front doors share that one user
 * provider:
 * <ul>
 *   <li><b>Session</b> for the console — {@code POST /api/auth/login} establishes a
 *       {@code JSESSIONID} cookie; {@code /logout}; {@code GET /api/auth/me}.</li>
 *   <li><b>HTTP Basic</b> for API/CI clients — stateless, per request.</li>
 * </ul>
 * SSO/SAML remains Phase 2 (기획서 §12).
 *
 * <p><b>RBAC rule matrix</b> (roles are hierarchical: ADMIN ⊇ MEMBER ⊇ VIEWER):
 * <pre>
 *   OPEN  (no auth)  /actuator/health, /v3/api-docs/**, /swagger-ui/**, POST /api/auth/login
 *   READ  (VIEWER+)  GET  /api/**
 *   ADMIN (ADMIN)    POST/PUT/PATCH/DELETE /api/settings/**  (notify, policy rules, members)
 *   WRITE (MEMBER+)  POST/PUT/PATCH/DELETE /api/**  (approve, block, resolve, connect source, scan)
 * </pre>
 *
 * A VIEWER therefore gets 403 on any mutating call; MEMBER/ADMIN are allowed.
 * CSRF is disabled: API clients use Basic, and the self-hosted console uses the
 * session cookie over a same-origin JSON API (no browser form posts).
 */
@Configuration
public class SecurityConfig {

    /** Paths served without authentication (health probe, OpenAPI docs/UI, login). */
    static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // Session is created on login (console); Basic remains per-request.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Auth endpoints: login must be reachable unauthenticated;
                        // logout/me require an authenticated principal.
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        // Members management is ADMIN-only end to end, including
                        // the listing (it exposes the user roster). Declared before
                        // the general settings GET rule so it wins.
                        .requestMatchers("/api/settings/members/**").hasRole("ADMIN")
                        .requestMatchers("/api/settings/members").hasRole("ADMIN")
                        // Settings (notify channels / policy rules):
                        // reads VIEWER+, mutations ADMIN-only. Declared before the
                        // general /api/** mutating rules so it wins.
                        .requestMatchers(HttpMethod.GET, "/api/settings/**")
                            .hasAnyRole("VIEWER", "MEMBER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/settings/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/settings/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/settings/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/settings/**").hasRole("ADMIN")
                        // Read access: any authenticated role.
                        .requestMatchers(HttpMethod.GET, "/api/**")
                            .hasAnyRole("VIEWER", "MEMBER", "ADMIN")
                        // Mutating access: MEMBER or ADMIN (VIEWER forbidden).
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("MEMBER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("MEMBER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole("MEMBER", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole("MEMBER", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .httpBasic(basic -> {})
                // No browser login form / redirect: unauthenticated API calls get a
                // clean 401 instead of a 302 to a non-existent login page.
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout.disable());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Single auth provider shared by Basic and the session login endpoint: the
     * DB-backed {@link UserDetailsService} with BCrypt verification.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
