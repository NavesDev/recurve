package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.NotFoundException;

public class PriceNotFoundException extends NotFoundException {

    public PriceNotFoundException(UUID id) {
        super("Price %s not found".formatted(id));
    }
}
