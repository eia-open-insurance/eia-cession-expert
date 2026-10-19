package com.eia.cession.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateCessionStatusRequest(
        @NotNull CessionStatus status,
        @NotNull @Min(0) Integer version
) {}
