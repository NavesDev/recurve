package com.navesdev.recurve.user.controller;

import java.util.Set;
import java.util.UUID;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.UserValidator;
import com.navesdev.recurve.user.service.UpdateUserCommand;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(

        @NotBlank @Size(max = UserValidator.NAME_MAX_LENGTH) String name,

        @NotBlank @Email @Size(max = UserValidator.EMAIL_MAX_LENGTH) String email,

        Set<Permission> permissions) {

    public UpdateUserCommand toCommand(UUID id) {
        return new UpdateUserCommand(id, name, email, permissions == null ? Set.of() : permissions);
    }
}
