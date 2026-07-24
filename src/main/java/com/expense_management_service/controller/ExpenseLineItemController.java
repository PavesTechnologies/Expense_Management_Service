package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.service.ExpenseLineItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/employee/expenses")
@RequiredArgsConstructor
public class ExpenseLineItemController {

    private final ExpenseLineItemService expenseLineItemService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    public ApiResponse<ExpenseLineItemResponse> create(@Valid @RequestBody ExpenseLineItemRequest request) {
        return ApiResponse.success("Expense line item created", expenseLineItemService.create(request));
    }

    @PutMapping("/{lineItemId}")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
    public ApiResponse<ExpenseLineItemResponse> update(@PathVariable UUID lineItemId,
                                                        @Valid @RequestBody ExpenseLineItemRequest request) {
        return ApiResponse.success("Expense line item updated", expenseLineItemService.update(lineItemId, request));
    }

    @GetMapping("/{lineItemId}")
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    public ApiResponse<ExpenseLineItemResponse> getById(@PathVariable UUID lineItemId) {
        return ApiResponse.success(expenseLineItemService.getById(lineItemId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE','FINANCE','MANAGER')")
    public ApiResponse<List<ExpenseLineItemResponse>> getAll() {
        return ApiResponse.success(expenseLineItemService.getAll());
    }

    @DeleteMapping("/{lineItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID lineItemId) {
        expenseLineItemService.delete(lineItemId);
    }
}
