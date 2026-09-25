package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CashAdvanceAdjustmentRepository extends JpaRepository<CashAdvanceAdjustment, UUID> {
    List<CashAdvanceAdjustment> findByCashAdvance_AdvanceId(UUID advanceId);
    List<CashAdvanceAdjustment> findByReport_ReportId(UUID reportId);
}

