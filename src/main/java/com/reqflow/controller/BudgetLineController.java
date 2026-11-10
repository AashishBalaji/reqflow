package com.reqflow.controller;

import com.reqflow.dto.CreateBudgetLineRequest;
import com.reqflow.model.BudgetLine;
import com.reqflow.service.BudgetLineService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/budget-lines")
public class BudgetLineController {

    private final BudgetLineService budgetLineService;

    public BudgetLineController(BudgetLineService budgetLineService) {
        this.budgetLineService = budgetLineService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetLine create(@Valid @RequestBody CreateBudgetLineRequest request) {
        return budgetLineService.create(request);
    }

    @GetMapping
    public List<BudgetLine> findAll() {
        return budgetLineService.findAll();
    }

    @GetMapping("/{id}")
    public BudgetLine findById(@PathVariable Long id) {
        return budgetLineService.findById(id);
    }
}
