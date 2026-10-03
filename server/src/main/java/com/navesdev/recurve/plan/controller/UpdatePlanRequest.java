package com.navesdev.recurve.plan.controller;

import java.util.UUID;

import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.UpdatePlanCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePlanRequest(

        @NotBlank @Size(max = PlanValidator.NAME_MAX_LENGTH) String name,

        @Size(max = PlanValidator.DESCRIPTION_MAX_LENGTH) String description) {

    public UpdatePlanCommand toCommand(UUID id) {
        return new UpdatePlanCommand(id, name, description);
    }
}
