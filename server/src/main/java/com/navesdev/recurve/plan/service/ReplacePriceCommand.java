package com.navesdev.recurve.plan.service;

import java.math.BigDecimal;
import java.util.UUID;

/** FR-02.7, BR-04: the cycle and currency are the replaced price's. */
public record ReplacePriceCommand(UUID priceId, BigDecimal price) {
}
