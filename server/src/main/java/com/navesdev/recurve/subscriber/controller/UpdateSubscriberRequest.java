package com.navesdev.recurve.subscriber.controller;

import java.util.UUID;

import com.navesdev.recurve.subscriber.domain.SubscriberValidator;
import com.navesdev.recurve.subscriber.service.UpdateSubscriberCommand;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSubscriberRequest(

        @NotBlank @Size(max = SubscriberValidator.NAME_MAX_LENGTH) String name,

        @NotBlank @Email @Size(max = SubscriberValidator.EMAIL_MAX_LENGTH) String email,

        /** CPF or CNPJ, with or without punctuation; its check digits are the domain's to judge. */
        @NotBlank @Size(max = SubscriberValidator.DOCUMENT_MAX_LENGTH) String document) {

    public UpdateSubscriberCommand toCommand(UUID id) {
        return new UpdateSubscriberCommand(id, name, email, document);
    }
}
