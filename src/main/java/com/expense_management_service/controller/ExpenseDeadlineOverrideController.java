package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.DeadlineOverrideRequest;
import com.expense_management_service.dto.response.DeadlineOverrideResponse;
import com.expense_management_service.service.impl.ExpenseDeadlineOverrideService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Finance deadline overrides for expense reports: EXTEND moves the submission deadline, REOPEN lifts the check.
 * Both require a reason and are recorded in the audit log.
 */
@RestController
@RequestMapping("/xms/finance/expense-reports")
@RequiredArgsConstructor
public class ExpenseDeadlineOverrideController {

    private final ExpenseDeadlineOverrideService deadlineOverrideService;

    @PostMapping(value = "/{reportId}/deadline-override", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<DeadlineOverrideResponse> apply(@PathVariable UUID reportId,
                                                       @Valid @RequestBody DeadlineOverrideRequest request) {
        return ApiResponse.success("Submission deadline updated", deadlineOverrideService.apply(reportId, request));
    }
}
