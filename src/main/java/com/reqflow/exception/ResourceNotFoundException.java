package com.reqflow.exception;

/** Thrown when a Vendor, BudgetLine, or PurchaseRequisition id is looked up
 * and doesn't exist. Mapped to a 404 by GlobalExceptionHandler. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
