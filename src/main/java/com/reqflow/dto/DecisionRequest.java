package com.reqflow.dto;

import jakarta.validation.constraints.NotBlank;

/** The body a human reviewer OR the AI triage agent sends to approve or
 * reject a pending requisition. decidedBy identifies the actor making the
 * call ("agent:claude-triage-v1" or a human reviewer's name) so the audit
 * trail on PurchaseRequisition always says who decided, not just what. */
public record DecisionRequest(
        @NotBlank String decidedBy,
        @NotBlank String reason
) {
}
