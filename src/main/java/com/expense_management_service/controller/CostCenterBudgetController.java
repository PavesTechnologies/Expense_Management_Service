package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.service.CostCenterBudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cost-center-budgets")
@RequiredArgsConstructor
public class CostCenterBudgetController {

    private final CostCenterBudgetService costCenterBudgetService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CostCenterBudgetResponse> create(@Valid @RequestBody CostCenterBudgetRequest request) {
        return ApiResponse.success("Cost center budget created", costCenterBudgetService.create(request));
    }

    @PutMapping("/{budgetId}")
    public ApiResponse<CostCenterBudgetResponse> update(@PathVariable UUID budgetId,
                                                         @Valid @RequestBody CostCenterBudgetRequest request) {
        return ApiResponse.success("Cost center budget updated", costCenterBudgetService.update(budgetId, request));
    }

    @GetMapping("/{budgetId}")
    public ApiResponse<CostCenterBudgetResponse> getById(@PathVariable UUID budgetId) {
        return ApiResponse.success(costCenterBudgetService.getById(budgetId));
    }

    @GetMapping
    public ApiResponse<Page<CostCenterBudgetResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(costCenterBudgetService.getAll(pageable));
    }

    @DeleteMapping("/{budgetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID budgetId) {
        costCenterBudgetService.delete(budgetId);
    }
}
