package com.reqflow.repository;

import com.reqflow.model.BudgetLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BudgetLineRepository extends JpaRepository<BudgetLine, Long> {
    Optional<BudgetLine> findByDepartmentAndCategoryAndFiscalYear(String department, String category, int fiscalYear);
}
