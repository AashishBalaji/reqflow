package com.reqflow.service;

import com.reqflow.model.RequisitionEvent;
import com.reqflow.repository.RequisitionEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Appends to and reads back each requisition's activity log. This is
 * deliberately kept separate from RequisitionService: that service owns
 * business state (status, budget commits), this one owns the append-only
 * history of what happened and when -- a requisition's current state can be
 * fully reconstructed without it, but the history can't be reconstructed
 * without having recorded it as it happened.
 */
@Service
public class RequisitionEventService {

    private final RequisitionEventRepository eventRepository;

    public RequisitionEventService(RequisitionEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public void record(Long requisitionId, String activity, String detail) {
        eventRepository.save(new RequisitionEvent(requisitionId, activity, detail, Instant.now()));
    }

    public List<RequisitionEvent> findForRequisition(Long requisitionId) {
        return eventRepository.findByRequisitionIdOrderByOccurredAtAscIdAsc(requisitionId);
    }

    /** One query for however many requisitions are being listed, grouped
     * back out by requisition id -- avoids N+1 when building a list
     * response. */
    public Map<Long, List<RequisitionEvent>> findForRequisitions(List<Long> requisitionIds) {
        return eventRepository.findByRequisitionIdInOrderByRequisitionIdAscOccurredAtAscIdAsc(requisitionIds)
                .stream()
                .collect(Collectors.groupingBy(RequisitionEvent::getRequisitionId));
    }
}
