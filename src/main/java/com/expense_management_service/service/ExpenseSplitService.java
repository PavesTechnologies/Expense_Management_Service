package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseSplitReplaceRequest;
import com.expense_management_service.dto.response.ExpenseSplitResponse;

import java.util.List;
import java.util.UUID;

/**
 * Manages an {@code ExpenseLineItem}'s split allocations. Deliberately a whole-set replace
 * operation, not per-row CRUD like {@code CostAllocationService} - Group 1's validation rules
 * (minimum 2 splits, no duplicate cost center, amount/percentage reconciliation) are set-level
 * invariants that a per-row API cannot enforce without ever persisting a transiently-invalid state
 * (e.g. exactly 1 split) in between calls.
 */
public interface ExpenseSplitService {

    /** Current splits for a line item, in entry order. Empty for a NORMAL line item. */
    List<ExpenseSplitResponse> getSplitsForLineItem(UUID lineItemId, String actingEmployeeId);

    /**
     * Validates and atomically replaces every split on the line item. An empty list reverts the
     * line item to NORMAL. Only the report's own owner may call this, and only while the report is
     * in an editable status ({@code ReportStatus.isEditable()}).
     */
    List<ExpenseSplitResponse> replaceSplitsForLineItem(UUID lineItemId, ExpenseSplitReplaceRequest request, String actingEmployeeId);
}
