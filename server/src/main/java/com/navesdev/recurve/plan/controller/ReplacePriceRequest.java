package com.navesdev.recurve.plan.controller;

import java.math.BigDecimal;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.ReplacePriceCommand;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Only the amount: the cycle and currency are the replaced price's (BR-04). */
public record ReplacePriceRequest(

        @NotNull
        @DecimalMin(PlanValidator.PRICE_MIN)
        @Digits(integer = PlanValidator.PRICE_INTEGER_DIGITS, fraction = PlanValidator.PRICE_FRACTION_DIGITS)
        BigDecimal price) {

    public ReplacePriceCommand toCommand(UUID priceId) {
        return new ReplacePriceCommand(priceId, price);
    }
}
