package com.navesdev.recurve.subscriber.service;

import java.util.UUID;

/** FR-03.1. */
public record CreateSubscriberCommand(String name, String email, String document, UUID planPriceId) {
}
