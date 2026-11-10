package com.reqflow.dto;

import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** What the API returns for a PurchaseRequisition. */
public record RequisitionResponse(Long id, String requesterName, String department, Long vendorId,
                                   String category, BigDecimal amount, String justification,
                                   RequisitionStatus status, String decidedBy, String decisionReason,
                                   Instant createdAt, Instant decidedAt) {

    public static RequisitionResponse from(PurchaseRequisition r) {
        return new RequisitionResponse(r.getId(), r.getRequesterName(), r.getDepartment(), r.getVendorId(),
                r.getCategory(), r.getAmount(), r.getJustification(), r.getStatus(), r.getDecidedBy(),
                r.getDecisionReason(), r.getCreatedAt(), r.getDecidedAt());
    }
}
