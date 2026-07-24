package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import java.util.List;
import java.util.UUID;

public interface ExpenseLineItemService {

    ExpenseLineItemResponse create(ExpenseLineItemRequest request);

    ExpenseLineItemResponse update(UUID lineItemId, ExpenseLineItemRequest request);

    ExpenseLineItemResponse getById(UUID lineItemId);

    List<ExpenseLineItemResponse> getAll();

    void delete(UUID lineItemId);
}
