package com.navesdev.recurve.plan.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.BillingInterval;

/** FR-02.2. */
public record AddPriceCommand(UUID planId, BigDecimal price, String currency, BillingInterval interval) {
}
