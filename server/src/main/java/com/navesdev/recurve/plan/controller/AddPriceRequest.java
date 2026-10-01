package com.navesdev.recurve.plan.controller;

import java.math.BigDecimal;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.AddPriceCommand;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AddPriceRequest(

        @NotNull
        @DecimalMin(PlanValidator.PRICE_MIN)
        @Digits(integer = PlanValidator.PRICE_INTEGER_DIGITS, fraction = PlanValidator.PRICE_FRACTION_DIGITS)
        BigDecimal price,

        @NotBlank @Pattern(regexp = PlanValidator.CURRENCY_PATTERN) String currency,

        @NotNull BillingInterval interval) {

    public AddPriceCommand toCommand(UUID planId) {
        return new AddPriceCommand(planId, price, currency, interval);
    }
}
