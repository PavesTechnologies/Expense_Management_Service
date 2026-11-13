package com.expense_management_service.repository;

import com.expense_management_service.entity.ApPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApPaymentAllocationRepository extends JpaRepository<ApPaymentAllocation, UUID> {

    /** Every payment allocation made against one report, oldest first: the report's payment history. */
    List<ApPaymentAllocation> findByReport_ReportIdOrderByBatch_RecordedAtAsc(UUID reportId);
}
