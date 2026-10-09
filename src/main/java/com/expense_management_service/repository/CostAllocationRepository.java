package com.expense_management_service.repository;

import com.expense_management_service.entity.CostAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CostAllocationRepository extends JpaRepository<CostAllocation, UUID> {

    /** Production-readiness audit (Part 5): backs the legacy-vs-new-split conflict guard - a line item must use CostAllocation or ExpenseSplit, never both. */
    List<CostAllocation> findByLineItem_LineItemId(UUID lineItemId);
}
