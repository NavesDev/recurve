package com.navesdev.recurve.user.service;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Loads the operator for authentication.
 *
 * <p>Internal by design: it runs inside the authentication filter, before
 * any principal exists, so it carries no {@code @PreAuthorize} — a check
 * here could never pass. Nothing else may call it.
 */
@Service
@RequiredArgsConstructor
public class OperatorDetailsService implements UserDetailsService {

    private final UserRepository repository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        User operator = repository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("No operator with email " + email));

        return org.springframework.security.core.userdetails.User
                .withUsername(operator.getEmail())
                .password(operator.getPasswordHash())
                // BR-09: an inactive operator can neither sign in nor operate.
                .disabled(!operator.isActive())
                .authorities(operator.authorities().stream()
                        .map(permission -> new SimpleGrantedAuthority(permission.name()))
                        .toList())
                .build();
    }
}
