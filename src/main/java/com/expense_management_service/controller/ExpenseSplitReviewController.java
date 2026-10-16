package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.ExpenseSplitResponse;
import com.expense_management_service.service.ExpenseSplitService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only split allocation for Finance verification and AP payment review, so reviewers see how
 * one reimbursed bill is charged across cost centers. Editing stays with the report owner
 * ({@link ExpenseSplitController}).
 */
@RestController
@RequestMapping("/xms/finance/expense-line-items/{lineItemId}/splits")
@RequiredArgsConstructor
public class ExpenseSplitReviewController {

    private final ExpenseSplitService expenseSplitService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    public ApiResponse<List<ExpenseSplitResponse>> getSplits(@PathVariable UUID lineItemId) {
        return ApiResponse.success(expenseSplitService.getSplitsForReview(lineItemId));
    }
}
