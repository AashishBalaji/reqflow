package com.reqflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateBudgetLineRequest(
        @NotBlank String department,
        @NotBlank String category,
        @NotNull Integer fiscalYear,
        @NotNull @DecimalMin("0.0") BigDecimal allocatedAmount
) {
}
