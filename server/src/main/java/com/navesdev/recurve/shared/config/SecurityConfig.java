package com.navesdev.recurve.shared.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            @Value("${recurve.docs.enabled:false}") boolean docsEnabled) throws Exception {
        return http
                // No cookie-based session to protect, and no browser form posts.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    // The contract is a shape, not data, and the Swagger UI has to
                    // fetch it before any credential exists. Only opened while
                    // DocsConfig serves it: off, the path answers 401 like any other.
                    if (docsEnabled) {
                        requests.requestMatchers(DocsConfig.PATH, DocsConfig.PATH + "/**").permitAll();
                    }
                    requests
                        // FR-01.5: rebuilding an index is a system operation.
                        .requestMatchers(HttpMethod.POST, "/api/users/reindex").hasAuthority("MANAGE_SYSTEM")
                        // BR-01: operators have no view permission; reading them is managing them.
                        .requestMatchers("/api/users/**").hasAuthority("MANAGE_USERS")
                        .anyRequest().authenticated();
                })
                // A refusal happens in the filter, before any controller; hand it
                // to the same resolver the controllers use so the 401 and the 403
                // carry the ApiError body every other error does. The default
                // entry point would answer a 401 with a Basic challenge, which
                // makes a browser pop its own login dialog over any client
                // (the Swagger UI included). An API client sends credentials
                // on every request; it needs no invitation.
                .httpBasic(basic -> basic.authenticationEntryPoint(
                        (request, response, denied) -> exceptionResolver.resolveException(request, response, null, denied)))
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
