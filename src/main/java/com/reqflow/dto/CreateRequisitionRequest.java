package com.reqflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateRequisitionRequest(
        @NotBlank String requesterName,
        @NotBlank String department,
        @NotNull Long vendorId,
        @NotBlank String category,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String justification
) {
}
