package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceReconciliationLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CashAdvanceReconciliationLineRepository extends JpaRepository<CashAdvanceReconciliationLine, UUID> {

    List<CashAdvanceReconciliationLine> findByRun_ReconciliationRunIdOrderByEmployeeIdAsc(UUID runId);
}
