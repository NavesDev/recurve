package com.navesdev.recurve.user.service;

import java.util.Set;
import java.util.UUID;

import com.navesdev.recurve.user.domain.Permission;

/** FR-01.2. Permissions are replaced wholesale, not merged. */
public record UpdateUserCommand(UUID id, String name, String email, Set<Permission> permissions) {
}
