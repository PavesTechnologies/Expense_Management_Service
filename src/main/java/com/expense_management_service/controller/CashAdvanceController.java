package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.service.CashAdvanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/employee/cash-advances")
@RequiredArgsConstructor
public class CashAdvanceController {

    private final CashAdvanceService cashAdvanceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<CashAdvanceResponse> create(@Valid @RequestBody CashAdvanceRequest request) {
        return ApiResponse.success("Cash advance created", cashAdvanceService.create(request));
    }

    @PutMapping("/{advanceId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<CashAdvanceResponse> update(@PathVariable UUID advanceId, @Valid @RequestBody CashAdvanceRequest request) {
        return ApiResponse.success("Cash advance updated", cashAdvanceService.update(advanceId, request));
    }

    @GetMapping("/{advanceId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    public ApiResponse<CashAdvanceResponse> getById(@PathVariable UUID advanceId) {
        return ApiResponse.success(cashAdvanceService.getById(advanceId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    public ApiResponse<List<CashAdvanceResponse>> getAll() {
        return ApiResponse.success(cashAdvanceService.getAll());
    }

    @DeleteMapping("/{advanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID advanceId) {
        cashAdvanceService.delete(advanceId);
    }
}
