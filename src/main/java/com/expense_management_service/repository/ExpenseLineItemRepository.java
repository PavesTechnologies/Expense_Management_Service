package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseLineItemRepository extends JpaRepository<ExpenseLineItem, UUID> {

    List<ExpenseLineItem> findByReport_ReportId(UUID reportId);

    /** Path-scoped lookup — guarantees a line item is only ever addressed through its own parent report. */
    Optional<ExpenseLineItem> findByLineItemIdAndReport_ReportId(UUID lineItemId, UUID reportId);

    /** Sum of every line item's base-currency amount for a report — the report-level total is always presented in base currency. */
    @Query("select coalesce(sum(l.baseAmount), 0) from ExpenseLineItem l where l.report.reportId = :reportId")
    BigDecimal sumBaseAmountByReportId(@Param("reportId") UUID reportId);
}