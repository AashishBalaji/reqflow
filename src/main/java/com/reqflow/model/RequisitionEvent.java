package com.reqflow.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One timestamped activity in a requisition's life, append-only. This is
 * what turns the two bare timestamps on PurchaseRequisition (createdAt,
 * decidedAt) into a genuine multi-activity event log: SUBMITTED,
 * AUTO_APPROVED, FLAGGED_FOR_REVIEW, OPENED_FOR_REVIEW, APPROVED, REJECTED.
 *
 * Critically, this also fixes a real limitation the original two-timestamp
 * design had: decisionReason on PurchaseRequisition gets overwritten the
 * moment a decision is made, so the original flag reason was unrecoverable
 * once a case closed. Each event keeps its own detail text forever, so the
 * full history -- why it was flagged, and later why it was decided -- stays
 * intact even after the requisition's own current-state fields move on.
 */
@Entity
@Table(name = "requisition_events")
public class RequisitionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requisition_id", nullable = false)
    private Long requisitionId;

    @Column(nullable = false)
    private String activity;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected RequisitionEvent() { }

    public RequisitionEvent(Long requisitionId, String activity, String detail, Instant occurredAt) {
        this.requisitionId = requisitionId;
        this.activity = activity;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getRequisitionId() { return requisitionId; }
    public String getActivity() { return activity; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
