package com.reqflow.controller;

import com.reqflow.dto.CreateRequisitionRequest;
import com.reqflow.dto.DecisionRequest;
import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionStatus;
import com.reqflow.service.RequisitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The create endpoint is where the auto-approval policy runs. The
 * approve/reject endpoints below are the exact same "tools" a human
 * reviewer uses from a UI and that the AI triage agent
 * (agent/triage_agent.py) calls as function-calling tools -- there is no
 * separate "agent API"; the agent is just another caller of this REST
 * surface, which is the point of modeling it as tool-use rather than a
 * bespoke integration.
 */
@RestController
@RequestMapping("/api/requisitions")
public class RequisitionController {

    private final RequisitionService requisitionService;

    public RequisitionController(RequisitionService requisitionService) {
        this.requisitionService = requisitionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseRequisition create(@Valid @RequestBody CreateRequisitionRequest request) {
        return requisitionService.create(request);
    }

    @GetMapping
    public List<PurchaseRequisition> findAll(@RequestParam(required = false) RequisitionStatus status) {
        return status == null ? requisitionService.findAll() : requisitionService.findByStatus(status);
    }

    @GetMapping("/{id}")
    public PurchaseRequisition findById(@PathVariable Long id) {
        return requisitionService.findById(id);
    }

    @PostMapping("/{id}/approve")
    public PurchaseRequisition approve(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return requisitionService.approve(id, request.decidedBy(), request.reason());
    }

    @PostMapping("/{id}/reject")
    public PurchaseRequisition reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return requisitionService.reject(id, request.decidedBy(), request.reason());
    }
}
