package com.reqflow.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateVendorRequest(
        @NotBlank String name,
        @NotBlank String category,
        @Min(0) @Max(100) int riskScore
) {
}
