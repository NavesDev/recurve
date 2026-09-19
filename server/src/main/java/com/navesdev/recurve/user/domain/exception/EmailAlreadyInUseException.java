package com.navesdev.recurve.user.domain.exception;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-02: operator email is unique. */
public class EmailAlreadyInUseException extends BusinessRuleException {

    public EmailAlreadyInUseException(String email) {
        super("Email %s is already in use".formatted(email));
    }
}
