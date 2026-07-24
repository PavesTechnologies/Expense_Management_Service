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

@RestController
@RequestMapping("/xms/finance/reimbursements")
@RequiredArgsConstructor
public class CashAdvanceAdjustmentController {

    private final CashAdvanceAdjustmentService cashAdvanceAdjustmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<CashAdvanceAdjustmentResponse> create(@Valid @RequestBody CashAdvanceAdjustmentRequest request) {
        return ApiResponse.success("Cash advance adjustment created", cashAdvanceAdjustmentService.create(request));
    }

    @PutMapping("/{adjustmentId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<CashAdvanceAdjustmentResponse> update(@PathVariable UUID adjustmentId,
                                                              @Valid @RequestBody CashAdvanceAdjustmentRequest request) {
        return ApiResponse.success("Cash advance adjustment updated", cashAdvanceAdjustmentService.update(adjustmentId, request));
    }

    @GetMapping("/{adjustmentId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','EMPLOYEE')")
    public ApiResponse<CashAdvanceAdjustmentResponse> getById(@PathVariable UUID adjustmentId) {
        return ApiResponse.success(cashAdvanceAdjustmentService.getById(adjustmentId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER','EMPLOYEE')")
    public ApiResponse<List<CashAdvanceAdjustmentResponse>> getAll() {
        return ApiResponse.success(cashAdvanceAdjustmentService.getAll());
    }

    @DeleteMapping("/{adjustmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID adjustmentId) {
        cashAdvanceAdjustmentService.delete(adjustmentId);
    }
}
