package com.reqflow.model;

public enum RequisitionStatus {
    /** Failed an automatic policy check (budget or vendor risk) and is
     * waiting on a human -- or the triage agent -- to decide. */
    PENDING_REVIEW,
    /** Passed every automatic policy check at creation time; no human
     * or agent touched it. */
    AUTO_APPROVED,
    /** A human or the triage agent approved it after review. */
    APPROVED,
    /** A human or the triage agent rejected it after review. */
    REJECTED
}
