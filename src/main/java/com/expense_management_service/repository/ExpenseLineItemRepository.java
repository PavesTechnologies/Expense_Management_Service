package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseLineItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseLineItemRepository extends JpaRepository<ExpenseLineItem, UUID> {

    List<ExpenseLineItem> findByReport_ReportId(UUID reportId);

    /** Path-scoped lookup — guarantees a line item is only ever addressed through its own parent report. */
    Optional<ExpenseLineItem> findByLineItemIdAndReport_ReportId(UUID lineItemId, UUID reportId);
}