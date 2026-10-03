package com.navesdev.recurve.auth.controller;

import com.navesdev.recurve.user.domain.UserValidator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Only presence and length: whether the email is well formed is not this
 * request's question — a malformed one simply matches no operator.
 */
public record TokenRequest(
        @NotBlank @Size(max = UserValidator.EMAIL_MAX_LENGTH) String email,
        @NotBlank @Size(max = 72) String password) {
}
