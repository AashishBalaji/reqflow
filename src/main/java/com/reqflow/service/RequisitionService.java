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

import java.math.BigDecimal;
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
    private final ApprovalPolicyProperties policy;

    public RequisitionService(RequisitionRepository requisitionRepository,
                               VendorService vendorService,
                               BudgetLineService budgetLineService,
                               ApprovalPolicyProperties policy) {
        this.requisitionRepository = requisitionRepository;
        this.vendorService = vendorService;
        this.budgetLineService = budgetLineService;
        this.policy = policy;
    }

    public PurchaseRequisition create(CreateRequisitionRequest request) {
        // Fail fast and loud if the vendor doesn't exist at all - that's a
        // client error, not something to silently flag for review.
        Vendor vendor = vendorService.findById(request.vendorId());

        PurchaseRequisition requisition = new PurchaseRequisition(
                request.requesterName(), request.department(), request.vendorId(),
                request.category(), request.amount(), request.justification());

        applyAutoApprovalPolicy(requisition, vendor);
        return requisitionRepository.save(requisition);
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

        int currentFiscalYear = java.time.Instant.now().atZone(ZoneOffset.UTC).getYear();
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

        budgetLine.get().commit(requisition.getAmount());
        requisition.decide(RequisitionStatus.AUTO_APPROVED, "system:auto-approval-policy",
                "Within ceiling, budget, and vendor-risk thresholds");
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

    public PurchaseRequisition approve(Long id, String decidedBy, String reason) {
        PurchaseRequisition requisition = findById(id);
        requireReviewable(requisition);
        requisition.decide(RequisitionStatus.APPROVED, decidedBy, reason);
        return requisitionRepository.save(requisition);
    }

    public PurchaseRequisition reject(Long id, String decidedBy, String reason) {
        PurchaseRequisition requisition = findById(id);
        requireReviewable(requisition);
        requisition.decide(RequisitionStatus.REJECTED, decidedBy, reason);
        return requisitionRepository.save(requisition);
    }

    private void requireReviewable(PurchaseRequisition requisition) {
        if (requisition.getStatus() != RequisitionStatus.PENDING_REVIEW) {
            throw new IllegalStateException(
                    "Requisition " + requisition.getId() + " is " + requisition.getStatus()
                            + " and is not open for a new decision");
        }
    }
}
