package com.reqflow.dto;

import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionEvent;
import com.reqflow.model.RequisitionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** What the API returns for a PurchaseRequisition. `events` is the full
 * activity log (see RequisitionEvent) -- callers that want true
 * process-mining-style analysis (activity sequences, queue-time vs.
 * decision-time) read it from here rather than from the two summary
 * timestamps below, which only ever show total elapsed time. */
public record RequisitionResponse(Long id, String requesterName, String department, Long vendorId,
                                   String category, BigDecimal amount, String justification,
                                   RequisitionStatus status, String decidedBy, String decisionReason,
                                   Instant createdAt, Instant decidedAt,
                                   List<RequisitionEventResponse> events) {

    public static RequisitionResponse from(PurchaseRequisition r, List<RequisitionEvent> events) {
        return new RequisitionResponse(r.getId(), r.getRequesterName(), r.getDepartment(), r.getVendorId(),
                r.getCategory(), r.getAmount(), r.getJustification(), r.getStatus(), r.getDecidedBy(),
                r.getDecisionReason(), r.getCreatedAt(), r.getDecidedAt(),
                events.stream().map(RequisitionEventResponse::from).toList());
    }
}
