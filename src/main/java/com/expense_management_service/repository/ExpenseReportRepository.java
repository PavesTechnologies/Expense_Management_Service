package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseReportRepository extends JpaRepository<ExpenseReport, UUID> {

    /** Duplicate-title validation — a title only needs to be unique for one employee within one fiscal period. */
    Optional<ExpenseReport> findByEmployeeIdAndFiscalYearAndTitleIgnoreCase(
            String employeeId, String fiscalYear, String title);

    /** Scopes the report list to the requesting Employee's own reports. */
    List<ExpenseReport> findByEmployeeId(String employeeId);
}
