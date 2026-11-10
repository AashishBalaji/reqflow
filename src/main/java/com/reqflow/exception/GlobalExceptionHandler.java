package com.reqflow.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Turns exceptions into small, consistent JSON error bodies instead of
 * Spring's default stack-trace-shaped responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(HttpStatus.CONFLICT, ex.getMessage()));
    }

    /** Thrown when BudgetLine's @Version catches two requisitions racing
     * against the same budget line's remaining() check. The caller lost
     * the race, not the request itself -- retrying (re-reading current
     * state first) is the correct client response, hence 409 rather than
     * 500. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(HttpStatus.CONFLICT,
                "This record was updated by another request at the same time. Please retry."));
    }

    /** Catches violations of DB-level constraints the entities declare but
     * no service-layer pre-check enforces -- e.g. BudgetLine's unique
     * (department, category, fiscalYear) constraint. Without this, a
     * second POST for the same budget line falls through to a raw 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(HttpStatus.CONFLICT,
                "This conflicts with an existing record (e.g. a duplicate budget line for the same "
                        + "department/category/fiscal year)."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST, "Validation failed");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
