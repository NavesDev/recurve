package com.navesdev.recurve.payment.service;

import java.util.UUID;

/** FR-04.1: charge this subscriber's current cycle. */
public record RequestPaymentCommand(UUID subscriberId) {
}
