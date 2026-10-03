package com.navesdev.recurve.subscriber.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.NotFoundException;

public class SubscriberNotFoundException extends NotFoundException {

    public SubscriberNotFoundException(UUID id) {
        super("Subscriber %s not found".formatted(id));
    }
}
