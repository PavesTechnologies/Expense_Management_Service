package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.service.CashAdvanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cash-advances")
@RequiredArgsConstructor
public class CashAdvanceController {

    private final CashAdvanceService cashAdvanceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CashAdvanceResponse> create(@Valid @RequestBody CashAdvanceRequest request) {
        return ApiResponse.success("Cash advance created", cashAdvanceService.create(request));
    }

    @PutMapping("/{advanceId}")
    public ApiResponse<CashAdvanceResponse> update(@PathVariable UUID advanceId, @Valid @RequestBody CashAdvanceRequest request) {
        return ApiResponse.success("Cash advance updated", cashAdvanceService.update(advanceId, request));
    }

    @GetMapping("/{advanceId}")
    public ApiResponse<CashAdvanceResponse> getById(@PathVariable UUID advanceId) {
        return ApiResponse.success(cashAdvanceService.getById(advanceId));
    }

    @GetMapping
    public ApiResponse<Page<CashAdvanceResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(cashAdvanceService.getAll(pageable));
    }

    @DeleteMapping("/{advanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID advanceId) {
        cashAdvanceService.delete(advanceId);
    }
}
