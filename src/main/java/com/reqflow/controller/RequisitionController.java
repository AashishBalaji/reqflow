package com.reqflow.controller;

import com.reqflow.dto.CreateRequisitionRequest;
import com.reqflow.dto.DecisionRequest;
import com.reqflow.dto.RequisitionResponse;
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
 * bespoke integration. All three POST endpoints require HTTP Basic auth
 * (SecurityConfig); GET stays open for browsing.
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
    public RequisitionResponse create(@Valid @RequestBody CreateRequisitionRequest request) {
        return RequisitionResponse.from(requisitionService.create(request));
    }

    @GetMapping
    public List<RequisitionResponse> findAll(@RequestParam(required = false) RequisitionStatus status) {
        var requisitions = status == null ? requisitionService.findAll() : requisitionService.findByStatus(status);
        return requisitions.stream().map(RequisitionResponse::from).toList();
    }

    @GetMapping("/{id}")
    public RequisitionResponse findById(@PathVariable Long id) {
        return RequisitionResponse.from(requisitionService.findById(id));
    }

    @PostMapping("/{id}/approve")
    public RequisitionResponse approve(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return RequisitionResponse.from(requisitionService.approve(id, request.decidedBy(), request.reason()));
    }

    @PostMapping("/{id}/reject")
    public RequisitionResponse reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return RequisitionResponse.from(requisitionService.reject(id, request.decidedBy(), request.reason()));
    }
}
