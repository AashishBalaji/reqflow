package com.reqflow.controller;

import com.reqflow.dto.CreateRequisitionRequest;
import com.reqflow.dto.DecisionRequest;
import com.reqflow.dto.RequisitionResponse;
import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionEvent;
import com.reqflow.model.RequisitionStatus;
import com.reqflow.service.RequisitionEventService;
import com.reqflow.service.RequisitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The create endpoint is where the auto-approval policy runs. The
 * approve/reject endpoints below are the exact same "tools" a human
 * reviewer uses from a UI and that the AI triage agent
 * (agent/triage_agent.py) calls as function-calling tools -- there is no
 * separate "agent API"; the agent is just another caller of this REST
 * surface, which is the point of modeling it as tool-use rather than a
 * bespoke integration. The open endpoint is the one addition that isn't a
 * decision: it marks a flagged case as picked up, without deciding it, so
 * an event-log analysis can see queue-time and decision-time separately
 * (see analytics/process_intelligence.py). All POST endpoints require
 * HTTP Basic auth (SecurityConfig); GET stays open for browsing.
 */
@RestController
@RequestMapping("/api/requisitions")
public class RequisitionController {

    private final RequisitionService requisitionService;
    private final RequisitionEventService requisitionEventService;

    public RequisitionController(RequisitionService requisitionService,
                                  RequisitionEventService requisitionEventService) {
        this.requisitionService = requisitionService;
        this.requisitionEventService = requisitionEventService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequisitionResponse create(@Valid @RequestBody CreateRequisitionRequest request) {
        return toResponse(requisitionService.create(request));
    }

    @GetMapping
    public List<RequisitionResponse> findAll(@RequestParam(required = false) RequisitionStatus status) {
        var requisitions = status == null ? requisitionService.findAll() : requisitionService.findByStatus(status);
        if (requisitions.isEmpty()) {
            return List.of();
        }
        Map<Long, List<RequisitionEvent>> eventsByRequisition =
                requisitionEventService.findForRequisitions(requisitions.stream().map(PurchaseRequisition::getId).toList());
        return requisitions.stream()
                .map(r -> RequisitionResponse.from(r, eventsByRequisition.getOrDefault(r.getId(), Collections.emptyList())))
                .toList();
    }

    @GetMapping("/{id}")
    public RequisitionResponse findById(@PathVariable Long id) {
        return toResponse(requisitionService.findById(id));
    }

    @PostMapping("/{id}/approve")
    public RequisitionResponse approve(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return toResponse(requisitionService.approve(id, request.decidedBy(), request.reason()));
    }

    @PostMapping("/{id}/reject")
    public RequisitionResponse reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return toResponse(requisitionService.reject(id, request.decidedBy(), request.reason()));
    }

    @PostMapping("/{id}/open")
    public RequisitionResponse open(@PathVariable Long id) {
        return toResponse(requisitionService.openForReview(id));
    }

    private RequisitionResponse toResponse(PurchaseRequisition requisition) {
        return RequisitionResponse.from(requisition, requisitionEventService.findForRequisition(requisition.getId()));
    }
}
