package com.reqflow.service;

import com.reqflow.config.ApprovalPolicyProperties;
import com.reqflow.dto.CreateBudgetLineRequest;
import com.reqflow.dto.CreateRequisitionRequest;
import com.reqflow.dto.CreateVendorRequest;
import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionEvent;
import com.reqflow.model.RequisitionStatus;
import com.reqflow.model.Vendor;
import com.reqflow.repository.BudgetLineRepository;
import com.reqflow.repository.RequisitionRepository;
import com.reqflow.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the one piece of logic this whole project exists to
 * demonstrate: does RequisitionService route a requisition to
 * AUTO_APPROVED or PENDING_REVIEW for the right reason, and does the
 * approve/reject path correctly refuse to re-decide something already
 * decided.
 */
@SpringBootTest
@Transactional
class RequisitionServiceTest {

    @Autowired private RequisitionService requisitionService;
    @Autowired private VendorService vendorService;
    @Autowired private BudgetLineService budgetLineService;
    @Autowired private RequisitionEventService requisitionEventService;
    @Autowired private VendorRepository vendorRepository;
    @Autowired private BudgetLineRepository budgetLineRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private ApprovalPolicyProperties policy;

    private int currentFiscalYear;
    private Long goodVendorId;
    private Long riskyVendorId;

    @BeforeEach
    void setUp() {
        requisitionRepository.deleteAll();
        budgetLineRepository.deleteAll();
        vendorRepository.deleteAll();

        currentFiscalYear = Instant.now().atZone(ZoneOffset.UTC).getYear();

        goodVendorId = vendorService.create(new CreateVendorRequest("Reliable Supplier", "office_supplies", 10)).getId();
        riskyVendorId = vendorService.create(new CreateVendorRequest("Risky Supplier", "office_supplies", 95)).getId();

        budgetLineService.create(new CreateBudgetLineRequest(
                "Engineering", "office_supplies", currentFiscalYear, new BigDecimal("10000.00")));
    }

    @Test
    void autoApprovesWhenUnderCeilingBudgetAndRisk() {
        PurchaseRequisition req = requisitionService.create(new CreateRequisitionRequest(
                "Alice", "Engineering", goodVendorId, "office_supplies", new BigDecimal("500.00"), "paper"));

        assertEquals(RequisitionStatus.AUTO_APPROVED, req.getStatus());
        assertEquals("system:auto-approval-policy", req.getDecidedBy());
        assertNotNull(req.getDecidedAt());
    }

    @Test
    void flagsWhenAmountExceedsCeiling() {
        BigDecimal overCeiling = policy.getAutoApprovalCeiling().add(new BigDecimal("1.00"));
        PurchaseRequisition req = requisitionService.create(new CreateRequisitionRequest(
                "Bob", "Engineering", goodVendorId, "office_supplies", overCeiling, "big order"));

        assertEquals(RequisitionStatus.PENDING_REVIEW, req.getStatus());
        assertTrue(req.getDecisionReason().contains("exceeds auto-approval ceiling"));
        assertNull(req.getDecidedBy());
    }

    @Test
    void flagsWhenVendorRiskAtOrAboveCeiling() {
        PurchaseRequisition req = requisitionService.create(new CreateRequisitionRequest(
                "Carol", "Engineering", riskyVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));

        assertEquals(RequisitionStatus.PENDING_REVIEW, req.getStatus());
        assertTrue(req.getDecisionReason().contains("risk score 95"));
    }

    @Test
    void flagsWhenNoBudgetLineTracked() {
        PurchaseRequisition req = requisitionService.create(new CreateRequisitionRequest(
                "Dana", "Sales", goodVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));

        assertEquals(RequisitionStatus.PENDING_REVIEW, req.getStatus());
        assertTrue(req.getDecisionReason().contains("No budget line tracked"));
    }

    @Test
    void flagsWhenAmountExceedsRemainingBudget() {
        PurchaseRequisition req = requisitionService.create(new CreateRequisitionRequest(
                "Eve", "Engineering", goodVendorId, "office_supplies", new BigDecimal("50000.00"), "huge order"));

        assertEquals(RequisitionStatus.PENDING_REVIEW, req.getStatus());
        assertTrue(req.getDecisionReason().contains("exceeds remaining budget"));
    }

    @Test
    void autoApprovalCommitsSpendToBudgetLine() {
        requisitionService.create(new CreateRequisitionRequest(
                "Frank", "Engineering", goodVendorId, "office_supplies", new BigDecimal("1000.00"), "supplies"));

        var line = budgetLineService.find("Engineering", "office_supplies", currentFiscalYear).orElseThrow();
        assertEquals(new BigDecimal("9000.00"), line.remaining());
    }

