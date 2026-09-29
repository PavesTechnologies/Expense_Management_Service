package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.service.CashAdvanceAdjustmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Reimbursements", description = "Cash advance settlement and expense reimbursement adjustment endpoints")
@RestController
@RequestMapping("/xms/finance/reimbursements")
@RequiredArgsConstructor
public class CashAdvanceAdjustmentController {

    private final CashAdvanceAdjustmentService cashAdvanceAdjustmentService;

    @Operation(summary = "Create a cash advance settlement adjustment against an expense report")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceAdjustmentResponse> create(@Valid @RequestBody CashAdvanceAdjustmentRequest request) {
        return ApiResponse.success("Cash advance adjustment created", cashAdvanceAdjustmentService.create(request));
    }

    @Operation(summary = "Update an existing cash advance settlement adjustment")
    @PutMapping("/{adjustmentId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceAdjustmentResponse> update(@PathVariable UUID adjustmentId,
                                                              @Valid @RequestBody CashAdvanceAdjustmentRequest request) {
        return ApiResponse.success("Cash advance adjustment updated", cashAdvanceAdjustmentService.update(adjustmentId, request));
    }

    @Operation(summary = "Get a cash advance settlement adjustment by ID")
    @GetMapping("/{adjustmentId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE','MANAGER','GENERAL')")
    public ApiResponse<CashAdvanceAdjustmentResponse> getById(@PathVariable UUID adjustmentId) {
        return ApiResponse.success(cashAdvanceAdjustmentService.getById(adjustmentId));
    }

    @Operation(summary = "Get all cash advance settlement adjustments")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE','MANAGER','GENERAL')")
    public ApiResponse<List<CashAdvanceAdjustmentResponse>> getAll() {
        return ApiResponse.success(cashAdvanceAdjustmentService.getAll());
    }

    @Operation(summary = "Delete a cash advance settlement adjustment (Admin only)")
    @DeleteMapping("/{adjustmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID adjustmentId) {
        cashAdvanceAdjustmentService.delete(adjustmentId);
    }
}

