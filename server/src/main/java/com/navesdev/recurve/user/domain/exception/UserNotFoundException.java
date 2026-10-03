package com.navesdev.recurve.user.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.NotFoundException;

public class UserNotFoundException extends NotFoundException {

    public UserNotFoundException(UUID id) {
        super("Operator %s not found".formatted(id));
    }

    private UserNotFoundException(String message) {
        super(message);
    }

    /** Looked up by email: the message does not repeat what was typed. */
    public static UserNotFoundException byEmail() {
        return new UserNotFoundException("Operator not found");
    }
}
