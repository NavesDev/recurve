package com.navesdev.recurve.shared.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * A token or HTTP Basic over a stateless chain (FR-05.1, FR-05.3), and
 * the one place that says which permission each route needs.
 *
 * <p>The token is checked by Spring's resource server and turned into the
 * principal by whatever {@code Converter<Jwt, …>} the application
 * declares (the {@code auth} feature's): this class depends on that
 * abstraction, never on the feature.
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

    /** The value of {@code recurve.payment.gateway} under which the Asaas webhook exists. */
    private static final String ASAAS_GATEWAY = "asaas";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            @Value("${recurve.docs.enabled:false}") boolean docsEnabled,
            @Value("${recurve.payment.gateway:fake}") String paymentGateway,
            Converter<Jwt, AbstractAuthenticationToken> tokenConverter) throws Exception {
        AuthenticationEntryPoint unauthenticated =
                (request, response, denied) -> exceptionResolver.resolveException(request, response, null, denied);
        return http
                // No cookie-based session to protect, and no browser form posts.
                .csrf(csrf -> csrf.disable())
                // NFR-10: origins from CorsConfig; none unless configured.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    // The contract is a shape, not data, and the Swagger UI has to
                    // fetch it before any credential exists. Only opened while
                    // DocsConfig serves it: off, the path answers 401 like any other.
                    if (docsEnabled) {
                        requests.requestMatchers(DocsConfig.PATH, DocsConfig.PATH + "/**").permitAll();
                    }
                    // FR-04.7: the payment gateway is no operator. Its webhook
                    // authenticates it by its own token, in the controller, and
                    // exists only while Asaas is the gateway: otherwise the path
                    // answers 401 like any other.
                    if (ASAAS_GATEWAY.equals(paymentGateway)) {
                        requests.requestMatchers(HttpMethod.POST, "/api/webhooks/asaas").permitAll();
                    }
                    requests
                        // FR-05.1: signing in is how a credential is obtained.
                        .requestMatchers(HttpMethod.POST, "/api/auth/token").permitAll()
                        // FR-01.5: rebuilding an index is a system operation.
                        .requestMatchers(HttpMethod.POST, "/api/users/reindex", "/api/plans/reindex",
                                "/api/subscribers/reindex", "/api/payments/reindex")
                            .hasAuthority("MANAGE_SYSTEM")
                        // BR-01: operators have no view permission; reading them is managing them.
                        .requestMatchers("/api/users/**").hasAuthority("MANAGE_USERS")
                        // BR-01: plans are read with VIEW_PLANS, which MANAGE_PLANS implies.
                        .requestMatchers(HttpMethod.GET, "/api/plans/**").hasAuthority("VIEW_PLANS")
                        // A price is part of its plan: changing either is managing plans.
                        .requestMatchers("/api/plans/**", "/api/prices/**").hasAuthority("MANAGE_PLANS")
                        // BR-01: subscribers are read with VIEW_SUBSCRIBERS, which MANAGE_SUBSCRIBERS implies.
                        .requestMatchers(HttpMethod.GET, "/api/subscribers/**").hasAuthority("VIEW_SUBSCRIBERS")
                        .requestMatchers("/api/subscribers/**").hasAuthority("MANAGE_SUBSCRIBERS")
                        // BR-01: payments are read with VIEW_PAYMENTS, which MANAGE_PAYMENTS implies.
                        .requestMatchers(HttpMethod.GET, "/api/payments/**").hasAuthority("VIEW_PAYMENTS")
                        .requestMatchers("/api/payments/**").hasAuthority("MANAGE_PAYMENTS")
                        .anyRequest().authenticated();
                })
                // A refusal happens in the filter, before any controller; hand it
                // to the same resolver the controllers use so the 401 and the 403
                // carry the ApiError body every other error does. The default
                // entry point would answer a 401 with a Basic challenge, which
                // makes a browser pop its own login dialog over any client
                // (the Swagger UI included). An API client sends credentials
                // on every request; it needs no invitation.
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthenticated))
                // FR-05.3: same answer for a bad token as for bad Basic
                // credentials, with no Bearer challenge either.
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(tokenConverter))
                        .authenticationEntryPoint(unauthenticated))
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
