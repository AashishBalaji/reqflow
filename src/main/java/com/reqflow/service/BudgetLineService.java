package com.reqflow.service;

import com.reqflow.dto.CreateBudgetLineRequest;
import com.reqflow.exception.ResourceNotFoundException;
import com.reqflow.model.BudgetLine;
import com.reqflow.repository.BudgetLineRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BudgetLineService {

    private final BudgetLineRepository budgetLineRepository;

    public BudgetLineService(BudgetLineRepository budgetLineRepository) {
        this.budgetLineRepository = budgetLineRepository;
    }

    public BudgetLine create(CreateBudgetLineRequest request) {
        BudgetLine line = new BudgetLine(
                request.department(), request.category(), request.fiscalYear(), request.allocatedAmount());
        return budgetLineRepository.save(line);
    }

    public List<BudgetLine> findAll() {
        return budgetLineRepository.findAll();
    }

    public BudgetLine findById(Long id) {
        return budgetLineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No budget line with id " + id));
    }

    /** Returns empty when no budget line is tracked for that
     * department/category/year combination — callers decide what that
     * absence means (RequisitionService treats "untracked" as "can't
     * confirm there's room," so it flags rather than auto-approves). */
    public Optional<BudgetLine> find(String department, String category, int fiscalYear) {
        return budgetLineRepository.findByDepartmentAndCategoryAndFiscalYear(department, category, fiscalYear);
    }
}
