package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.NotFoundException;

public class PlanNotFoundException extends NotFoundException {

    public PlanNotFoundException(UUID id) {
        super("Plan %s not found".formatted(id));
    }
}
