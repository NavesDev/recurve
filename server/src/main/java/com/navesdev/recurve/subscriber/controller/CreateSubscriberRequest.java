package com.navesdev.recurve.subscriber.controller;

import java.util.UUID;

import com.navesdev.recurve.subscriber.domain.SubscriberValidator;
import com.navesdev.recurve.subscriber.service.CreateSubscriberCommand;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSubscriberRequest(

        @NotBlank @Size(max = SubscriberValidator.NAME_MAX_LENGTH) String name,

        @NotBlank @Email @Size(max = SubscriberValidator.EMAIL_MAX_LENGTH) String email,

        @NotNull UUID planPriceId) {

    public CreateSubscriberCommand toCommand() {
        return new CreateSubscriberCommand(name, email, planPriceId);
    }
}
