package com.reqflow.controller;

import com.reqflow.dto.BudgetLineResponse;
import com.reqflow.dto.CreateBudgetLineRequest;
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
    public BudgetLineResponse create(@Valid @RequestBody CreateBudgetLineRequest request) {
        return BudgetLineResponse.from(budgetLineService.create(request));
    }

    @GetMapping
    public List<BudgetLineResponse> findAll() {
        return budgetLineService.findAll().stream().map(BudgetLineResponse::from).toList();
    }

    @GetMapping("/{id}")
    public BudgetLineResponse findById(@PathVariable Long id) {
        return BudgetLineResponse.from(budgetLineService.findById(id));
    }
}
