package com.reqflow.repository;

import com.reqflow.model.RequisitionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequisitionEventRepository extends JpaRepository<RequisitionEvent, Long> {

    List<RequisitionEvent> findByRequisitionIdOrderByOccurredAtAscIdAsc(Long requisitionId);

    /** Bulk fetch for findAll()-style listings, so showing N requisitions'
     * event logs doesn't cost N+1 queries. */
    List<RequisitionEvent> findByRequisitionIdInOrderByRequisitionIdAscOccurredAtAscIdAsc(List<Long> requisitionIds);
}
