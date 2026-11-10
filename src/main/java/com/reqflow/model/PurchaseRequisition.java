package com.reqflow.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "requisitions")
public class PurchaseRequisition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String requesterName;

    @NotBlank
    private String department;

    @NotNull
    private Long vendorId;

    @NotBlank
    private String category;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    @NotBlank
    private String justification;

    @Enumerated(EnumType.STRING)
    private RequisitionStatus status = RequisitionStatus.PENDING_REVIEW;

    /** Who or what made the final approve/reject call, and why -- filled
     * in whether that's the auto-approval rule at creation, a human
     * reviewer, or the AI triage agent (see agent/triage_agent.py). This
     * is the audit trail a real procurement system needs: every decision
     * traceable to an actor and a stated reason, not a silent status flip. */
    private String decidedBy;
    private String decisionReason;

    private Instant createdAt = Instant.now();
    private Instant decidedAt;

    protected PurchaseRequisition() { }

    public PurchaseRequisition(String requesterName, String department, Long vendorId,
                                String category, BigDecimal amount, String justification) {
        this.requesterName = requesterName;
        this.department = department;
        this.vendorId = vendorId;
        this.category = category;
        this.amount = amount;
        this.justification = justification;
    }

    public void decide(RequisitionStatus status, String decidedBy, String reason) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decisionReason = reason;
        this.decidedAt = Instant.now();
    }

    /** Leaves the requisition at PENDING_REVIEW (its default) but records
     * why the auto-approval policy didn't clear it, so a reviewer opens it
     * already knowing what to check. Not a decision: decidedBy/decidedAt
     * stay unset, since nobody has actually decided anything yet. */
    public void flagForReview(String reason) {
        this.status = RequisitionStatus.PENDING_REVIEW;
        this.decisionReason = reason;
    }

    public Long getId() { return id; }
    public String getRequesterName() { return requesterName; }
    public String getDepartment() { return department; }
    public Long getVendorId() { return vendorId; }
    public String getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public String getJustification() { return justification; }
    public RequisitionStatus getStatus() { return status; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionReason() { return decisionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
