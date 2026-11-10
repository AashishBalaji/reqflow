package com.reqflow.dto;

import com.reqflow.model.RequisitionEvent;

import java.time.Instant;

/** One entry in a requisition's activity log. */
public record RequisitionEventResponse(String activity, String detail, Instant occurredAt) {

    public static RequisitionEventResponse from(RequisitionEvent event) {
        return new RequisitionEventResponse(event.getActivity(), event.getDetail(), event.getOccurredAt());
    }
}
