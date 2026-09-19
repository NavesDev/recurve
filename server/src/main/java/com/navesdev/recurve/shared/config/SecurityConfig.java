package com.navesdev.recurve.shared.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * HTTP Basic over a stateless chain (FR-05.1), and the one place that
 * says which permission each route needs.
 *
 * <p>Authorization is a property of the HTTP boundary: it asks whether
 * the operator on the other side may do this. The services carry no
 * check, so the scheduler, the startup bootstrap and calls between
 * features are plain method calls — the server acting on its own behalf
 * has no operator and needs no permission. Deciding here also puts the
 * 403 ahead of any request parsing: an operator who may not create a
 * user does not learn what a valid body looks like.
 *
 * <p>One rule per route family, in the order Spring Security matches
 * them: the most specific first. {@code MANAGE_*} implies {@code VIEW_*}
 * when the principal's authorities are built, so a rule names one
 * permission.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        return http
                // No cookie-based session to protect, and no browser form posts.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // FR-01.5: rebuilding an index is a system operation.
                        .requestMatchers(HttpMethod.POST, "/api/users/reindex").hasAuthority("MANAGE_SYSTEM")
                        .requestMatchers(HttpMethod.GET, "/api/users/**").hasAuthority("VIEW_USERS")
                        .requestMatchers("/api/users/**").hasAuthority("MANAGE_USERS")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                // A denial happens in the filter, before any controller; hand it
                // to the same resolver the controllers use so the 403 carries the
                // ApiError body every other error does.
                .exceptionHandling(handling -> handling.accessDeniedHandler(
                        (request, response, denied) -> exceptionResolver.resolveException(request, response, null, denied)))
                .build();
    }

    /** NFR-04: the operator password is stored as a BCrypt hash. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
