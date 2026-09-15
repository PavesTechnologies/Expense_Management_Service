package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseSplitReplaceRequest;
import com.expense_management_service.dto.response.ExpenseSplitResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExpenseSplitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Employee-facing split management, mirroring the existing {@code /xms/employee/expense-line-items}
 * URL convention used by receipts. Whole-set replace, not per-row CRUD - see
 * {@link ExpenseSplitService}'s javadoc for why. {@code GENERAL} is included deliberately (unlike
 * {@code CostAllocationController}, which never granted it) so an employee can manage their own
 * splits directly; ownership is enforced in the service layer regardless of role.
 */
@RestController
@RequestMapping("/xms/employee/expense-line-items/{lineItemId}/splits")
@RequiredArgsConstructor
public class ExpenseSplitController {

    private final ExpenseSplitService expenseSplitService;
    private final CurrentUserService currentUserService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    public ApiResponse<List<ExpenseSplitResponse>> getSplits(@PathVariable UUID lineItemId) {
        return ApiResponse.success(expenseSplitService.getSplitsForLineItem(lineItemId, currentUserService.getEmployeeId()));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    public ApiResponse<List<ExpenseSplitResponse>> replaceSplits(@PathVariable UUID lineItemId,
                                                                  @Valid @RequestBody ExpenseSplitReplaceRequest request) {
        return ApiResponse.success("Expense splits saved",
                expenseSplitService.replaceSplitsForLineItem(lineItemId, request, currentUserService.getEmployeeId()));
    }
}
