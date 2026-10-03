package com.navesdev.recurve.payment.service;

/**
 * Something the payment gateway reports about one of its charges, already
 * stripped of the vendor's envelope: what happened, and the two ways of
 * naming the charge — our payment id as the gateway's external reference,
 * and the gateway's own id. Either may be missing.
 */
public record GatewayEvent(String type, String externalReference, String externalId) {
}
