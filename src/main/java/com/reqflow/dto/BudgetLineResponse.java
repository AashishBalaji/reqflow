package com.reqflow.dto;

import com.reqflow.model.BudgetLine;

import java.math.BigDecimal;

/** What the API returns for a BudgetLine. Exposes remaining() as a
 * first-class field (callers shouldn't have to recompute
 * allocated - spent themselves) and deliberately leaves out the entity's
 * @Version field -- that's a persistence concern for optimistic locking,
 * not something an API client has any use for. */
public record BudgetLineResponse(Long id, String department, String category, int fiscalYear,
                                  BigDecimal allocatedAmount, BigDecimal spentAmount, BigDecimal remaining) {

    public static BudgetLineResponse from(BudgetLine line) {
        return new BudgetLineResponse(line.getId(), line.getDepartment(), line.getCategory(),
                line.getFiscalYear(), line.getAllocatedAmount(), line.getSpentAmount(), line.remaining());
    }
}