    @Test
    void approveMovesPendingRequisitionToApproved() {
        PurchaseRequisition pending = requisitionService.create(new CreateRequisitionRequest(
                "Grace", "Engineering", riskyVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));

        PurchaseRequisition approved = requisitionService.approve(
                pending.getId(), "agent:claude-triage-v1", "Reviewed manually, acceptable risk");

        assertEquals(RequisitionStatus.APPROVED, approved.getStatus());
        assertEquals("agent:claude-triage-v1", approved.getDecidedBy());
        assertNotNull(approved.getDecidedAt());
    }

    @Test
    void cannotReDecideAnAlreadyDecidedRequisition() {
        PurchaseRequisition approved = requisitionService.create(new CreateRequisitionRequest(
                "Heidi", "Engineering", goodVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));
        assertEquals(RequisitionStatus.AUTO_APPROVED, approved.getStatus());

        assertThrows(IllegalStateException.class, () ->
                requisitionService.reject(approved.getId(), "someone", "too late"));
    }

    @Test
    void vendorValidationFailsFastForUnknownVendor() {
        assertThrows(com.reqflow.exception.ResourceNotFoundException.class, () ->
                requisitionService.create(new CreateRequisitionRequest(
                        "Ivan", "Engineering", 9999L, "office_supplies", new BigDecimal("500.00"), "supplies")));
    }

    @Test
    void autoApprovalRecordsSubmittedThenAutoApprovedEvents() {
        PurchaseRequisition req = requisitionService.create(new CreateRequisitionRequest(
                "Judy", "Engineering", goodVendorId, "office_supplies", new BigDecimal("500.00"), "paper"));

        List<RequisitionEvent> events = requisitionEventService.findForRequisition(req.getId());
        assertEquals(2, events.size());
        assertEquals("SUBMITTED", events.get(0).getActivity());
        assertEquals("AUTO_APPROVED", events.get(1).getActivity());
        assertEquals(req.getDecisionReason(), events.get(1).getDetail());
    }

    @Test
    void flaggingRecordsSubmittedThenFlaggedEventWithTheReason() {
        PurchaseRequisition req = requisitionService.create(new CreateRequisitionRequest(
                "Kevin", "Engineering", riskyVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));

        List<RequisitionEvent> events = requisitionEventService.findForRequisition(req.getId());
        assertEquals(2, events.size());
        assertEquals("SUBMITTED", events.get(0).getActivity());
        assertEquals("FLAGGED_FOR_REVIEW", events.get(1).getActivity());
        assertTrue(events.get(1).getDetail().contains("risk score 95"));
    }

    @Test
    void flaggedReasonSurvivesOnTheEventLogAfterTheFinalDecisionOverwritesIt() {
        // decisionReason on the requisition itself gets overwritten by
        // decide() -- the whole reason this event log exists. Confirm the
        // original flag reason is still readable from the event, even
        // after approve() has moved the requisition past PENDING_REVIEW
        // and replaced decisionReason with the approver's own text.
        PurchaseRequisition flagged = requisitionService.create(new CreateRequisitionRequest(
                "Laura", "Engineering", riskyVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));
        String originalFlagReason = flagged.getDecisionReason();

        requisitionService.approve(flagged.getId(), "someone", "approved on manual review");

        List<RequisitionEvent> events = requisitionEventService.findForRequisition(flagged.getId());
        assertEquals(originalFlagReason, events.get(1).getDetail());
        assertEquals("APPROVED", events.get(2).getActivity());
        assertEquals("approved on manual review", events.get(2).getDetail());
    }

    @Test
    void openForReviewRecordsAnEventWithoutChangingStatus() {
        PurchaseRequisition flagged = requisitionService.create(new CreateRequisitionRequest(
                "Mallory", "Engineering", riskyVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));

        PurchaseRequisition opened = requisitionService.openForReview(flagged.getId());

        assertEquals(RequisitionStatus.PENDING_REVIEW, opened.getStatus());
        List<RequisitionEvent> events = requisitionEventService.findForRequisition(flagged.getId());
        assertEquals(3, events.size());
        assertEquals("OPENED_FOR_REVIEW", events.get(2).getActivity());
    }

    @Test
    void cannotOpenAnAlreadyDecidedRequisition() {
        PurchaseRequisition approved = requisitionService.create(new CreateRequisitionRequest(
                "Niaj", "Engineering", goodVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));
        assertEquals(RequisitionStatus.AUTO_APPROVED, approved.getStatus());

        assertThrows(IllegalStateException.class, () -> requisitionService.openForReview(approved.getId()));
    }

    @Test
    void rejectRecordsARejectedEventWithTheGivenReason() {
        PurchaseRequisition flagged = requisitionService.create(new CreateRequisitionRequest(
                "Oscar", "Engineering", riskyVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));

        requisitionService.reject(flagged.getId(), "someone", "risk too high even on review");

        List<RequisitionEvent> events = requisitionEventService.findForRequisition(flagged.getId());
        RequisitionEvent last = events.get(events.size() - 1);
        assertEquals("REJECTED", last.getActivity());
        assertEquals("risk too high even on review", last.getDetail());
    }
}
