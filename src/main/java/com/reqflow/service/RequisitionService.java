package com.reqflow.service;

import com.reqflow.config.ApprovalPolicyProperties;
import com.reqflow.dto.CreateRequisitionRequest;
import com.reqflow.exception.ResourceNotFoundException;
import com.reqflow.model.BudgetLine;
import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionStatus;
import com.reqflow.model.Vendor;
import com.reqflow.repository.RequisitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Owns the one rule that matters in this project: should a requisition clear
 * automatically, or does a human (or the AI triage agent standing in for
 * one) need to look at it?
 *
 * A requisition auto-approves only when ALL three hold:
 *   1. amount is at or under reqflow.policy.auto-approval-ceiling
 *   2. the matching budget line (department + category + current fiscal
 *      year) exists AND has enough remaining() to cover it
 *   3. the vendor exists, is active, and its riskScore is under
 *      reqflow.policy.vendor-risk-ceiling
 *
 * Any failure routes it to PENDING_REVIEW with a human-readable reason
 * recorded directly on the requisition, so a reviewer (or the agent) never
 * has to re-derive why it was flagged.
 */
@Service
public class RequisitionService {

    private final RequisitionRepository requisitionRepository;
    private final VendorService vendorService;
    private final BudgetLineService budgetLineService;
    private final RequisitionEventService requisitionEventService;
    private final ApprovalPolicyProperties policy;

    public RequisitionService(RequisitionRepository requisitionRepository,
                               VendorService vendorService,
                               BudgetLineService budgetLineService,
                               RequisitionEventService requisitionEventService,
                               ApprovalPolicyProperties policy) {
        this.requisitionRepository = requisitionRepository;
        this.vendorService = vendorService;
        this.budgetLineService = budgetLineService;
        this.requisitionEventService = requisitionEventService;
        this.policy = policy;
    }

    @Transactional
    public PurchaseRequisition create(CreateRequisitionRequest request) {
        // Fail fast and loud if the vendor doesn't exist at all - that's a
        // client error, not something to silently flag for review.
        Vendor vendor = vendorService.findById(request.vendorId());

        PurchaseRequisition requisition = new PurchaseRequisition(
                request.requesterName(), request.department(), request.vendorId(),
                request.category(), request.amount(), request.justification());

        applyAutoApprovalPolicy(requisition, vendor);
        PurchaseRequisition saved = requisitionRepository.save(requisition);

        // Recorded after save so the event carries the real (post-persist)
        // id. SUBMITTED always comes first in the sequence, whatever the
        // policy decided a moment later in the same call.
        requisitionEventService.record(saved.getId(), "SUBMITTED", null);
        if (saved.getStatus() == RequisitionStatus.AUTO_APPROVED) {
            requisitionEventService.record(saved.getId(), "AUTO_APPROVED", saved.getDecisionReason());
        } else {
            requisitionEventService.record(saved.getId(), "FLAGGED_FOR_REVIEW", saved.getDecisionReason());
        }
        return saved;
    }

    private void applyAutoApprovalPolicy(PurchaseRequisition requisition, Vendor vendor) {
        if (requisition.getAmount().compareTo(policy.getAutoApprovalCeiling()) > 0) {
            requisition.flagForReview("Amount %s exceeds auto-approval ceiling of %s"
                    .formatted(requisition.getAmount(), policy.getAutoApprovalCeiling()));
            return;
        }

        if (!vendor.isActive() || vendor.getRiskScore() >= policy.getVendorRiskCeiling()) {
            requisition.flagForReview("Vendor '%s' risk score %d is at or above the ceiling of %d%s"
                    .formatted(vendor.getName(), vendor.getRiskScore(), policy.getVendorRiskCeiling(),
                            vendor.isActive() ? "" : " (and vendor is inactive)"));
            return;
        }

        int currentFiscalYear = currentFiscalYear();
        Optional<BudgetLine> budgetLine = budgetLineService.find(
                requisition.getDepartment(), requisition.getCategory(), currentFiscalYear);

        if (budgetLine.isEmpty()) {
            requisition.flagForReview("No budget line tracked for %s/%s in FY%d - can't confirm there's room"
                    .formatted(requisition.getDepartment(), requisition.getCategory(), currentFiscalYear));
            return;
        }

        BigDecimal remaining = budgetLine.get().remaining();
        if (remaining.compareTo(requisition.getAmount()) < 0) {
            requisition.flagForReview("Amount %s exceeds remaining budget of %s for %s/%s FY%d"
                    .formatted(requisition.getAmount(), remaining, requisition.getDepartment(),
                            requisition.getCategory(), currentFiscalYear));
            return;
        }

        commitBudget(budgetLine.get(), requisition.getAmount());
        requisition.decide(RequisitionStatus.AUTO_APPROVED, "system:auto-approval-policy",
                "Within ceiling, budget, and vendor-risk thresholds");
    }

