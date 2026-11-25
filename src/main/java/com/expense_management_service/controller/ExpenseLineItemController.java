package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.request.PolicyPreviewRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.dto.response.ExpenseDateRangeResponse;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.service.ExpenseLineItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Add/edit/delete expense line item endpoints (EP02-S2), nested under their parent report
 * so a line item can never be addressed independently of the report it belongs to.
 * Ownership and status-gating (Draft / Policy Rejected / Query Raised only) are enforced
 * inside {@link ExpenseLineItemService}.
 */
@RestController
@RequestMapping("/xms/employee/expense-reports/{reportId}/line-items")
@RequiredArgsConstructor
public class ExpenseLineItemController {

    private final ExpenseLineItemService expenseLineItemService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<ExpenseLineItemResponse> create(@PathVariable UUID reportId,
                                                        @Valid @RequestBody ExpenseLineItemRequest request) {
        return ApiResponse.success("Expense line item added", expenseLineItemService.create(reportId, request));
    }

    /**
     * Policy warnings the line-item form would raise as it stands right now, for showing while the
     * employee types. Saves nothing; the same rules run again (authoritatively) on create/update.
     */
    @PostMapping("/policy-preview")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<List<PolicyWarningResponse>> previewPolicy(@PathVariable UUID reportId,
                                                                  @Valid @RequestBody PolicyPreviewRequest request) {
        return ApiResponse.success(expenseLineItemService.previewPolicy(reportId, request));
    }

    @PutMapping("/{lineItemId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<ExpenseLineItemResponse> update(@PathVariable UUID reportId,
                                                        @PathVariable UUID lineItemId,
                                                        @Valid @RequestBody ExpenseLineItemRequest request) {
        return ApiResponse.success("Expense line item updated", expenseLineItemService.update(reportId, lineItemId, request));
    }

    @GetMapping("/{lineItemId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER','FINANCE_EXECUTIVE')")
    public ApiResponse<ExpenseLineItemResponse> getById(@PathVariable UUID reportId, @PathVariable UUID lineItemId) {
        return ApiResponse.success(expenseLineItemService.getById(reportId, lineItemId));
    }

    @GetMapping("/expense-date-range")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<ExpenseDateRangeResponse> expenseDateRange(@PathVariable UUID reportId) {
        return ApiResponse.success(expenseLineItemService.expenseDateRange(reportId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER','FINANCE_EXECUTIVE')")
    public ApiResponse<List<ExpenseLineItemResponse>> getAll(@PathVariable UUID reportId) {
        return ApiResponse.success(expenseLineItemService.getAllForReport(reportId));
    }

    @DeleteMapping("/{lineItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public void delete(@PathVariable UUID reportId, @PathVariable UUID lineItemId) {
        expenseLineItemService.delete(reportId, lineItemId);
    }
}
