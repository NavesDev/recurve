package com.navesdev.recurve.plan.controller;

import com.navesdev.recurve.plan.domain.PlanValidator;
import com.navesdev.recurve.plan.service.CreatePlanCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePlanRequest(

        @NotBlank @Size(max = PlanValidator.NAME_MAX_LENGTH) String name,

        @Size(max = PlanValidator.DESCRIPTION_MAX_LENGTH) String description) {

    public CreatePlanCommand toCommand() {
        return new CreatePlanCommand(name, description);
    }
}