    /** Persists the spend immediately rather than relying on dirty-checking
     * alone -- @Transactional keeps this entity managed either way, but an
     * explicit save makes the write unmissable on review and is what
     * actually triggers the @Version check that catches two concurrent
     * requisitions both reading remaining() before either commits. */
    private void commitBudget(BudgetLine budgetLine, BigDecimal amount) {
        budgetLine.commit(amount);
        budgetLineService.save(budgetLine);
    }

    /** Budget lines are tracked per fiscal year; this project has no
     * separate fiscal-calendar config, so "current fiscal year" is simply
     * the calendar year in UTC. Pulled out once so create() and approve()
     * can't drift against each other on how that's computed. */
    private int currentFiscalYear() {
        return Instant.now().atZone(ZoneOffset.UTC).getYear();
    }

    public List<PurchaseRequisition> findAll() {
        return requisitionRepository.findAll();
    }

    public List<PurchaseRequisition> findByStatus(RequisitionStatus status) {
        return requisitionRepository.findByStatus(status);
    }

    public PurchaseRequisition findById(Long id) {
        return requisitionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No requisition with id " + id));
    }

    /**
     * A human or agent override of a flagged requisition. If a budget line
     * is tracked for its department/category/current fiscal year, this
     * records the spend against it too -- otherwise a requisition flagged
     * for insufficient budget, then approved anyway, would leave the
     * budget line looking like it still has room it doesn't. (A
     * requisition flagged for vendor risk rather than budget simply has
     * nothing new to commit here; commitBudget is only reached when a
     * line exists.)
     *
     * Deliberately has no amount/risk/budget floor of its own: this IS
     * the human-override path, so a reviewer can approve something the
     * policy rejected (including pushing a budget line negative) and the
     * decision is trusted as given, recorded under decidedBy. That makes
     * this the single most consequential call in the service -- there is
     * no safety net below it.
     */
    @Transactional
    public PurchaseRequisition approve(Long id, String decidedBy, String reason) {
        PurchaseRequisition requisition = findById(id);
        requireReviewable(requisition);

        budgetLineService.find(requisition.getDepartment(), requisition.getCategory(), currentFiscalYear())
                .ifPresent(line -> commitBudget(line, requisition.getAmount()));

        requisition.decide(RequisitionStatus.APPROVED, decidedBy, reason);
        PurchaseRequisition saved = requisitionRepository.save(requisition);
        requisitionEventService.record(saved.getId(), "APPROVED", reason);
        return saved;
    }

    @Transactional
    public PurchaseRequisition reject(Long id, String decidedBy, String reason) {
        PurchaseRequisition requisition = findById(id);
        requireReviewable(requisition);
        requisition.decide(RequisitionStatus.REJECTED, decidedBy, reason);
        PurchaseRequisition saved = requisitionRepository.save(requisition);
        requisitionEventService.record(saved.getId(), "REJECTED", reason);
        return saved;
    }

    /**
     * Marks a flagged requisition as picked up for review, without
     * deciding it -- the step a human opening a case, or the triage agent
     * starting to look at one, actually corresponds to. Doesn't exist
     * purely for completeness: it's what lets an event-log analysis (see
     * analytics/process_intelligence.py) decompose total cycle time into
     * queue-time (flagged -> opened) and decision-time (opened -> decided)
     * instead of only ever seeing one lump sum. Callable more than once;
     * only the first OPENED_FOR_REVIEW event is used for that split.
     */
    @Transactional
    public PurchaseRequisition openForReview(Long id) {
        PurchaseRequisition requisition = findById(id);
        requireReviewable(requisition);
        requisitionEventService.record(requisition.getId(), "OPENED_FOR_REVIEW", null);
        return requisition;
    }

    private void requireReviewable(PurchaseRequisition requisition) {
        if (requisition.getStatus() != RequisitionStatus.PENDING_REVIEW) {
            throw new IllegalStateException(
                    "Requisition " + requisition.getId() + " is " + requisition.getStatus()
                            + " and is not pending review");
        }
    }
}
