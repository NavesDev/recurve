package com.navesdev.recurve.plan.service;

import java.util.UUID;

/** FR-02.6. Both fields are replaced; an absent description removes it. */
public record UpdatePlanCommand(UUID id, String name, String description) {
}
