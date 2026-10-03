package com.navesdev.recurve.auth.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.navesdev.recurve.user.service.UserService;

/**
 * The pieces signing in is built from: one key both signs and verifies
 * (HS256 — the server is the only party that does either), and the
 * email-and-password check HTTP Basic already uses.
 */
@Configuration
@EnableConfigurationProperties(TokenProperties.class)
public class TokenConfig {

    @Bean
    public JwtEncoder jwtEncoder(TokenProperties properties) {
        return NimbusJwtEncoder.withSecretKey(properties.key()).algorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Checks signature and expiry against the application's {@link Clock},
     * with no leeway: one server issues and verifies, so there is no clock
     * drift to forgive.
     */
    @Bean
    public JwtDecoder jwtDecoder(TokenProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.key())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator expiry = new JwtTimestampValidator(Duration.ZERO);
        expiry.setClock(clock);
        decoder.setJwtValidator(expiry);
        return decoder;
    }

    /**
     * What {@code SecurityConfig} turns a verified token into. Typed as the
     * abstraction, so the shared security configuration never imports this
     * feature.
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> operatorTokenConverter(UserService userService) {
        return new OperatorTokenConverter(userService);
    }

    /**
     * Email and password against {@code OperatorDetailsService} (BR-09
     * included). Being a bean, it is also what HTTP Basic authenticates
     * with, so both ways in check credentials the same way.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService operators, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(operators);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
