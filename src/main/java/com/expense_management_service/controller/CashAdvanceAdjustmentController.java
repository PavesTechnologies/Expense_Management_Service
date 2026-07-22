package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.service.CashAdvanceAdjustmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cash-advance-adjustments")
@RequiredArgsConstructor
public class CashAdvanceAdjustmentController {

    private final CashAdvanceAdjustmentService cashAdvanceAdjustmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CashAdvanceAdjustmentResponse> create(@Valid @RequestBody CashAdvanceAdjustmentRequest request) {
        return ApiResponse.success("Cash advance adjustment created", cashAdvanceAdjustmentService.create(request));
    }

    @PutMapping("/{adjustmentId}")
    public ApiResponse<CashAdvanceAdjustmentResponse> update(@PathVariable UUID adjustmentId,
                                                              @Valid @RequestBody CashAdvanceAdjustmentRequest request) {
        return ApiResponse.success("Cash advance adjustment updated", cashAdvanceAdjustmentService.update(adjustmentId, request));
    }

    @GetMapping("/{adjustmentId}")
    public ApiResponse<CashAdvanceAdjustmentResponse> getById(@PathVariable UUID adjustmentId) {
        return ApiResponse.success(cashAdvanceAdjustmentService.getById(adjustmentId));
    }

    @GetMapping
    public ApiResponse<Page<CashAdvanceAdjustmentResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(cashAdvanceAdjustmentService.getAll(pageable));
    }

    @DeleteMapping("/{adjustmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID adjustmentId) {
        cashAdvanceAdjustmentService.delete(adjustmentId);
    }
}
