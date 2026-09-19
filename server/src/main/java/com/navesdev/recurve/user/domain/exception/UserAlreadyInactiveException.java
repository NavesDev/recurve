package com.navesdev.recurve.user.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

public class UserAlreadyInactiveException extends BusinessRuleException {

    public UserAlreadyInactiveException(UUID id) {
        super("Operator %s is already inactive".formatted(id));
    }
}
