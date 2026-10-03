package com.navesdev.recurve.payment.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.payment.gateway.AsaasProperties;
import com.navesdev.recurve.payment.gateway.PaymentGatewayConfig;
import com.navesdev.recurve.payment.service.GatewayEvent;
import com.navesdev.recurve.payment.service.PaymentService;

/**
 * Where Asaas reports what happened to its charges (FR-04.7). The one
 * route no operator calls: {@code SecurityConfig} lets it through, and it
 * answers to Asaas's token instead — the {@code asaas-access-token} header
 * Asaas sends on every call, compared in constant time so the comparison
 * says nothing about how much of a guess was right. A missing or wrong
 * token is a 401 and reaches nothing.
 *
 * <p>Any event Recurve does not act on, or about a charge it does not hold,
 * is still answered 200: an error would make Asaas retry it and, after
 * enough of them, pause the whole queue.
 */
@RestController
@RequestMapping("/api/webhooks/asaas")
@ConditionalOnProperty(name = PaymentGatewayConfig.PROPERTY, havingValue = "asaas")
public class AsaasWebhookController {

    private final PaymentService service;
    private final byte[] token;

    public AsaasWebhookController(PaymentService service, AsaasProperties properties) {
        this.service = service;
        this.token = properties.webhookToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(name = "asaas-access-token", required = false) String sentToken,
            @RequestBody AsaasEvent event) {

        if (sentToken == null || !MessageDigest.isEqual(token, sentToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BadCredentialsException("Webhook token missing or wrong");
        }
        AsaasEvent.Charge charge = event.payment();
        service.handle(new GatewayEvent(event.event(),
                charge == null ? null : charge.externalReference(),
                charge == null ? null : charge.id()));
        return ResponseEntity.ok().build();
    }

    /** Asaas's envelope, reduced to what Recurve reads; every other field is ignored. */
    record AsaasEvent(String event, Charge payment) {

        record Charge(String id, String externalReference) {
        }
    }
}
