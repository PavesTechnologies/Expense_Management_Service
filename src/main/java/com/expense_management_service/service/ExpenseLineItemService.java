package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ExpenseLineItemService {

    ExpenseLineItemResponse create(ExpenseLineItemRequest request);

    ExpenseLineItemResponse update(UUID lineItemId, ExpenseLineItemRequest request);

    ExpenseLineItemResponse getById(UUID lineItemId);

    Page<ExpenseLineItemResponse> getAll(Pageable pageable);

    void delete(UUID lineItemId);
}
