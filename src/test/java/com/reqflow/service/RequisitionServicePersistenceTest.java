package com.reqflow.service;

import com.reqflow.dto.CreateBudgetLineRequest;
import com.reqflow.dto.CreateRequisitionRequest;
import com.reqflow.dto.CreateVendorRequest;
import com.reqflow.model.BudgetLine;
import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionStatus;
import com.reqflow.repository.BudgetLineRepository;
import com.reqflow.repository.RequisitionRepository;
import com.reqflow.repository.VendorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deliberately NOT @Transactional at the class level, unlike
 * RequisitionServiceTest. A wrapping test transaction keeps every entity
 * attached for the whole test, which is exactly what let a real bug slip
 * through once: RequisitionService.applyAutoApprovalPolicy used to mutate
 * a BudgetLine without ever saving it, and the in-memory mutation looked
 * identical to a persisted one as long as nothing forced a fresh read.
 * These tests force that fresh read -- each one re-fetches the budget
 * line through BudgetLineService.find in a brand new transaction, the way
 * a second, later HTTP request actually would.
 */
@SpringBootTest
class RequisitionServicePersistenceTest {

    @Autowired private RequisitionService requisitionService;
    @Autowired private VendorService vendorService;
    @Autowired private BudgetLineService budgetLineService;
    @Autowired private VendorRepository vendorRepository;
    @Autowired private BudgetLineRepository budgetLineRepository;
    @Autowired private RequisitionRepository requisitionRepository;

    private int currentFiscalYear;
    private Long goodVendorId;
    private Long riskyVendorId;

    @BeforeEach
    void setUp() {
        currentFiscalYear = Instant.now().atZone(ZoneOffset.UTC).getYear();
        goodVendorId = vendorService.create(new CreateVendorRequest("Reliable Supplier", "office_supplies", 10)).getId();
        riskyVendorId = vendorService.create(new CreateVendorRequest("Risky Supplier", "office_supplies", 95)).getId();
        budgetLineService.create(new CreateBudgetLineRequest(
                "Engineering", "office_supplies", currentFiscalYear, new BigDecimal("10000.00")));
    }

    @AfterEach
    void tearDown() {
        requisitionRepository.deleteAll();
        budgetLineRepository.deleteAll();
        vendorRepository.deleteAll();
    }

    @Test
    void autoApprovalCommitActuallyPersists() {
        requisitionService.create(new CreateRequisitionRequest(
                "Frank", "Engineering", goodVendorId, "office_supplies", new BigDecimal("1000.00"), "supplies"));

        var persisted = budgetLineService.find("Engineering", "office_supplies", currentFiscalYear).orElseThrow();
        assertEquals(new BigDecimal("9000.00"), persisted.remaining());
    }

    @Test
    void manualApprovalOfBudgetFlaggedRequisitionCommitsBudgetToo() {
        PurchaseRequisition flagged = requisitionService.create(new CreateRequisitionRequest(
                "Grace", "Engineering", goodVendorId, "office_supplies", new BigDecimal("50000.00"), "huge order"));
        assertEquals(RequisitionStatus.PENDING_REVIEW, flagged.getStatus());

        requisitionService.approve(flagged.getId(), "cfo:override", "Approved despite budget, one-time exception");

        var persisted = budgetLineService.find("Engineering", "office_supplies", currentFiscalYear).orElseThrow();
        assertEquals(new BigDecimal("-40000.00"), persisted.remaining());
    }

    @Test
    void manualApprovalForVendorRiskFlagDoesNotTouchUntrackedBudget() {
        // Confirms approve() only commits budget when a line exists -- it
        // shouldn't throw or fabricate one for a risk-flagged requisition.
        PurchaseRequisition flagged = requisitionService.create(new CreateRequisitionRequest(
                "Heidi", "Engineering", riskyVendorId, "office_supplies", new BigDecimal("500.00"), "supplies"));
        assertEquals(RequisitionStatus.PENDING_REVIEW, flagged.getStatus());

        PurchaseRequisition approved = requisitionService.approve(flagged.getId(), "someone", "acceptable risk");

        assertEquals(RequisitionStatus.APPROVED, approved.getStatus());
        var persisted = budgetLineService.find("Engineering", "office_supplies", currentFiscalYear).orElseThrow();
        assertEquals(new BigDecimal("9500.00"), persisted.remaining());
    }

    /**
     * Exercises the @Version field directly, without needing real
     * threads: load the same BudgetLine row through two independent
     * reads (standing in for two concurrent requisitions both passing the
     * remaining() check before either commits), save the first --
     * succeeds and bumps the version -- then save the second, stale copy.
     * That second save is exactly what a losing concurrent request would
     * hit, and it must throw rather than silently overwrite the first
     * commit (which is what would over-spend the budget).
     */
    @Test
    void concurrentBudgetCommitsAreRejectedByOptimisticLock() {
        BudgetLine firstRead = budgetLineService.find("Engineering", "office_supplies", currentFiscalYear).orElseThrow();
        BudgetLine secondRead = budgetLineService.find("Engineering", "office_supplies", currentFiscalYear).orElseThrow();

        firstRead.commit(new BigDecimal("1000.00"));
        budgetLineService.save(firstRead);

        secondRead.commit(new BigDecimal("2000.00"));
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> budgetLineService.save(secondRead));

        // The loser's write never landed -- only the first commit counts.
        var persisted = budgetLineService.find("Engineering", "office_supplies", currentFiscalYear).orElseThrow();
        assertEquals(new BigDecimal("9000.00"), persisted.remaining());
    }
}
