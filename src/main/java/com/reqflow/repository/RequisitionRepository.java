package com.reqflow.repository;

import com.reqflow.model.PurchaseRequisition;
import com.reqflow.model.RequisitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequisitionRepository extends JpaRepository<PurchaseRequisition, Long> {
    List<PurchaseRequisition> findByStatus(RequisitionStatus status);
}
