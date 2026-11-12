package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseSplit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExpenseSplitRepository extends JpaRepository<ExpenseSplit, UUID> {

    /** The line item's CURRENT split state - excludes soft-deleted (removedAt != null) rows kept only for approval-history FK integrity. */
    List<ExpenseSplit> findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(UUID lineItemId);
}
