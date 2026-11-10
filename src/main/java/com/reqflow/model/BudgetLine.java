package com.reqflow.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@Entity
@Table(name = "budget_lines", uniqueConstraints = @UniqueConstraint(columnNames = {"department", "category", "fiscalYear"}))
public class BudgetLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String department;

    @NotBlank
    private String category;

    @NotNull
    private Integer fiscalYear;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal allocatedAmount;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal spentAmount = BigDecimal.ZERO;

    protected BudgetLine() { }

    public BudgetLine(String department, String category, int fiscalYear, BigDecimal allocatedAmount) {
        this.department = department;
        this.category = category;
        this.fiscalYear = fiscalYear;
        this.allocatedAmount = allocatedAmount;
    }

    public BigDecimal remaining() {
        return allocatedAmount.subtract(spentAmount);
    }

    public void commit(BigDecimal amount) {
        this.spentAmount = this.spentAmount.add(amount);
    }

    public Long getId() { return id; }
    public String getDepartment() { return department; }
    public String getCategory() { return category; }
    public Integer getFiscalYear() { return fiscalYear; }
    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal allocatedAmount) { this.allocatedAmount = allocatedAmount; }
    public BigDecimal getSpentAmount() { return spentAmount; }
    public void setSpentAmount(BigDecimal spentAmount) { this.spentAmount = spentAmount; }
}
