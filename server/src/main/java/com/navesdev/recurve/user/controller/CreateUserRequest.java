package com.navesdev.recurve.user.controller;

import java.util.Set;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.service.CreateUserCommand;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(

        @NotBlank @Size(max = 120) String name,

        @NotBlank @Email @Size(max = 255) String email,

        // BCrypt only considers the first 72 bytes; a longer password would
        // be silently truncated, so it is rejected instead.
        @NotBlank @Size(min = 8, max = 72) String password,

        Set<Permission> permissions) {

    public CreateUserCommand toCommand() {
        return new CreateUserCommand(name, email, password, permissions == null ? Set.of() : permissions);
    }
}
