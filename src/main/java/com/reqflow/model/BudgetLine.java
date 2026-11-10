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

    /** Optimistic lock: without it, two requisitions against the same
     * budget line could both read remaining() before either commits its
     * spend, and both pass the headroom check -- over-committing the
     * budget. With it, the loser of that race gets an
     * ObjectOptimisticLockingFailureException instead of a silent
     * over-commit (mapped to a 409 by GlobalExceptionHandler).
     * columnDefinition gives existing/seeded rows a real 0 instead of
     * NULL -- Hibernate's version-increment NPEs on a null version. */
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

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
