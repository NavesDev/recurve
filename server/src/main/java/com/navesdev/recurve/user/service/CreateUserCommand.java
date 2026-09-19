package com.navesdev.recurve.user.service;

import java.util.Set;

import com.navesdev.recurve.user.domain.Permission;

/** FR-01.1. The password arrives raw and is hashed by the service. */
public record CreateUserCommand(String name, String email, String rawPassword, Set<Permission> permissions) {
}
