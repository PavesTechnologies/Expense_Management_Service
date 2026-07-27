package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import java.util.List;
import java.util.UUID;

/**
 * Add/edit/delete expense line item business logic (EP02-S2).
 * <p>
 * Every operation is scoped to a parent report ({@code reportId}), never addressed by
 * {@code lineItemId} alone — this guarantees a line item can only be reached through the
 * report it actually belongs to, and lets the implementation enforce ownership and
 * status-gating (Draft / Policy Rejected / Query Raised only) at the report level.
 */
public interface ExpenseLineItemService {

    ExpenseLineItemResponse create(UUID reportId, ExpenseLineItemRequest request);

    ExpenseLineItemResponse update(UUID reportId, UUID lineItemId, ExpenseLineItemRequest request);

    ExpenseLineItemResponse getById(UUID reportId, UUID lineItemId);

    List<ExpenseLineItemResponse> getAllForReport(UUID reportId);

    void delete(UUID reportId, UUID lineItemId);
}
