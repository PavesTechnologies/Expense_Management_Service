package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.service.ExpenseLineItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expense-line-items")
@RequiredArgsConstructor
public class ExpenseLineItemController {

    private final ExpenseLineItemService expenseLineItemService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExpenseLineItemResponse> create(@Valid @RequestBody ExpenseLineItemRequest request) {
        return ApiResponse.success("Expense line item created", expenseLineItemService.create(request));
    }

    @PutMapping("/{lineItemId}")
    public ApiResponse<ExpenseLineItemResponse> update(@PathVariable UUID lineItemId,
                                                        @Valid @RequestBody ExpenseLineItemRequest request) {
        return ApiResponse.success("Expense line item updated", expenseLineItemService.update(lineItemId, request));
    }

    @GetMapping("/{lineItemId}")
    public ApiResponse<ExpenseLineItemResponse> getById(@PathVariable UUID lineItemId) {
        return ApiResponse.success(expenseLineItemService.getById(lineItemId));
    }

    @GetMapping
    public ApiResponse<Page<ExpenseLineItemResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(expenseLineItemService.getAll(pageable));
    }

    @DeleteMapping("/{lineItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID lineItemId) {
        expenseLineItemService.delete(lineItemId);
    }
}
