package com.navesdev.recurve.subscriber.domain;

/**
 * Where a subscription stands. {@code PAST_DUE} is reached by a failed
 * charge (FR-04.3) and left by a confirmed one (FR-04.2); both arrive with
 * the {@code payment} feature.
 */
public enum SubscriberStatus {

    ACTIVE,
    PAST_DUE,
    CANCELED
}
