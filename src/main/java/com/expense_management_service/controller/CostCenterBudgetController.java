package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.service.CostCenterBudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/cost-center-budgets")
@RequiredArgsConstructor
public class CostCenterBudgetController {

    private final CostCenterBudgetService costCenterBudgetService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CostCenterBudgetResponse> create(@Valid @RequestBody CostCenterBudgetRequest request) {
        return ApiResponse.success("Cost center budget created", costCenterBudgetService.create(request));
    }

    @PutMapping("/{budgetId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CostCenterBudgetResponse> update(@PathVariable UUID budgetId,
                                                         @Valid @RequestBody CostCenterBudgetRequest request) {
        return ApiResponse.success("Cost center budget updated", costCenterBudgetService.update(budgetId, request));
    }

    @GetMapping("/{budgetId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<CostCenterBudgetResponse> getById(@PathVariable UUID budgetId) {
        return ApiResponse.success(costCenterBudgetService.getById(budgetId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<CostCenterBudgetResponse>> getAll() {
        return ApiResponse.success(costCenterBudgetService.getAll());
    }

    @DeleteMapping("/{budgetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID budgetId) {
        costCenterBudgetService.delete(budgetId);
    }
}
