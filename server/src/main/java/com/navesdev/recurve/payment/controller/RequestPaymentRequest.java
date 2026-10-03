package com.navesdev.recurve.payment.controller;

import java.util.UUID;

import com.navesdev.recurve.payment.service.RequestPaymentCommand;

import jakarta.validation.constraints.NotNull;

public record RequestPaymentRequest(@NotNull UUID subscriberId) {

    public RequestPaymentCommand toCommand() {
        return new RequestPaymentCommand(subscriberId);
    }
}
