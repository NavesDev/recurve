package com.navesdev.recurve.subscriber.service;

import java.util.UUID;

/** FR-03.7. */
public record UpdateSubscriberCommand(UUID id, String name, String email, String document) {
}
