package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvanceReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CashAdvanceReconciliationRunRepository extends JpaRepository<CashAdvanceReconciliationRun, UUID> {

    /** A run for the same period that has not been closed yet. Closed runs are kept; a new run may follow them. */
    Optional<CashAdvanceReconciliationRun> findFirstByPeriodTypeAndPeriodStartAndStatusNot(
            String periodType, LocalDate periodStart, String status);
}
